package com.fangyang.federatedquery.executor;

import com.fangyang.datasource.DataSourceAdapter;
import com.fangyang.datasource.DataSourceQueryParams;
import com.fangyang.federatedquery.exception.ErrorCode;
import com.fangyang.federatedquery.exception.GraphQueryException;
import com.fangyang.federatedquery.model.QueryResult;
import com.fangyang.federatedquery.plan.ExternalQuery;
import com.fangyang.federatedquery.plan.PhysicalQuery;
import com.fangyang.metadata.DataSourceMetadata;
import com.fangyang.metadata.MetadataQueryService;
import org.neo4j.driver.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

class QueryExecutionGateway {
    private static final Logger log = LoggerFactory.getLogger(QueryExecutionGateway.class);
    private static final int DEFAULT_MAX_RETRIES = 3;

    private final AdapterRegistry adapterRegistry;
    private final ExecutionRuntime executionRuntime;
    private final Supplier<MetadataQueryService> metadataQueryServiceSupplier;
    private final QueryParamBuilder queryParamBuilder;
    private final QueryResultAssembler queryResultAssembler;

    QueryExecutionGateway(
            AdapterRegistry adapterRegistry,
            ExecutionRuntime executionRuntime,
            Supplier<MetadataQueryService> metadataQueryServiceSupplier,
            QueryParamBuilder queryParamBuilder,
            QueryResultAssembler queryResultAssembler) {
        this.adapterRegistry = adapterRegistry;
        this.executionRuntime = executionRuntime;
        this.metadataQueryServiceSupplier = metadataQueryServiceSupplier;
        this.queryParamBuilder = queryParamBuilder;
        this.queryResultAssembler = queryResultAssembler;
    }

    CompletableFuture<QueryResult> executePhysical(PhysicalQuery query) {
        DataSourceAdapter adapter = resolveTuGraphAdapter();
        if (adapter == null) {
            log.error("Physical query failed [queryId={}]: No TuGraph adapter registered", query.getId());
            return CompletableFuture.failedFuture(new GraphQueryException(
                    ErrorCode.DATASOURCE_CONNECTION_ERROR,
                    "No TuGraph adapter registered"));
        }

        return executionRuntime.withTimeout(executionRuntime.supplyAsync(() -> {
            List<Record> records;
            if (query.getParameters() != null && !query.getParameters().isEmpty()) {
                records = adapter.executeTuGraphQuery(query.getCypher(), query.getParameters());
            } else {
                records = adapter.executeTuGraphQuery(query.getCypher());
            }
            return queryResultAssembler.fromRecords(records, "tugraph");
        })).exceptionallyCompose(throwable -> handlePhysicalFailure(query, throwable));
    }

    CompletableFuture<QueryResult> executeExternal(ExternalQuery query) {
        DataSourceAdapter adapter = adapterRegistry.resolve(query.getDataSource());
        if (adapter == null) {
            log.error("External query failed [queryId={}] [dataSource={}]: No adapter found",
                    query.getId(), query.getDataSource());
            return CompletableFuture.failedFuture(new GraphQueryException(
                    ErrorCode.DATASOURCE_CONNECTION_ERROR,
                    "No adapter found for data source: " + query.getDataSource()));
        }

        return executeWithRetry(adapter, query, getMaxRetries(query.getDataSource()));
    }

    CompletableFuture<QueryResult> executeBatch(BatchRequest batch) {
        DataSourceAdapter adapter = adapterRegistry.resolve(batch.getDataSource());
        if (adapter == null) {
            log.error("Batch query failed [batchId={}] [dataSource={}]: No adapter found",
                    batch.getId(), batch.getDataSource());
            return CompletableFuture.failedFuture(new GraphQueryException(
                    ErrorCode.DATASOURCE_CONNECTION_ERROR,
                    "No adapter for data source: " + batch.getDataSource()));
        }

        DataSourceQueryParams params = queryParamBuilder.build(batch);
        return executionRuntime.withTimeout(executionRuntime.supplyAsync(() -> {
            List<Map<String, Object>> data = adapter.executeExternalQuery(params);
            return queryResultAssembler.fromMaps(data, batch.getDataSource(), null);
        })).exceptionallyCompose(throwable -> handleBatchFailure(batch, throwable));
    }

    private DataSourceAdapter resolveTuGraphAdapter() {
        DataSourceAdapter adapter = adapterRegistry.resolve("tugraph");
        return adapter != null ? adapter : adapterRegistry.resolve("TuGraph");
    }

    private CompletableFuture<QueryResult> executeWithRetry(DataSourceAdapter adapter, ExternalQuery query, int maxRetries) {
        return executeWithRetryInternal(adapter, query, maxRetries, 0);
    }

    private CompletableFuture<QueryResult> executeWithRetryInternal(
            DataSourceAdapter adapter,
            ExternalQuery query,
            int maxRetries,
            int attempt) {
        return executionRuntime.withTimeout(executionRuntime.supplyAsync(() -> executeAdapterQuery(adapter, query)))
                .thenApply(result -> queryResultAssembler.normalizeExternalResult(query, result))
                .handle((result, throwable) -> {
                    if (throwable == null) {
                        return CompletableFuture.completedFuture(result);
                    }

                    Throwable cause = unwrapThrowable(throwable);
                    if (attempt < maxRetries) {
                        log.warn("External query attempt {}/{} failed for data source: {}, retrying...",
                                attempt + 1, maxRetries + 1, query.getDataSource(), cause);
                        return scheduleRetry(adapter, query, maxRetries, attempt + 1);
                    }

                    log.error("External query failed after {} attempts [queryId={}] [dataSource={}]",
                            maxRetries + 1, query.getId(), query.getDataSource(), cause);
                    return CompletableFuture.<QueryResult>failedFuture(new GraphQueryException(
                            ErrorCode.EXTERNAL_DATASOURCE_ERROR,
                            "External query failed after " + (maxRetries + 1) + " attempts",
                            cause));
                })
                .thenCompose(future -> future);
    }

    private CompletableFuture<QueryResult> scheduleRetry(
            DataSourceAdapter adapter,
            ExternalQuery query,
            int maxRetries,
            int nextAttempt) {
        Executor delayedExecutor = executionRuntime.delayedExecutor(nextAttempt);
        return CompletableFuture.completedFuture(null)
                .thenRunAsync(() -> { }, delayedExecutor)
                .thenCompose(ignored -> executeWithRetryInternal(adapter, query, maxRetries, nextAttempt));
    }

    private QueryResult executeAdapterQuery(DataSourceAdapter adapter, ExternalQuery query) {
        if ("tugraph".equalsIgnoreCase(query.getDataSource()) || "TuGraph".equalsIgnoreCase(query.getDataSource())) {
            String cypher = extractCypher(query);
            List<Record> records;
            if (query.getParameters() != null && !query.getParameters().isEmpty()) {
                records = adapter.executeTuGraphQuery(cypher, query.getParameters());
            } else {
                records = adapter.executeTuGraphQuery(cypher);
            }
            return queryResultAssembler.fromRecords(records, query.getDataSource());
        }

        DataSourceQueryParams params = queryParamBuilder.build(query);
        List<Map<String, Object>> data = adapter.executeExternalQuery(params);
        return queryResultAssembler.fromMaps(data, query.getDataSource(), query.getTargetLabel());
    }

    private String extractCypher(ExternalQuery query) {
        Object cypher = query.getFilters().get("cypher");
        return cypher != null ? cypher.toString() : "";
    }

    private int getMaxRetries(String dataSourceName) {
        MetadataQueryService metadataQueryService = metadataQueryServiceSupplier.get();
        if (metadataQueryService == null) {
            return DEFAULT_MAX_RETRIES;
        }

        return metadataQueryService.getDataSource(dataSourceName)
                .map(DataSourceMetadata::getMaxRetries)
                .filter(retries -> retries > 0)
                .orElse(DEFAULT_MAX_RETRIES);
    }

    private CompletableFuture<QueryResult> handlePhysicalFailure(PhysicalQuery query, Throwable throwable) {
        Throwable cause = unwrapAsyncFailure(throwable);
        if (cause instanceof TimeoutException) {
            log.error("Physical query timeout after {}ms [queryId={}]: {}",
                    executionRuntime.getTimeoutMs(), query.getId(), query.getCypher());
            return CompletableFuture.failedFuture(new GraphQueryException(
                    ErrorCode.DATASOURCE_QUERY_ERROR,
                    "Physical query timeout after " + executionRuntime.getTimeoutMs() + "ms",
                    cause));
        }
        return CompletableFuture.failedFuture(toGraphQueryException(
                cause,
                ErrorCode.DATASOURCE_QUERY_ERROR,
                "Physical query failed [queryId=" + query.getId() + "]"));
    }

    private CompletableFuture<QueryResult> handleBatchFailure(BatchRequest batch, Throwable throwable) {
        Throwable cause = unwrapAsyncFailure(throwable);
        if (cause instanceof TimeoutException) {
            log.error("Batch query timeout after {}ms [batchId={}] [dataSource={}]",
                    executionRuntime.getTimeoutMs(), batch.getId(), batch.getDataSource());
            return CompletableFuture.failedFuture(new GraphQueryException(
                    ErrorCode.BATCH_EXECUTION_ERROR,
                    "Batch query timeout after " + executionRuntime.getTimeoutMs() + "ms",
                    cause));
        }

        GraphQueryException exception = toBatchGraphQueryException(batch, cause);
        log.error("Batch query failed [batchId={}] [dataSource={}]: {}",
                batch.getId(), batch.getDataSource(), exception.getMessage(), cause);
        return CompletableFuture.failedFuture(exception);
    }

    private GraphQueryException toBatchGraphQueryException(BatchRequest batch, Throwable cause) {
        if (cause instanceof GraphQueryException graphQueryException) {
            if (graphQueryException.getErrorCode() == ErrorCode.DATASOURCE_CONNECTION_ERROR
                    || graphQueryException.getErrorCode() == ErrorCode.BATCH_EXECUTION_ERROR) {
                return graphQueryException;
            }
        }
        return new GraphQueryException(
                ErrorCode.BATCH_EXECUTION_ERROR,
                "Batch query failed [batchId=" + batch.getId() + "]",
                cause);
    }

    private GraphQueryException toGraphQueryException(Throwable cause, ErrorCode errorCode, String message) {
        if (cause instanceof GraphQueryException graphQueryException) {
            return graphQueryException;
        }
        return new GraphQueryException(errorCode, message, cause);
    }

    private Throwable unwrapThrowable(Throwable throwable) {
        if (throwable instanceof CompletionException || throwable instanceof ExecutionException) {
            return throwable.getCause() != null ? throwable.getCause() : throwable;
        }
        return throwable;
    }

    private Throwable unwrapAsyncFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException || current instanceof ExecutionException) {
            if (current.getCause() == null) {
                break;
            }
            current = current.getCause();
        }
        return current;
    }
}
