package com.federatedquery.executor;

import com.federatedquery.adapter.*;
import com.federatedquery.exception.ErrorCode;
import com.federatedquery.exception.GraphQueryException;
import com.federatedquery.metadata.DataSourceMetadata;
import com.federatedquery.metadata.MetadataRegistry;
import com.federatedquery.plan.*;
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
    private MetadataRegistry registry;
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
    }

    public FederatedExecutor(MetadataRegistry registry) {
        this();
        this.registry = registry;
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
                    
                    Map<String, Map<String, Set<String>>> dataByVariable = extractIdsAndPropertiesFromPhysicalResults(result);
                    populateDependentQueryInputIds(dependentQueries, dataByVariable);
                    
                    List<CompletableFuture<QueryResult>> dependentFutures = new ArrayList<>();
                    
                    List<ExternalQuery> readyQueries = dependentQueries.stream()
                            .filter(ExternalQuery::isReadyToExecute)
                            .collect(Collectors.toList());
                    
                    List<ExternalQuery> notReadyQueries = dependentQueries.stream()
                            .filter(q -> !q.isReadyToExecute())
                            .collect(Collectors.toList());
                    
                    for (ExternalQuery query : notReadyQueries) {
                        log.debug("External query {} is not ready to execute - no input IDs available, returning empty result", query.getId());
                        result.addExternalResult(new QueryResult());
                    }
                    
                    List<ExternalQuery> directDependentQueries = readyQueries.stream()
                            .filter(q -> !q.hasInputIds() || q.getInputIds().size() <= 1)
                            .collect(Collectors.toList());
                    
                    List<ExternalQuery> batchDependentQueries = readyQueries.stream()
                            .filter(q -> q.hasInputIds() && q.getInputIds().size() > 1)
                            .collect(Collectors.toList());
                    
                    for (ExternalQuery query : directDependentQueries) {
                        dependentFutures.add(scheduleDependentQuery(query, result));
                    }
                    
                    if (!batchDependentQueries.isEmpty()) {
                        List<BatchRequest> batches = batchingStrategy.batch(batchDependentQueries);
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

    private void populateDependentQueryInputIds(
            List<ExternalQuery> dependentQueries,
            Map<String, Map<String, Set<String>>> dataByVariable) {
        for (ExternalQuery dependentQuery : dependentQueries) {
            String sourceVar = dependentQuery.getSourceVariableName();
            String inputIdField = dependentQuery.getInputIdField();

            if (sourceVar == null || !dataByVariable.containsKey(sourceVar)) {
                log.warn("No data found for source variable: {}", sourceVar);
                continue;
            }

            Map<String, Set<String>> propMap = dataByVariable.get(sourceVar);
            Set<String> ids = resolveInputIds(propMap, inputIdField);
            if (ids == null || ids.isEmpty()) {
                log.warn("No IDs found for source variable: {} with field: {}", sourceVar, inputIdField);
                continue;
            }

            dependentQuery.getInputIds().clear();
            dependentQuery.getInputIds().addAll(ids);
            log.debug("Populated {} IDs for external query {} from variable {} (field: {})",
                    ids.size(), dependentQuery.getId(), sourceVar, inputIdField != null ? inputIdField : "_id");
        }
    }

    private Set<String> resolveInputIds(Map<String, Set<String>> propMap, String inputIdField) {
        if (propMap == null) {
            return null;
        }
        if (inputIdField != null && propMap.containsKey(inputIdField)) {
            Set<String> ids = propMap.get(inputIdField);
            log.debug("Using property '{}' with {} values", inputIdField, ids.size());
            return ids;
        }
        if (propMap.containsKey("_id")) {
            Set<String> ids = propMap.get("_id");
            log.debug("Using _id with {} values", ids.size());
            return ids;
        }
        return null;
    }

    private CompletableFuture<QueryResult> scheduleDependentQuery(ExternalQuery query, ExecutionResult result) {
        return executeExternal(query).thenApplyAsync(queryResult -> {
            QueryResult enriched = enrichExternalResultWithSourceRows(
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
                        ? enrichExternalResultWithSourceRows(
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
    
    private Map<String, Map<String, Set<String>>> extractIdsAndPropertiesFromPhysicalResults(ExecutionResult result) {
        Map<String, Map<String, Set<String>>> dataByVariable = new HashMap<>();
        
        for (List<QueryResult> qrList : result.getPhysicalResults().values()) {
            for (QueryResult qr : qrList) {
                for (GraphEntity entity : qr.getEntities()) {
                    String varName = entity.getVariableName();
                    if (varName == null) {
                        varName = entity.getLabel();
                    }
                    if (varName != null) {
                        addEntityData(dataByVariable, varName, entity);
                    }
                    
                    String label = entity.getLabel();
                    if (label != null && !label.equals(varName)) {
                        addEntityData(dataByVariable, label, entity);
                    }
                }
            }
        }
        
        log.debug("Extracted data by variable: {}", dataByVariable.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().keySet())));
        
        return dataByVariable;
    }

    private void addEntityData(
            Map<String, Map<String, Set<String>>> dataByVariable,
            String key,
            GraphEntity entity) {
        Map<String, Set<String>> propMap = dataByVariable.computeIfAbsent(key, ignored -> new HashMap<>());
        if (entity.getId() != null) {
            propMap.computeIfAbsent("_id", ignored -> new HashSet<>()).add(entity.getId());
        }
        if (entity.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : entity.getProperties().entrySet()) {
            if (entry.getValue() != null) {
                propMap.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>())
                        .add(String.valueOf(entry.getValue()));
            }
        }
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
        return runOnExecutor(() -> finalAdapter.execute(convertToExternalQuery(query)))
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
        return runOnExecutor(() -> adapter.execute(query))
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .thenApply(result -> applyExternalQueryMetadata(query, result))
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
    
    private int getMaxRetries(String dataSourceName) {
        if (registry == null) {
            return DEFAULT_MAX_RETRIES;
        }
        
        return registry.getDataSource(dataSourceName)
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
        
        ExternalQuery combinedQuery = new ExternalQuery();
        combinedQuery.setId(batch.getId());
        combinedQuery.setDataSource(batch.getDataSource());
        combinedQuery.setOperator(batch.getOperator());
        combinedQuery.setInputIds(batch.getInputIds());
        combinedQuery.setInputIdField(batch.getInputIdField());
        combinedQuery.setOutputFields(batch.getOutputFields());
        combinedQuery.setOutputVariables(batch.getOutputVariables());
        combinedQuery.setFilters(batch.getFilters());
        combinedQuery.setBatched(true);
        
        return runOnExecutor(() -> adapter.execute(combinedQuery))
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionallyCompose(e -> handleBatchFailure(batch, e))
                .thenApply(result -> result);
    }

    private QueryResult normalizeExternalResult(ExternalQuery query, QueryResult result) {
        return applyExternalQueryMetadata(query, result);
    }

    private QueryResult applyExternalQueryMetadata(ExternalQuery query, QueryResult result) {
        if (query == null || result == null || result.getEntities() == null) {
            return result;
        }
        for (GraphEntity entity : result.getEntities()) {
            if (query.getEdgeType() != null && entity.getSourceEdgeType() == null) {
                entity.setSourceEdgeType(query.getEdgeType());
            }
            if (query.getTargetLabel() != null && entity.getLabel() == null) {
                entity.setLabel(query.getTargetLabel());
            }
            if (entity.getVariableName() == null && !query.getOutputVariables().isEmpty()) {
                entity.setVariableName(query.getOutputVariables().get(0));
            }
        }
        return result;
    }

    private QueryResult enrichExternalResultWithSourceRows(
            ExternalQuery query,
            QueryResult queryResult,
            ExecutionResult executionResult) {
        if (query == null || queryResult == null) {
            return queryResult;
        }
        if (query.getSourceVariableName() == null || query.getSourceVariableName().isEmpty()) {
            return queryResult;
        }
        if (query.getInputIdField() == null || query.getInputIdField().isEmpty()
                || query.getOutputIdField() == null || query.getOutputIdField().isEmpty()) {
            return queryResult;
        }

        Map<String, List<QueryResult.ResultRow>> sourceRowsByJoinValue =
                indexSourceRows(executionResult, query.getSourceVariableName(), query.getInputIdField());
        if (sourceRowsByJoinValue.isEmpty()) {
            return queryResult;
        }

        List<QueryResult.ResultRow> baseRows = !queryResult.getRows().isEmpty()
                ? queryResult.getRows()
                : createRowsFromEntities(query, queryResult.getEntities());

        List<QueryResult.ResultRow> mergedRows = new ArrayList<>();
        int rowIndex = 0;
        for (QueryResult.ResultRow baseRow : baseRows) {
            String joinValue = resolveJoinValue(baseRow, query.getOutputIdField());
            if (joinValue == null) {
                continue;
            }

            List<QueryResult.ResultRow> sourceRows = sourceRowsByJoinValue.get(joinValue);
            if (sourceRows == null || sourceRows.isEmpty()) {
                continue;
            }

            for (QueryResult.ResultRow sourceRow : sourceRows) {
                QueryResult.ResultRow mergedRow = new QueryResult.ResultRow();
                mergedRow.setRowId((query.getId() != null ? query.getId() : "external") + "#" + rowIndex++);
                mergedRow.getEntitiesByVariable().putAll(sourceRow.getEntitiesByVariable());
                mergedRow.getEntitiesByVariable().putAll(baseRow.getEntitiesByVariable());
                mergedRows.add(mergedRow);
            }
        }

        if (!mergedRows.isEmpty()) {
            queryResult.setRows(mergedRows);
        }
        return queryResult;
    }

    private Map<String, List<QueryResult.ResultRow>> indexSourceRows(
            ExecutionResult executionResult,
            String sourceVariableName,
            String inputIdField) {
        Map<String, List<QueryResult.ResultRow>> sourceRowsByJoinValue = new LinkedHashMap<>();

        for (List<QueryResult> qrList : executionResult.getPhysicalResults().values()) {
            for (QueryResult qr : qrList) {
                for (QueryResult.ResultRow row : qr.getRows()) {
                    GraphEntity sourceEntity = row.getEntitiesByVariable().get(sourceVariableName);
                    if (sourceEntity == null) {
                        continue;
                    }
                    Object joinValue = "_id".equals(inputIdField)
                            ? sourceEntity.getId()
                            : sourceEntity.getProperties().get(inputIdField);
                    if (joinValue != null) {
                        sourceRowsByJoinValue
                                .computeIfAbsent(String.valueOf(joinValue), key -> new ArrayList<>())
                                .add(row);
                    }
                }
            }
        }

        return sourceRowsByJoinValue;
    }

    private List<QueryResult.ResultRow> createRowsFromEntities(ExternalQuery query, List<GraphEntity> entities) {
        List<QueryResult.ResultRow> rows = new ArrayList<>();
        int rowIndex = 0;
        for (GraphEntity entity : entities) {
            QueryResult.ResultRow row = new QueryResult.ResultRow();
            row.setRowId((query.getId() != null ? query.getId() : "external") + "#entity-" + rowIndex++);
            if (entity.getVariableName() != null && !entity.getVariableName().isEmpty()) {
                row.put(entity.getVariableName(), entity);
                rows.add(row);
            }
        }
        return rows;
    }

    private String resolveJoinValue(QueryResult.ResultRow row, String outputIdField) {
        for (GraphEntity entity : row.getEntitiesByVariable().values()) {
            Object joinValue = "_id".equals(outputIdField)
                    ? entity.getId()
                    : entity.getProperties().get(outputIdField);
            if (joinValue != null) {
                return String.valueOf(joinValue);
            }
        }
        return null;
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
    
    private ExternalQuery convertToExternalQuery(PhysicalQuery pq) {
        ExternalQuery eq = new ExternalQuery();
        eq.setId(pq.getId());
        eq.setDataSource("tugraph");
        eq.setOperator("cypher");
        eq.getFilters().put("cypher", pq.getCypher());
        if (pq.getParameters() != null && !pq.getParameters().isEmpty()) {
            eq.getParameters().putAll(pq.getParameters());
        }
        return eq;
    }
    
    private CompletableFuture<QueryResult> runOnExecutor(Supplier<CompletableFuture<QueryResult>> supplier) {
        return CompletableFuture.supplyAsync(supplier, executorService).thenCompose(f -> f);
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
