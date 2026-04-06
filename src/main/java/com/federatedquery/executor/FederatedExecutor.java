package com.federatedquery.executor;

import com.federatedquery.adapter.*;
import com.federatedquery.metadata.MetadataRegistry;
import com.federatedquery.plan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Service
public class FederatedExecutor {
    private static final Logger log = LoggerFactory.getLogger(FederatedExecutor.class);
    
    private static final long DEFAULT_TIMEOUT_MS = 30000;
    
    private final MetadataRegistry registry;
    private final Map<String, DataSourceAdapter> adapters;
    private final ExecutorService executorService;
    private final BatchingStrategy batchingStrategy;
    private long timeoutMs = DEFAULT_TIMEOUT_MS;
    
    public FederatedExecutor(MetadataRegistry registry) {
        this.registry = registry;
        this.adapters = new ConcurrentHashMap<>();
        this.executorService = Executors.newFixedThreadPool(10);
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
            futures.add(executePhysical(pq).thenApply(r -> {
                result.addPhysicalResult(pq.getId(), r);
                return r;
            }));
        }
        
        List<ExternalQuery> externalQueries = plan.getExternalQueries();
        if (!externalQueries.isEmpty()) {
            List<BatchRequest> batches = batchingStrategy.batch(externalQueries);
            
            for (BatchRequest batch : batches) {
                futures.add(executeBatch(batch).thenApply(r -> {
                    result.addBatchResult(batch.getId(), r);
                    return r;
                }));
            }
        }
        
        for (UnionPart union : plan.getUnionParts()) {
            futures.add(executeUnion(union).thenApply(r -> {
                result.addUnionResult(union.getId(), r);
                return r;
            }));
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    result.setSuccess(true);
                    result.setExecutionTimeMs(System.currentTimeMillis() - result.getStartTime());
                    return result;
                });
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
        
        return adapter.execute(convertToExternalQuery(query))
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
        
        return adapter.execute(query)
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    log.error("External query timeout after {}ms for data source: {}", timeoutMs, query.getDataSource());
                    return QueryResult.error("External query timeout after " + timeoutMs + "ms: " + e.getMessage());
                });
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
        
        return adapter.execute(combinedQuery)
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(e -> {
                    log.error("Batch query timeout after {}ms for data source: {}", timeoutMs, batch.getDataSource());
                    return QueryResult.error("Batch query timeout after " + timeoutMs + "ms: " + e.getMessage());
                })
                .thenApply(result -> {
                    if (result.isSuccess()) {
                        return batchingStrategy.unbatch(batch, result);
                    }
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
