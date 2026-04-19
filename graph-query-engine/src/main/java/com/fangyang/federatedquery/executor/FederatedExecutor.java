package com.fangyang.federatedquery.executor;

import com.fangyang.datasource.DataSourceAdapter;
import com.fangyang.datasource.DataSourceQueryParams;
import com.fangyang.federatedquery.GraphEntity;
import com.fangyang.federatedquery.QueryResult;
import com.fangyang.federatedquery.exception.ErrorCode;
import com.fangyang.federatedquery.exception.GraphQueryException;
import com.fangyang.metadata.DataSourceMetadata;
import com.fangyang.metadata.LabelMetadata;
import com.fangyang.metadata.MetadataQueryService;
import com.fangyang.federatedquery.plan.*;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class FederatedExecutor {
    private static final Logger log = LoggerFactory.getLogger(FederatedExecutor.class);

    private static final long DEFAULT_TIMEOUT_MS = 30000;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 100;
    private static final int CORE_POOL_SIZE = 10;
    private static final int MAX_POOL_SIZE = 20;
    private static final int QUEUE_CAPACITY = 100;
    private static final long KEEP_ALIVE_TIME = 60L;

    @Autowired
    private MetadataQueryService metadataQueryService;
    @Autowired
    private DependencyResolver dependencyResolver;
    @Autowired
    private ResultEnricher resultEnricher;

    private final Map<String, DataSourceAdapter> adapters;
    private final ExecutorService executorService;
    private final BatchingStrategy batchingStrategy;
    private long timeoutMs = DEFAULT_TIMEOUT_MS;

    public FederatedExecutor() {
        this.adapters = new ConcurrentHashMap<>();
        this.executorService = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        this.batchingStrategy = new BatchingStrategy();
        this.dependencyResolver = new DependencyResolver();
        this.resultEnricher = new ResultEnricher();
    }

    public FederatedExecutor(MetadataQueryService metadataQueryService,
                             DependencyResolver dependencyResolver,
                             ResultEnricher resultEnricher) {
        this();
        this.metadataQueryService = metadataQueryService;
        this.dependencyResolver = dependencyResolver;
        this.resultEnricher = resultEnricher;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void registerAdapter(String name, DataSourceAdapter adapter) {
        adapters.put(name, adapter);
    }

    public CompletableFuture<ExecutionResult> execute(ExecutionPlan plan) {
        ExecutionResult result = new ExecutionResult();
        result.setPlanId(plan.getPlanId());

        List<PhysicalQuery> physicalQueries = plan.getPhysicalQueries();
        List<ExternalQuery> externalQueries = plan.getExternalQueries();

        List<ExternalQuery> independentQueries = externalQueries.stream()
                .filter(q -> !q.isDependsOnPhysicalQuery())
                .collect(Collectors.toList());

        List<ExternalQuery> dependentQueries = externalQueries.stream()
                .filter(ExternalQuery::isDependsOnPhysicalQuery)
                .collect(Collectors.toList());

        if (dependentQueries.isEmpty()) {
            return executeAllInParallel(plan, result, physicalQueries, independentQueries);
        }

        return executeWithDependencyAwareness(plan, result, physicalQueries, independentQueries, dependentQueries);
    }

    private CompletableFuture<ExecutionResult> executeAllInParallel(
            ExecutionPlan plan,
            ExecutionResult result,
            List<PhysicalQuery> physicalQueries,
            List<ExternalQuery> independentQueries) {

        List<CompletableFuture<QueryResult>> futures = new ArrayList<>();
        futures.addAll(schedulePhysicalQueries(physicalQueries, result));
        futures.addAll(scheduleIndependentQueries(independentQueries, result));
        futures.addAll(scheduleUnionQueries(plan.getUnionParts(), result));
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApplyAsync(v -> markExecutionSuccess(result), executorService);
    }

    private CompletableFuture<ExecutionResult> executeWithDependencyAwareness(
            ExecutionPlan plan,
            ExecutionResult result,
            List<PhysicalQuery> physicalQueries,
            List<ExternalQuery> independentQueries,
            List<ExternalQuery> dependentQueries) {

        List<CompletableFuture<QueryResult>> physicalFutures = schedulePhysicalQueries(physicalQueries, result);
        List<CompletableFuture<QueryResult>> independentFutures = scheduleIndependentQueries(independentQueries, result);

        return CompletableFuture.allOf(physicalFutures.toArray(new CompletableFuture[0]))
                .thenComposeAsync(physicalVoid -> {
                    log.debug("Physical queries completed, extracting IDs for dependent queries");

                    Map<String, Map<String, Set<String>>> dataByVariable =
                            dependencyResolver.extractIdsAndPropertiesFromPhysicalResults(result);
                    dependencyResolver.populateDependentQueryInputIds(dependentQueries, dataByVariable);

                    DependencyResolver.DependencyClassification classification =
                            dependencyResolver.classifyDependentQueries(dependentQueries);

                    List<CompletableFuture<QueryResult>> dependentFutures = new ArrayList<>();

                    for (ExternalQuery query : classification.getNotReadyQueries()) {
                        log.debug("External query {} is not ready to execute - no input IDs available, returning empty result", query.getId());
                        result.addExternalResult(new QueryResult());
                    }

                    for (ExternalQuery query : classification.getDirectQueries()) {
                        dependentFutures.add(scheduleDependentQuery(query, result));
                    }

                    if (!classification.getBatchQueries().isEmpty()) {
                        List<BatchRequest> batches = batchingStrategy.batch(classification.getBatchQueries());
                        for (BatchRequest batch : batches) {
                            dependentFutures.add(scheduleBatchQuery(batch, result));
                        }
                    }

                    CompletableFuture<Void> dependentVoid = CompletableFuture.allOf(
                            dependentFutures.toArray(new CompletableFuture[0]));

                    CompletableFuture<Void> independentVoid = CompletableFuture.allOf(
                            independentFutures.toArray(new CompletableFuture[0]));

                    return CompletableFuture.allOf(dependentVoid, independentVoid);
                }, executorService)
                .thenComposeAsync(v -> CompletableFuture.allOf(
                        scheduleUnionQueries(plan.getUnionParts(), result).toArray(new CompletableFuture[0])
                ), executorService)
                .thenApplyAsync(v -> markExecutionSuccess(result), executorService);
    }

    private List<CompletableFuture<QueryResult>> schedulePhysicalQueries(
            List<PhysicalQuery> physicalQueries,
            ExecutionResult result) {
        List<CompletableFuture<QueryResult>> futures = new ArrayList<>();
        for (PhysicalQuery query : physicalQueries) {
            futures.add(executePhysical(query).thenApplyAsync(queryResult -> {
                result.addPhysicalResult(query.getId(), queryResult);
                return queryResult;
            }, executorService));
        }
        return futures;
    }

    private List<CompletableFuture<QueryResult>> scheduleIndependentQueries(
            List<ExternalQuery> independentQueries,
            ExecutionResult result) {
        List<CompletableFuture<QueryResult>> futures = new ArrayList<>();
        for (ExternalQuery query : independentQueries) {
            futures.add(executeExternal(query).thenApplyAsync(queryResult -> {
                result.addExternalResult(queryResult);
                return queryResult;
            }, executorService));
        }
        return futures;
    }

    private List<CompletableFuture<QueryResult>> scheduleUnionQueries(
            List<UnionPart> unionParts,
            ExecutionResult result) {
        List<CompletableFuture<QueryResult>> futures = new ArrayList<>();
        for (UnionPart union : unionParts) {
            futures.add(executeUnion(union).thenApplyAsync(queryResult -> {
                result.addUnionResult(union.getId(), queryResult);
                return queryResult;
            }, executorService));
        }
        return futures;
    }

    private CompletableFuture<QueryResult> scheduleDependentQuery(ExternalQuery query, ExecutionResult result) {
        return executeExternal(query).thenApplyAsync(queryResult -> {
            QueryResult enriched = resultEnricher.enrichExternalResultWithSourceRows(
                    query,
                    normalizeExternalResult(query, queryResult),
                    result);
            result.addExternalResult(enriched);
            return enriched;
        }, executorService);
    }

    private CompletableFuture<QueryResult> scheduleBatchQuery(BatchRequest batch, ExecutionResult result) {
        return executeBatch(batch).thenApplyAsync(batchResult -> {
            result.addBatchResult(batch.getId(), batchResult);
            List<QueryResult> unbatchedResults = batchingStrategy.unbatch(batch, batchResult);
            for (int i = 0; i < unbatchedResults.size(); i++) {
                ExternalQuery originalQuery = i < batch.getOriginalQueries().size()
                        ? batch.getOriginalQueries().get(i)
                        : null;
                QueryResult currentResult = originalQuery != null
                        ? resultEnricher.enrichExternalResultWithSourceRows(
                                originalQuery,
                                normalizeExternalResult(originalQuery, unbatchedResults.get(i)),
                                result)
                        : unbatchedResults.get(i);
                result.addExternalResult(currentResult);
            }
            return batchResult;
        }, executorService);
    }

    private ExecutionResult markExecutionSuccess(ExecutionResult result) {
        result.setExecutionTimeMs(System.currentTimeMillis() - result.getStartTime());
        return result;
    }

    private CompletableFuture<QueryResult> executePhysical(PhysicalQuery query) {
        DataSourceAdapter adapter = getAdapter("tugraph");
        if (adapter == null) {
            adapter = getAdapter("TuGraph");
        }

        if (adapter == null) {
            log.error("Physical query failed [queryId={}]: No TuGraph adapter registered", query.getId());
            return CompletableFuture.failedFuture(
                    new GraphQueryException(ErrorCode.DATASOURCE_CONNECTION_ERROR,
                            "No TuGraph adapter registered"));
        }

        DataSourceAdapter finalAdapter = adapter;
        return runOnExecutor(() -> {
            List<Record> records;
            if (query.getParameters() != null && !query.getParameters().isEmpty()) {
                records = finalAdapter.executeTuGraphQuery(query.getCypher(), query.getParameters());
            } else {
                records = finalAdapter.executeTuGraphQuery(query.getCypher());
            }
            return convertRecordsToQueryResult(records, "tugraph");
        })
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionallyCompose(e -> handlePhysicalFailure(query, e));
    }

    private CompletableFuture<QueryResult> executeExternal(ExternalQuery query) {
        DataSourceAdapter adapter = getAdapter(query.getDataSource());

        if (adapter == null) {
            log.error("External query failed [queryId={}] [dataSource={}]: No adapter found",
                    query.getId(), query.getDataSource());
            return CompletableFuture.failedFuture(
                    new GraphQueryException(ErrorCode.DATASOURCE_CONNECTION_ERROR,
                            "No adapter found for data source: " + query.getDataSource()));
        }

        int maxRetries = getMaxRetries(query.getDataSource());
        return executeWithRetry(adapter, query, maxRetries);
    }

    private CompletableFuture<QueryResult> executeWithRetry(DataSourceAdapter adapter, ExternalQuery query, int maxRetries) {
        return executeWithRetryInternal(adapter, query, maxRetries, 0);
    }

    private CompletableFuture<QueryResult> executeWithRetryInternal(DataSourceAdapter adapter, ExternalQuery query, int maxRetries, int attempt) {
        return runOnExecutor(() -> executeAdapterQuery(adapter, query))
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .thenApply(result -> resultEnricher.applyExternalQueryMetadata(query, result))
                .exceptionally(e -> {
                    if (attempt < maxRetries) {
                        log.warn("External query attempt {}/{} failed for data source: {}, retrying...",
                                attempt + 1, maxRetries + 1, query.getDataSource());
                        return null;
                    }
                    log.error("External query failed after {} attempts [queryId={}] [dataSource={}]",
                            maxRetries + 1, query.getId(), query.getDataSource());
                    throw new GraphQueryException(ErrorCode.EXTERNAL_DATASOURCE_ERROR,
                            "External query failed after " + (maxRetries + 1) + " attempts", e);
                })
                .thenCompose(result -> {
                    if (result == null && attempt < maxRetries) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new GraphQueryException(ErrorCode.EXTERNAL_DATASOURCE_ERROR,
                                    "Retry interrupted", ie);
                        }
                        return executeWithRetryInternal(adapter, query, maxRetries, attempt + 1);
                    }
                    return CompletableFuture.completedFuture(result);
                });
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
            return convertRecordsToQueryResult(records, query.getDataSource());
        } else {
            DataSourceQueryParams params = convertToParams(query);
            List<Map<String, Object>> data = adapter.executeExternalQuery(params);
            return convertMapsToQueryResult(data, query.getDataSource(), query.getTargetLabel());
        }
    }

    private String extractCypher(ExternalQuery query) {
        Object cypher = query.getFilters().get("cypher");
        return cypher != null ? cypher.toString() : "";
    }

    private DataSourceQueryParams convertToParams(ExternalQuery query) {
        DataSourceQueryParams params = new DataSourceQueryParams();
        params.setDataSource(query.getDataSource());
        params.setOperator(query.getOperator());
        params.setTargetLabel(query.getTargetLabel());
        params.setInputIds(query.getInputIds());
        params.setInputIdField(query.getInputIdField());
        params.setOutputIdField(query.getOutputIdField());
        params.setOutputVariables(query.getOutputVariables());
        params.setOutputFields(query.getOutputFields());
        params.setFilters(query.getFilters());
        params.setParameters(query.getParameters());
        if (query.getInputMapping() != null) {
            params.setInputMapping(query.getInputMapping());
        }
        return params;
    }

    private QueryResult convertRecordsToQueryResult(List<Record> records, String dataSource) {
        QueryResult result = new QueryResult();
        result.setDataSource(dataSource);
        result.setData(records);

        if (records == null) {
            result.setEntities(new ArrayList<>());
            result.setRows(new ArrayList<>());
            return result;
        }

        List<GraphEntity> entities = new ArrayList<>();
        List<QueryResult.ResultRow> rows = new ArrayList<>();
        int rowIndex = 0;
        for (Record record : records) {
            QueryResult.ResultRow row = new QueryResult.ResultRow();
            row.setRowId(dataSource + "#row-" + rowIndex++);
            for (String key : record.keys()) {
                GraphEntity entity = convertValueToEntity(key, record.get(key));
                if (entity != null) {
                    entities.add(entity);
                    row.put(key, entity);
                }
            }
            if (!row.getEntitiesByVariable().isEmpty()) {
                rows.add(row);
            }
        }

        result.setEntities(entities);
        result.setRows(rows);
        return result;
    }

    private QueryResult convertMapsToQueryResult(List<Map<String, Object>> maps, String dataSource, String label) {
        QueryResult result = new QueryResult();
        result.setDataSource(dataSource);
        result.setEntities(convertMapsToEntities(maps, label));
        result.setData(maps);
        return result;
    }

    private List<GraphEntity> convertRecordsToEntities(List<Record> records) {
        if (records == null) return new ArrayList<>();

        return records.stream()
                .flatMap(record -> {
                    List<GraphEntity> entities = new ArrayList<>();
                    for (String key : record.keys()) {
                        GraphEntity entity = convertValueToEntity(key, record.get(key));
                        if (entity != null) {
                            entities.add(entity);
                        }
                    }
                    return entities.stream();
                })
                .collect(Collectors.toList());
    }

    private GraphEntity convertValueToEntity(String key, Value value) {
        if (value == null || value.isNull()) {
            return null;
        }

        try {
            if (value.asEntity() instanceof Node) {
                Node node = value.asNode();
                String label = !node.labels().iterator().hasNext() ? "Node" : node.labels().iterator().next();
                Map<String, Object> props = new HashMap<>();
                node.asMap().forEach(props::put);

                GraphEntity entity = GraphEntity.node(
                        resolvePhysicalEntityId(label, props, String.valueOf(node.id())),
                        label
                );
                entity.setProperties(props);
                entity.setVariableName(key);
                return entity;
            }
            if (value.asEntity() instanceof Relationship) {
                Relationship rel = value.asRelationship();
                GraphEntity entity = GraphEntity.edge(
                        String.valueOf(rel.id()),
                        rel.type(),
                        String.valueOf(rel.startNodeId()),
                        String.valueOf(rel.endNodeId())
                );
                Map<String, Object> props = new HashMap<>();
                rel.asMap().forEach(props::put);
                entity.setProperties(props);
                entity.setVariableName(key);
                return entity;
            }
        } catch (Exception e) {
            log.debug("Could not convert value for key {}: {}", key, e.getMessage());
        }

        return null;
    }

    private String resolvePhysicalEntityId(String label, Map<String, Object> props, String fallbackId) {
        if (props == null || props.isEmpty()) {
            return fallbackId;
        }

        String idProperty = null;
        if (metadataQueryService != null && label != null) {
            idProperty = metadataQueryService.getLabel(label)
                    .map(LabelMetadata::getIdProperty)
                    .orElse(null);
        }

        if (idProperty != null) {
            Object mappedId = props.get(idProperty);
            if (mappedId != null) {
                return String.valueOf(mappedId);
            }
        }

        Object resId = props.get("resId");
        if (resId != null) {
            return String.valueOf(resId);
        }

        Object explicitId = props.get("id");
        if (explicitId != null) {
            return String.valueOf(explicitId);
        }

        return fallbackId;
    }

    private List<GraphEntity> convertMapsToEntities(List<Map<String, Object>> maps, String label) {
        if (maps == null) return new ArrayList<>();

        return maps.stream()
                .map(map -> {
                    String id = map.containsKey("id") ? String.valueOf(map.get("id")) : UUID.randomUUID().toString();
                    String entityLabel = map.containsKey("label") ? String.valueOf(map.get("label")) : (label != null ? label : "Node");

                    GraphEntity entity = GraphEntity.node(id, entityLabel);
                    Map<String, Object> props = new HashMap<>(map);
                    props.remove("id");
                    props.remove("label");
                    entity.setProperties(props);
                    return entity;
                })
                .collect(Collectors.toList());
    }

    private int getMaxRetries(String dataSourceName) {
        if (metadataQueryService == null) {
            return DEFAULT_MAX_RETRIES;
        }

        return metadataQueryService.getDataSource(dataSourceName)
                .map(DataSourceMetadata::getMaxRetries)
                .filter(retries -> retries > 0)
                .orElse(DEFAULT_MAX_RETRIES);
    }

    private CompletableFuture<QueryResult> executeBatch(BatchRequest batch) {
        DataSourceAdapter adapter = getAdapter(batch.getDataSource());

        if (adapter == null) {
            log.error("Batch query failed [batchId={}] [dataSource={}]: No adapter found",
                    batch.getId(), batch.getDataSource());
            return CompletableFuture.failedFuture(
                    new GraphQueryException(ErrorCode.DATASOURCE_CONNECTION_ERROR,
                            "No adapter for data source: " + batch.getDataSource()));
        }

        DataSourceQueryParams params = new DataSourceQueryParams();
        params.setDataSource(batch.getDataSource());
        params.setOperator(batch.getOperator());
        params.setInputIds(batch.getInputIds());
        params.setInputIdField(batch.getInputIdField());
        params.setOutputFields(batch.getOutputFields());
        params.setOutputVariables(batch.getOutputVariables());
        params.setFilters(batch.getFilters());

        return runOnExecutor(() -> {
            List<Map<String, Object>> data = adapter.executeExternalQuery(params);
            QueryResult result = convertMapsToQueryResult(data, batch.getDataSource(), null);
            return result;
        })
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionallyCompose(e -> handleBatchFailure(batch, e));
    }

    private QueryResult normalizeExternalResult(ExternalQuery query, QueryResult result) {
        return resultEnricher.applyExternalQueryMetadata(query, result);
    }

    private CompletableFuture<QueryResult> executeUnion(UnionPart union) {
        List<CompletableFuture<ExecutionResult>> subFutures = new ArrayList<>();

        for (ExecutionPlan subPlan : union.getSubPlans()) {
            subFutures.add(execute(subPlan));
        }

        return CompletableFuture.allOf(subFutures.toArray(new CompletableFuture[0]))
                .handle((ignored, throwable) -> {
                    if (throwable != null) {
                        return CompletableFuture.<QueryResult>failedFuture(
                                wrapUnionFailure(union, unwrapAsyncFailure(throwable)));
                    }
                    return CompletableFuture.completedFuture(buildUnionResult(subFutures));
                })
                .thenCompose(future -> future);
    }

    private DataSourceAdapter getAdapter(String name) {
        if (name == null) return null;

        DataSourceAdapter adapter = adapters.get(name);
        if (adapter == null) {
            adapter = adapters.get(name.toLowerCase());
        }
        if (adapter == null) {
            adapter = adapters.values().stream()
                    .filter(a -> a.getDataSourceName().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
        }
        return adapter;
    }

    private CompletableFuture<QueryResult> runOnExecutor(Supplier<QueryResult> supplier) {
        return CompletableFuture.supplyAsync(supplier, executorService);
    }

    private CompletableFuture<QueryResult> handlePhysicalFailure(PhysicalQuery query, Throwable throwable) {
        Throwable cause = unwrapAsyncFailure(throwable);
        if (cause instanceof TimeoutException) {
            log.error("Physical query timeout after {}ms [queryId={}]: {}",
                    timeoutMs, query.getId(), query.getCypher());
            return CompletableFuture.failedFuture(new GraphQueryException(
                    ErrorCode.DATASOURCE_QUERY_ERROR,
                    "Physical query timeout after " + timeoutMs + "ms",
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
                    timeoutMs, batch.getId(), batch.getDataSource());
            return CompletableFuture.failedFuture(new GraphQueryException(
                    ErrorCode.BATCH_EXECUTION_ERROR,
                    "Batch query timeout after " + timeoutMs + "ms",
                    cause));
        }

        GraphQueryException exception = toBatchGraphQueryException(batch, cause);
        log.error("Batch query failed [batchId={}] [dataSource={}]: {}",
                batch.getId(), batch.getDataSource(), exception.getMessage(), cause);
        return CompletableFuture.failedFuture(exception);
    }

    private QueryResult buildUnionResult(List<CompletableFuture<ExecutionResult>> subFutures) {
        QueryResult combined = new QueryResult();
        List<ExecutionResult> subResults = new ArrayList<>();

        for (CompletableFuture<ExecutionResult> future : subFutures) {
            ExecutionResult subResult = future.join();
            subResults.add(subResult);
            for (List<QueryResult> results : subResult.getPhysicalResults().values()) {
                for (QueryResult result : results) {
                    combined.getEntities().addAll(result.getEntities());
                }
            }
            for (QueryResult result : subResult.getBatchResults().values()) {
                combined.getEntities().addAll(result.getEntities());
            }
            for (QueryResult result : subResult.getExternalResults()) {
                combined.getEntities().addAll(result.getEntities());
            }
        }

        combined.setData(subResults);
        return combined;
    }

    private GraphQueryException wrapUnionFailure(UnionPart union, Throwable cause) {
        if (cause instanceof GraphQueryException graphQueryException
                && graphQueryException.getErrorCode() == ErrorCode.UNION_EXECUTION_ERROR) {
            return graphQueryException;
        }

        if (!(cause instanceof GraphQueryException)) {
            log.error("Union sub-query failed [unionId={}]: {}",
                    union.getId(), cause != null ? cause.getMessage() : "Unknown union failure", cause);
        }
        return new GraphQueryException(
                ErrorCode.UNION_EXECUTION_ERROR,
                "Union sub-query failed [unionId=" + union.getId() + "]",
                cause);
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

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }
}
