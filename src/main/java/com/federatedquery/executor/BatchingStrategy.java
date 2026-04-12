package com.federatedquery.executor;

import com.federatedquery.adapter.GraphEntity;
import com.federatedquery.adapter.QueryResult;
import com.federatedquery.plan.ExternalQuery;

import java.util.*;

public class BatchingStrategy {
    private int maxBatchSize = 1000;
    
    public List<BatchRequest> batch(List<ExternalQuery> queries) {
        Map<String, List<ExternalQuery>> grouped = new HashMap<>();
        
        for (ExternalQuery q : queries) {
            String key = q.getDataSource() + ":" + q.getOperator() + ":" + buildFilterKey(q.getFilters());
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(q);
        }
        
        List<BatchRequest> batches = new ArrayList<>();
        
        for (Map.Entry<String, List<ExternalQuery>> entry : grouped.entrySet()) {
            String[] parts = entry.getKey().split(":");
            String dataSource = parts[0];
            String operator = parts[1];
            
            List<ExternalQuery> groupQueries = entry.getValue();
            
            List<String> allInputIds = new ArrayList<>();
            String inputIdField = null;
            String outputIdField = null;
            List<String> outputFields = new ArrayList<>();
            List<String> outputVariables = new ArrayList<>();
            Map<String, Object> filters = new LinkedHashMap<>();
            
            for (ExternalQuery q : groupQueries) {
                allInputIds.addAll(q.getInputIds());
                if (inputIdField == null && q.getInputIdField() != null) {
                    inputIdField = q.getInputIdField();
                }
                if (outputIdField == null && q.getOutputIdField() != null) {
                    outputIdField = q.getOutputIdField();
                }
                outputFields.addAll(q.getOutputFields());
                outputVariables.addAll(q.getOutputVariables());
                if (filters.isEmpty() && q.getFilters() != null && !q.getFilters().isEmpty()) {
                    filters.putAll(q.getFilters());
                }
            }
            
            outputFields = new ArrayList<>(new LinkedHashSet<>(outputFields));
            outputVariables = new ArrayList<>(new LinkedHashSet<>(outputVariables));
            
            int totalSize = allInputIds.size();
            if (totalSize == 0) {
                BatchRequest batch = new BatchRequest();
                batch.setId(UUID.randomUUID().toString());
                batch.setDataSource(dataSource);
                batch.setOperator(operator);
                batch.setInputIds(new ArrayList<>());
                batch.setInputIdField(inputIdField);
                batch.setOutputIdField(outputIdField);
                batch.setOutputFields(new ArrayList<>(outputFields));
                batch.setOutputVariables(new ArrayList<>(outputVariables));
                batch.setFilters(new LinkedHashMap<>(filters));
                batch.setOriginalQueries(groupQueries);
                batches.add(batch);
            } else {
                for (int i = 0; i < totalSize; i += maxBatchSize) {
                    int end = Math.min(i + maxBatchSize, totalSize);
                    List<String> batchIds = allInputIds.subList(i, end);
                    
                    BatchRequest batch = new BatchRequest();
                    batch.setId(UUID.randomUUID().toString());
                    batch.setDataSource(dataSource);
                    batch.setOperator(operator);
                    batch.setInputIds(new ArrayList<>(batchIds));
                    batch.setInputIdField(inputIdField);
                    batch.setOutputIdField(outputIdField);
                    batch.setOutputFields(new ArrayList<>(outputFields));
                    batch.setOutputVariables(new ArrayList<>(outputVariables));
                    batch.setFilters(new LinkedHashMap<>(filters));
                    batch.setOriginalQueries(groupQueries);
                    
                    batches.add(batch);
                }
            }
        }
        
        return batches;
    }

    private String buildFilterKey(Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        List<String> segments = new ArrayList<>();
        new TreeMap<>(filters).forEach((key, value) -> segments.add(key + "=" + String.valueOf(value)));
        return String.join("|", segments);
    }
    
    public List<QueryResult> unbatch(BatchRequest batch, QueryResult batchResult) {
        List<QueryResult> results = new ArrayList<>();
        
        List<ExternalQuery> originalQueries = batch.getOriginalQueries();
        if (originalQueries == null || originalQueries.isEmpty()) {
            results.add(batchResult);
            return results;
        }
        
        List<GraphEntity> allEntities = batchResult.getEntities();
        if (allEntities == null || allEntities.isEmpty()) {
            for (ExternalQuery original : originalQueries) {
                QueryResult qr = new QueryResult();
                qr.setSuccess(true);
                qr.setEntities(new ArrayList<>());
                results.add(qr);
            }
            return results;
        }
        
        int totalEntities = allEntities.size();
        int queriesCount = originalQueries.size();
        
        if (queriesCount == 1) {
            QueryResult qr = new QueryResult();
            qr.setSuccess(true);
            qr.setEntities(new ArrayList<>(allEntities));
            qr.setRows(new ArrayList<>(batchResult.getRows()));
            results.add(qr);
            return results;
        }

        String outputIdField = batch.getOutputIdField();
        if (outputIdField != null && !outputIdField.isEmpty()) {
            for (ExternalQuery original : originalQueries) {
                QueryResult qr = new QueryResult();
                qr.setSuccess(true);
                qr.setDataSource(batchResult.getDataSource());
                
                Set<String> expectedIds = new LinkedHashSet<>(original.getInputIds());
                List<GraphEntity> queryEntities = new ArrayList<>();
                for (GraphEntity entity : allEntities) {
                    if (matchesOutputToInput(entity, outputIdField, expectedIds)) {
                        queryEntities.add(entity);
                    }
                }
                qr.setEntities(queryEntities);
                
                List<QueryResult.ResultRow> rows = new ArrayList<>();
                for (QueryResult.ResultRow row : batchResult.getRows()) {
                    if (matchesRowToInput(row, outputIdField, expectedIds)) {
                        rows.add(row);
                    }
                }
                qr.setRows(rows);
                results.add(qr);
            }
            return results;
        }
        
        int entitiesPerQuery = (int) Math.ceil((double) totalEntities / queriesCount);
        
        int entityIndex = 0;
        for (ExternalQuery original : originalQueries) {
            QueryResult qr = new QueryResult();
            qr.setSuccess(true);
            
            List<GraphEntity> queryEntities = new ArrayList<>();
            int inputIdCount = original.getInputIds() != null ? original.getInputIds().size() : 0;
            
            for (int i = 0; i < Math.min(inputIdCount, entitiesPerQuery) && entityIndex < totalEntities; i++) {
                queryEntities.add(allEntities.get(entityIndex));
                entityIndex++;
            }
            
            qr.setEntities(queryEntities);
            qr.setRows(new ArrayList<>());
            results.add(qr);
        }
        
        return results;
    }

    private boolean matchesOutputToInput(GraphEntity entity, String outputIdField, Set<String> expectedIds) {
        if (entity == null || expectedIds.isEmpty()) {
            return false;
        }
        Object outputId = entity.getProperties().get(outputIdField);
        return outputId != null && expectedIds.contains(String.valueOf(outputId));
    }

    private boolean matchesRowToInput(QueryResult.ResultRow row, String outputIdField, Set<String> expectedIds) {
        if (row == null || row.getEntitiesByVariable() == null || expectedIds.isEmpty()) {
            return false;
        }
        for (GraphEntity entity : row.getEntitiesByVariable().values()) {
            if (matchesOutputToInput(entity, outputIdField, expectedIds)) {
                return true;
            }
        }
        return false;
    }
    
    public int getMaxBatchSize() {
        return maxBatchSize;
    }
    
    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }
}
