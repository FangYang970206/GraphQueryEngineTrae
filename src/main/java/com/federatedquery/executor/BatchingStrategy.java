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
            String key = buildGroupKey(q);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(q);
        }
        
        List<BatchRequest> batches = new ArrayList<>();
        
        for (Map.Entry<String, List<ExternalQuery>> entry : grouped.entrySet()) {
            List<ExternalQuery> groupQueries = entry.getValue();
            ExternalQuery first = groupQueries.get(0);
            String dataSource = first.getDataSource();
            String operator = first.getOperator();
            
            List<String> allInputIds = new ArrayList<>();
            String inputIdField = null;
            List<String> outputFields = new ArrayList<>();
            List<String> outputVariables = new ArrayList<>();
            
            for (ExternalQuery q : groupQueries) {
                allInputIds.addAll(q.getInputIds());
                if (inputIdField == null && q.getInputIdField() != null) {
                    inputIdField = q.getInputIdField();
                }
                outputFields.addAll(q.getOutputFields());
                outputVariables.addAll(q.getOutputVariables());
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
                batch.setOutputFields(new ArrayList<>(outputFields));
                batch.setOutputVariables(new ArrayList<>(outputVariables));
                batch.setFilters(new HashMap<>(first.getFilters()));
                batch.setParameters(new HashMap<>(first.getParameters()));
                batch.setSnapshotName(first.getSnapshotName());
                batch.setSnapshotTime(first.getSnapshotTime());
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
                    batch.setOutputFields(new ArrayList<>(outputFields));
                    batch.setOutputVariables(new ArrayList<>(outputVariables));
                    batch.setFilters(new HashMap<>(first.getFilters()));
                    batch.setParameters(new HashMap<>(first.getParameters()));
                    batch.setSnapshotName(first.getSnapshotName());
                    batch.setSnapshotTime(first.getSnapshotTime());
                    batch.setOriginalQueries(groupQueries);
                    
                    batches.add(batch);
                }
            }
        }
        
        return batches;
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
            results.add(qr);
            return results;
        }
        
        int entitiesPerQuery = (int) Math.ceil((double) totalEntities / queriesCount);
        
        int entityIndex = 0;
        for (ExternalQuery original : originalQueries) {
            QueryResult qr = new QueryResult();
            qr.setSuccess(true);
            
            List<GraphEntity> queryEntities = new ArrayList<>();
            int inputIdCount = original.getInputIds() != null ? original.getInputIds().size() : 0;
            int expectedCount = inputIdCount > 0 ? Math.min(inputIdCount, entitiesPerQuery) : entitiesPerQuery;
            
            for (int i = 0; i < expectedCount && entityIndex < totalEntities; i++) {
                queryEntities.add(allEntities.get(entityIndex));
                entityIndex++;
            }
            
            qr.setEntities(queryEntities);
            results.add(qr);
        }
        
        return results;
    }
    
    private String buildGroupKey(ExternalQuery q) {
        return String.join("|",
                Objects.toString(q.getDataSource(), ""),
                Objects.toString(q.getOperator(), ""),
                Objects.toString(q.getInputIdField(), ""),
                Objects.toString(q.getSnapshotName(), ""),
                Objects.toString(q.getSnapshotTime(), ""),
                normalizeMap(q.getFilters()),
                normalizeMap(q.getParameters()));
    }
    
    private String normalizeMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        TreeMap<String, Object> sorted = new TreeMap<>(source);
        return sorted.toString();
    }
    
    public int getMaxBatchSize() {
        return maxBatchSize;
    }
    
    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }
}
