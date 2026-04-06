package com.federatedquery.executor;

import com.federatedquery.adapter.QueryResult;

import java.util.*;

public class ExecutionResult {
    private String planId;
    private boolean success = false;
    private long startTime = System.currentTimeMillis();
    private long executionTimeMs;
    private Map<String, List<QueryResult>> physicalResults = new HashMap<>();
    private Map<String, QueryResult> batchResults = new HashMap<>();
    private Map<String, QueryResult> unionResults = new HashMap<>();
    private List<QueryResult> externalResults = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    
    public String getPlanId() {
        return planId;
    }
    
    public void setPlanId(String planId) {
        this.planId = planId;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }
    
    public long getExecutionTimeMs() {
        return executionTimeMs;
    }
    
    public void setExecutionTimeMs(long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }
    
    public Map<String, List<QueryResult>> getPhysicalResults() {
        return physicalResults;
    }
    
    public void setPhysicalResults(Map<String, List<QueryResult>> physicalResults) {
        this.physicalResults = physicalResults;
    }
    
    public void addPhysicalResult(String queryId, QueryResult result) {
        this.physicalResults.computeIfAbsent(queryId, k -> new ArrayList<>()).add(result);
    }
    
    public Map<String, QueryResult> getBatchResults() {
        return batchResults;
    }
    
    public void setBatchResults(Map<String, QueryResult> batchResults) {
        this.batchResults = batchResults;
    }
    
    public void addBatchResult(String batchId, QueryResult result) {
        this.batchResults.put(batchId, result);
    }
    
    public Map<String, QueryResult> getUnionResults() {
        return unionResults;
    }
    
    public void setUnionResults(Map<String, QueryResult> unionResults) {
        this.unionResults = unionResults;
    }
    
    public void addUnionResult(String unionId, QueryResult result) {
        this.unionResults.put(unionId, result);
    }
    
    public List<QueryResult> getExternalResults() {
        return externalResults;
    }
    
    public void setExternalResults(List<QueryResult> externalResults) {
        this.externalResults = externalResults;
    }
    
    public void addExternalResult(QueryResult result) {
        this.externalResults.add(result);
    }
    
    public List<String> getWarnings() {
        return warnings;
    }
    
    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }
    
    public void addWarning(String warning) {
        this.warnings.add(warning);
    }
    
    public List<QueryResult> getAllResults() {
        List<QueryResult> all = new ArrayList<>();
        physicalResults.values().forEach(all::addAll);
        all.addAll(batchResults.values());
        all.addAll(unionResults.values());
        return all;
    }
}
