package com.fangyang.federatedquery.executor;

import com.fangyang.federatedquery.model.QueryResult;
import lombok.Data;

import java.util.*;
import java.util.concurrent.*;

@Data
public class ExecutionResult {
    private String planId;
    private long startTime = System.currentTimeMillis();
    private long executionTimeMs;
    private Map<String, List<QueryResult>> physicalResults = new ConcurrentHashMap<>();
    private Map<String, QueryResult> batchResults = new ConcurrentHashMap<>();
    private Map<String, QueryResult> unionResults = new ConcurrentHashMap<>();
    private List<QueryResult> externalResults = Collections.synchronizedList(new ArrayList<>());

    public void addPhysicalResult(String queryId, QueryResult result) {
        this.physicalResults.computeIfAbsent(queryId, k -> Collections.synchronizedList(new ArrayList<>())).add(result);
    }

    public void addBatchResult(String batchId, QueryResult result) {
        this.batchResults.put(batchId, result);
    }

    public void addUnionResult(String unionId, QueryResult result) {
        this.unionResults.put(unionId, result);
    }

    public void addExternalResult(QueryResult result) {
        this.externalResults.add(result);
    }

    public List<QueryResult> getAllResults() {
        List<QueryResult> all = new ArrayList<>();
        physicalResults.values().forEach(all::addAll);
        all.addAll(batchResults.values());
        all.addAll(unionResults.values());
        return all;
    }
}
