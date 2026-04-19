package com.fangyang.federatedquery.executor;

import com.fangyang.datasource.QueryFilter;
import com.fangyang.federatedquery.model.GraphEntity;
import com.fangyang.federatedquery.model.QueryResult;
import com.fangyang.federatedquery.plan.ExternalQuery;

import java.util.*;

public class BatchingStrategy {
    private int maxBatchSize = 1000;
    
    public List<BatchRequest> batch(List<ExternalQuery> queries) {
        Map<String, List<ExternalQuery>> grouped = new HashMap<>();
        
        for (ExternalQuery q : queries) {
            String key = q.getDataSource() + ":" + q.getOperator() + ":"
                    + buildFilterKey(q.getFilters()) + ":" + buildFilterConditionKey(q.getFilterConditions());
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
            List<QueryFilter> filterConditions = new ArrayList<>();
            
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
                if (filterConditions.isEmpty() && q.getFilterConditions() != null && !q.getFilterConditions().isEmpty()) {
                    filterConditions.addAll(q.getFilterConditions());
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
                batch.setFilterConditions(new ArrayList<>(filterConditions));
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
                    batch.setFilterConditions(new ArrayList<>(filterConditions));
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

    private String buildFilterConditionKey(List<QueryFilter> filterConditions) {
        if (filterConditions == null || filterConditions.isEmpty()) {
            return "";
        }
        List<String> segments = new ArrayList<>();
        for (QueryFilter condition : filterConditions) {
            segments.add(condition.getKey() + ":" + condition.getOperator() + ":" + String.valueOf(condition.getValue()));
        }
        Collections.sort(segments);
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
                qr.setEntities(new ArrayList<>());
                results.add(qr);
            }
            return results;
        }
        
        int totalEntities = allEntities.size();
        int queriesCount = originalQueries.size();
        
        if (queriesCount == 1) {
            QueryResult qr = new QueryResult();
            qr.setEntities(new ArrayList<>(allEntities));
            qr.setRows(new ArrayList<>(batchResult.getRows()));
            results.add(qr);
            return results;
        }

        String outputIdField = batch.getOutputIdField();
        if (outputIdField != null && !outputIdField.isEmpty()) {
            Map<String, List<GraphEntity>> entitiesByOutputId = bucketEntitiesByOutputId(allEntities, outputIdField);
            Map<String, List<QueryResult.ResultRow>> rowsByOutputId = bucketRowsByOutputId(batchResult.getRows(), outputIdField);
            for (ExternalQuery original : originalQueries) {
                QueryResult qr = new QueryResult();
                qr.setDataSource(batchResult.getDataSource());

                LinkedHashSet<GraphEntity> queryEntities = new LinkedHashSet<>();
                LinkedHashSet<QueryResult.ResultRow> rows = new LinkedHashSet<>();
                for (String inputId : new LinkedHashSet<>(original.getInputIds())) {
                    queryEntities.addAll(entitiesByOutputId.getOrDefault(inputId, Collections.emptyList()));
                    rows.addAll(rowsByOutputId.getOrDefault(inputId, Collections.emptyList()));
                }
                qr.setEntities(new ArrayList<>(queryEntities));
                qr.setRows(new ArrayList<>(rows));
                results.add(qr);
            }
            return results;
        }
        
        int entitiesPerQuery = (int) Math.ceil((double) totalEntities / queriesCount);
        
        int entityIndex = 0;
        for (ExternalQuery original : originalQueries) {
            QueryResult qr = new QueryResult();
            
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

    private Map<String, List<GraphEntity>> bucketEntitiesByOutputId(List<GraphEntity> entities, String outputIdField) {
        Map<String, List<GraphEntity>> buckets = new LinkedHashMap<>();
        if (entities == null || entities.isEmpty()) {
            return buckets;
        }
        for (GraphEntity entity : entities) {
            if (entity == null || entity.getProperties() == null) {
                continue;
            }
            Object outputId = entity.getProperties().get(outputIdField);
            if (outputId != null) {
                buckets.computeIfAbsent(String.valueOf(outputId), ignored -> new ArrayList<>()).add(entity);
            }
        }
        return buckets;
    }

    private Map<String, List<QueryResult.ResultRow>> bucketRowsByOutputId(List<QueryResult.ResultRow> rows, String outputIdField) {
        Map<String, List<QueryResult.ResultRow>> buckets = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) {
            return buckets;
        }
        for (QueryResult.ResultRow row : rows) {
            if (row == null || row.getEntitiesByVariable() == null) {
                continue;
            }
            LinkedHashSet<String> matchedOutputIds = new LinkedHashSet<>();
            for (GraphEntity entity : row.getEntitiesByVariable().values()) {
                if (entity == null || entity.getProperties() == null) {
                    continue;
                }
                Object outputId = entity.getProperties().get(outputIdField);
                if (outputId != null) {
                    matchedOutputIds.add(String.valueOf(outputId));
                }
            }
            for (String matchedOutputId : matchedOutputIds) {
                buckets.computeIfAbsent(matchedOutputId, ignored -> new ArrayList<>()).add(row);
            }
        }
        return buckets;
    }
    
    public int getMaxBatchSize() {
        return maxBatchSize;
    }
    
    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }
}
