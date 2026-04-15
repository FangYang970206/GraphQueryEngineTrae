package com.federatedquery.executor;

import com.federatedquery.adapter.QueryResult;
import lombok.Data;

import java.util.*;

@Data
public class ExecutionResult {
    private String planId;
    private long startTime = System.currentTimeMillis();
    private long executionTimeMs;
    private Map<String, List<QueryResult>> physicalResults = new HashMap<>();
    private Map<String, QueryResult> batchResults = new HashMap<>();
    private Map<String, QueryResult> unionResults = new HashMap<>();
    private List<QueryResult> externalResults = new ArrayList<>();

    public void addPhysicalResult(String queryId, QueryResult result) {
        this.physicalResults.computeIfAbsent(queryId, k -> new ArrayList<>()).add(result);
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
