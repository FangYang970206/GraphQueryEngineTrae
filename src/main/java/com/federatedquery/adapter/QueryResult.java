package com.federatedquery.adapter;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class QueryResult {
    private boolean success = true;
    private Object data;
    private List<GraphEntity> entities = new ArrayList<>();
    private String error;
    private Map<String, String> warnings = new HashMap<>();
    private long executionTimeMs;
    private String dataSource;
    
    public void addEntity(GraphEntity entity) {
        this.entities.add(entity);
    }
    
    public void setError(String error) {
        this.error = error;
        this.success = false;
    }
    
    public void addWarning(String key, String message) {
        this.warnings.put(key, message);
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
