package com.federatedquery.adapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QueryResult {
    private boolean success = true;
    private Object data;
    private List<GraphEntity> entities = new ArrayList<>();
    private String error;
    private Map<String, String> warnings = new HashMap<>();
    private long executionTimeMs;
    private String dataSource;
    
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public Object getData() {
        return data;
    }
    
    public void setData(Object data) {
        this.data = data;
    }
    
    public List<GraphEntity> getEntities() {
        return entities;
    }
    
    public void setEntities(List<GraphEntity> entities) {
        this.entities = entities;
    }
    
    public void addEntity(GraphEntity entity) {
        this.entities.add(entity);
    }
    
    public String getError() {
        return error;
    }
    
    public void setError(String error) {
        this.error = error;
        this.success = false;
    }
    
    public Map<String, String> getWarnings() {
        return warnings;
    }
    
    public void setWarnings(Map<String, String> warnings) {
        this.warnings = warnings;
    }
    
    public void addWarning(String key, String message) {
        this.warnings.put(key, message);
    }
    
    public long getExecutionTimeMs() {
        return executionTimeMs;
    }
    
    public void setExecutionTimeMs(long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }
    
    public String getDataSource() {
        return dataSource;
    }
    
    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }
    
    public static QueryResult success(List<GraphEntity> entities) {
        QueryResult result = new QueryResult();
        result.setEntities(entities);
        result.setSuccess(true);
        return result;
    }
    
    public static QueryResult error(String errorMessage) {
        QueryResult result = new QueryResult();
        result.setError(errorMessage);
        return result;
    }
    
    public static QueryResult partial(List<GraphEntity> entities, String warning) {
        QueryResult result = new QueryResult();
        result.setEntities(entities);
        result.setSuccess(true);
        result.addWarning("PARTIAL_RESULT", warning);
        return result;
    }
}
