package com.fangyang.federatedquery;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class QueryResult {
    private Object data;
    private List<GraphEntity> entities = new ArrayList<>();
    private List<ResultRow> rows = new ArrayList<>();
    private long executionTimeMs;
    private String dataSource;
    
    public void addEntity(GraphEntity entity) {
        this.entities.add(entity);
    }

    public void addRow(ResultRow row) {
        this.rows.add(row);
    }

    @Data
    public static class ResultRow {
        private String rowId;
        private java.util.Map<String, GraphEntity> entitiesByVariable = new java.util.HashMap<>();

        public void put(String variable, GraphEntity entity) {
            if (variable != null && entity != null) {
                entitiesByVariable.put(variable, entity);
            }
        }
    }
}
