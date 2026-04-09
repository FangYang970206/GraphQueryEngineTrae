package com.federatedquery.executor;

import com.federatedquery.adapter.*;
import com.federatedquery.metadata.DataSourceMetadata;
import com.federatedquery.metadata.MetadataRegistry;
import com.federatedquery.plan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    
    private final MetadataRegistry registry;
    private final Map<String, DataSourceAdapter> adapters;
    private final ExecutorService executorService;
    private final BatchingStrategy batchingStrategy;
    private long timeoutMs = DEFAULT_TIMEOUT_MS;
    
    public FederatedExecutor(MetadataRegistry registry) {
        this.registry = registry;
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
        
        for (PhysicalQuery pq : physicalQueries) {
            futures.add(executePhysical(pq).thenApplyAsync(r -> {
                result.addPhysicalResult(pq.getId(), r);
                return r;
            }, executorService));
        }
        
        for (ExternalQuery query : independentQueries) {
            futures.add(executeExternal(query).thenApplyAsync(r -> {
                result.addExternalResult(r);
                return r;
            }, executorService));
        }
        
        for (UnionPart union : plan.getUnionParts()) {
            futures.add(executeUnion(union).thenApplyAsync(r -> {
                result.addUnionResult(union.getId(), r);
                return r;
            }, executorService));
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApplyAsync(v -> {
                    result.setSuccess(true);
                    result.setExecutionTimeMs(System.currentTimeMillis() - result.getStartTime());
                    return result;
                }, executorService);
    }
    
    private CompletableFuture<ExecutionResult> executeWithDependencyAwareness(
            ExecutionPlan plan,
            ExecutionResult result,
            List<PhysicalQuery> physicalQueries,
            List<ExternalQuery> independentQueries,
            List<ExternalQuery> dependentQueries) {
        
        List<CompletableFuture<QueryResult>> physicalFutures = new ArrayList<>();
        
        for (PhysicalQuery pq : physicalQueries) {
            physicalFutures.add(executePhysical(pq).thenApplyAsync(r -> {
                result.addPhysicalResult(pq.getId(), r);
                return r;
            }, executorService));
        }
        
        List<CompletableFuture<QueryResult>> independentFutures = new ArrayList<>();
        for (ExternalQuery query : independentQueries) {
            independentFutures.add(executeExternal(query).thenApplyAsync(r -> {
                result.addExternalResult(r);
                return r;
            }, executorService));
        }
        
        return CompletableFuture.allOf(physicalFutures.toArray(new CompletableFuture[0]))
                .thenComposeAsync(physicalVoid -> {
                    log.debug("Physical queries completed, extracting IDs for dependent queries");
                    
                    Map<String, Set<String>> idsByVariable = extractIdsFromPhysicalResults(result);
                    
                    for (ExternalQuery depQuery : dependentQueries) {
                        String sourceVar = depQuery.getSourceVariableName();
                        if (sourceVar != null && idsByVariable.containsKey(sourceVar)) {
                            Set<String> ids = idsByVariable.get(sourceVar);
                            depQuery.getInputIds().clear();
                            depQuery.getInputIds().addAll(ids);
                            log.debug("Populated {} IDs for external query {} from variable {}", 
                                    ids.size(), depQuery.getId(), sourceVar);
                        } else {
                            log.warn("No IDs found for source variable: {}", sourceVar);
                        }
                    }
                    
                    List<CompletableFuture<QueryResult>> dependentFutures = new ArrayList<>();
                    
                    List<ExternalQuery> readyQueries = dependentQueries.stream()
                            .filter(ExternalQuery::isReadyToExecute)
                            .collect(Collectors.toList());
                    
                    List<ExternalQuery> notReadyQueries = dependentQueries.stream()
                            .filter(q -> !q.isReadyToExecute())
                            .collect(Collectors.toList());
                    
                    for (ExternalQuery query : notReadyQueries) {
                        log.warn("External query {} is not ready to execute - no input IDs available", query.getId());
                        result.addExternalResult(QueryResult.partial(new ArrayList<>(), 
                                "No input IDs available from physical query"));
                    }
                    
                    List<ExternalQuery> directDependentQueries = readyQueries.stream()
                            .filter(q -> !q.hasInputIds() || q.getInputIds().size() <= 1)
                            .collect(Collectors.toList());
                    
                    List<ExternalQuery> batchDependentQueries = readyQueries.stream()
                            .filter(q -> q.hasInputIds() && q.getInputIds().size() > 1)
                            .collect(Collectors.toList());
                    
                    for (ExternalQuery query : directDependentQueries) {
                        dependentFutures.add(executeExternal(query).thenApplyAsync(r -> {
                            result.addExternalResult(r);
                            return r;
                        }, executorService));
                    }
                    
                    if (!batchDependentQueries.isEmpty()) {
                        List<BatchRequest> batches = batchingStrategy.batch(batchDependentQueries);
                        for (BatchRequest batch : batches) {
                            dependentFutures.add(executeBatch(batch).thenApplyAsync(r -> {
                                result.addBatchResult(batch.getId(), r);
                                List<QueryResult> unbatched = batchingStrategy.unbatch(batch, r);
                                for (QueryResult qr : unbatched) {
                                    result.addExternalResult(qr);
                                }
                                return r;
                            }, executorService));
                        }
                    }
                    
                    CompletableFuture<Void> dependentVoid = CompletableFuture.allOf(
                            dependentFutures.toArray(new CompletableFuture[0]));
                    
                    CompletableFuture<Void> independentVoid = CompletableFuture.allOf(
                            independentFutures.toArray(new CompletableFuture[0]));
                    
                    return CompletableFuture.allOf(dependentVoid, independentVoid);
                }, executorService)
                .thenComposeAsync(v -> {
                    List<CompletableFuture<QueryResult>> unionFutures = new ArrayList<>();
                    for (UnionPart union : plan.getUnionParts()) {
                        unionFutures.add(executeUnion(union).thenApplyAsync(r -> {
                            result.addUnionResult(union.getId(), r);
                            return r;
                        }, executorService));
                    }
                    return CompletableFuture.allOf(unionFutures.toArray(new CompletableFuture[0]));
                }, executorService)
                .thenApplyAsync(v -> {
                    result.setSuccess(true);
                    result.setExecutionTimeMs(System.currentTimeMillis() - result.getStartTime());
                    return result;
                }, executorService);
    }
    
    private Map<String, Set<String>> extractIdsFromPhysicalResults(ExecutionResult result) {
        Map<String, Set<String>> idsByVariable = new HashMap<>();
        
        for (List<QueryResult> qrList : result.getPhysicalResults().values()) {
            for (QueryResult qr : qrList) {
                for (GraphEntity entity : qr.getEntities()) {
                    String varName = entity.getVariableName();
                    if (varName == null) {
                        varName = entity.getLabel();
                    }
                    if (varName != null) {
                        idsByVariable.computeIfAbsent(varName, k -> new HashSet<>())
                                .add(entity.getId());
                    }
                    
                    String label = entity.getLabel();
                    if (label != null) {
                        idsByVariable.computeIfAbsent(label, k -> new HashSet<>())
                                .add(entity.getId());
                    }
                }
            }
        }
        
        log.debug("Extracted IDs by variable: {}", idsByVariable.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size())));
        
        return idsByVariable;
    }
    
    private CompletableFuture<QueryResult> executePhysical(PhysicalQuery query) {
        DataSourceAdapter adapter = getAdapter("tugraph");
        if (adapter == null) {
            adapter = getAdapter("TuGraph");
        }
        
        if (adapter == null) {
            return CompletableFuture.completedFuture(
                    QueryResult.error("No TuGraph adapter registered"));
        }
        
        DataSourceAdapter finalAdapter = adapter;
        return runOnExecutor(() -> finalAdapter.execute(convertToExternalQuery(query)))
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    log.error("Physical query timeout after {}ms: {}", timeoutMs, query.getCypher());
                    return QueryResult.error("Query timeout after " + timeoutMs + "ms: " + e.getMessage());
                });
    }
    
    private CompletableFuture<QueryResult> executeExternal(ExternalQuery query) {
        DataSourceAdapter adapter = getAdapter(query.getDataSource());
        
        if (adapter == null) {
            log.warn("No adapter found for data source: {}", query.getDataSource());
            return CompletableFuture.completedFuture(
                    QueryResult.partial(new ArrayList<>(), 
                            "External source " + query.getDataSource() + " unavailable"));
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
                .exceptionally(e -> {
                    if (attempt < maxRetries) {
                        log.warn("External query attempt {}/{} failed for data source: {}, retrying...", 
                                attempt + 1, maxRetries + 1, query.getDataSource());
                        return null;
                    }
                    log.error("External query failed after {} attempts for data source: {}", 
                            maxRetries + 1, query.getDataSource());
                    return QueryResult.error("External query failed after " + (maxRetries + 1) + 
                            " attempts: " + e.getMessage());
                })
                .thenCompose(result -> {
                    if (result == null && attempt < maxRetries) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return CompletableFuture.completedFuture(QueryResult.error("Retry interrupted"));
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
            return CompletableFuture.completedFuture(
                    QueryResult.error("No adapter for data source: " + batch.getDataSource()));
        }
        
        ExternalQuery combinedQuery = new ExternalQuery();
        combinedQuery.setId(batch.getId());
        combinedQuery.setDataSource(batch.getDataSource());
        combinedQuery.setOperator(batch.getOperator());
        combinedQuery.setInputIds(batch.getInputIds());
        combinedQuery.setInputIdField(batch.getInputIdField());
        combinedQuery.setOutputFields(batch.getOutputFields());
        combinedQuery.setOutputVariables(batch.getOutputVariables());
        combinedQuery.setBatched(true);
        
        return runOnExecutor(() -> adapter.execute(combinedQuery))
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    log.error("Batch query timeout after {}ms for data source: {}", timeoutMs, batch.getDataSource());
                    return QueryResult.error("Batch query timeout after " + timeoutMs + "ms: " + e.getMessage());
                })
                .thenApply(result -> {
                    return result;
                });
    }
    
    private CompletableFuture<QueryResult> executeUnion(UnionPart union) {
        List<CompletableFuture<ExecutionResult>> subFutures = new ArrayList<>();
        
        for (ExecutionPlan subPlan : union.getSubPlans()) {
            subFutures.add(execute(subPlan));
        }
        
        return CompletableFuture.allOf(subFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    QueryResult combined = new QueryResult();
                    combined.setSuccess(true);
                    
                    for (CompletableFuture<ExecutionResult> future : subFutures) {
                        try {
                            ExecutionResult subResult = future.get();
                            for (List<QueryResult> results : subResult.getPhysicalResults().values()) {
                                for (QueryResult r : results) {
                                    combined.getEntities().addAll(r.getEntities());
                                }
                            }
                            for (QueryResult r : subResult.getBatchResults().values()) {
                                combined.getEntities().addAll(r.getEntities());
                            }
                            for (QueryResult r : subResult.getExternalResults()) {
                                combined.getEntities().addAll(r.getEntities());
                            }
                        } catch (Exception e) {
                            log.error("Failed to get union sub-result", e);
                        }
                    }
                    
                    return combined;
                });
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
        return eq;
    }
    
    private CompletableFuture<QueryResult> runOnExecutor(Supplier<CompletableFuture<QueryResult>> supplier) {
        return CompletableFuture.supplyAsync(supplier, executorService).thenCompose(f -> f);
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
