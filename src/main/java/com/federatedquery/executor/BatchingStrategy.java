package com.federatedquery.executor;

import com.federatedquery.adapter.GraphEntity;
import com.federatedquery.plan.ExternalQuery;

import java.util.*;

public class BatchingStrategy {
    private int maxBatchSize = 1000;
    
    public List<BatchRequest> batch(List<ExternalQuery> queries) {
        Map<String, List<ExternalQuery>> grouped = new HashMap<>();
        
        for (ExternalQuery q : queries) {
            String key = q.getDataSource() + ":" + q.getOperator();
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
            List<String> outputFields = new ArrayList<>();
            
            for (ExternalQuery q : groupQueries) {
                allInputIds.addAll(q.getInputIds());
                if (inputIdField == null && q.getInputIdField() != null) {
                    inputIdField = q.getInputIdField();
                }
                outputFields.addAll(q.getOutputFields());
            }
            
            outputFields = new ArrayList<>(new LinkedHashSet<>(outputFields));
            
            int totalSize = allInputIds.size();
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
                batch.setOriginalQueries(groupQueries);
                
                batches.add(batch);
            }
        }
        
        return batches;
    }
    
    public com.federatedquery.adapter.QueryResult unbatch(BatchRequest batch, com.federatedquery.adapter.QueryResult batchResult) {
        return batchResult;
    }
    
    public int getMaxBatchSize() {
        return maxBatchSize;
    }
    
    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }
}
