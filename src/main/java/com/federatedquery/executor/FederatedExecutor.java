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
        
        List<CompletableFuture<QueryResult>> futures = new ArrayList<>();
        
        for (PhysicalQuery pq : plan.getPhysicalQueries()) {
            futures.add(executePhysical(pq).thenApplyAsync(r -> {
                result.addPhysicalResult(pq.getId(), r);
                return r;
            }, executorService));
        }
        
        List<ExternalQuery> externalQueries = plan.getExternalQueries();
        if (!externalQueries.isEmpty()) {
            List<ExternalQuery> directQueries = new ArrayList<>();
            List<ExternalQuery> batchedQueries = new ArrayList<>();
            
            for (ExternalQuery query : externalQueries) {
                if (query.getInputIds() == null || query.getInputIds().isEmpty()) {
                    directQueries.add(query);
                } else {
                    batchedQueries.add(query);
                }
            }
            
            for (ExternalQuery query : directQueries) {
                futures.add(executeExternal(query).thenApplyAsync(r -> {
                    result.addExternalResult(r);
                    return r;
                }, executorService));
            }
            
            List<BatchRequest> batches = batchingStrategy.batch(batchedQueries);
            for (BatchRequest batch : batches) {
                futures.add(executeBatch(batch).thenApplyAsync(r -> {
                    result.addBatchResult(batch.getId(), r);
                    List<QueryResult> unbatched = batchingStrategy.unbatch(batch, r);
                    for (QueryResult qr : unbatched) {
                        result.addExternalResult(qr);
                    }
                    return r;
                }, executorService));
            }
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
