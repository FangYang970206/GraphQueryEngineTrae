package com.federatedquery.aggregator;

import com.federatedquery.adapter.GraphEntity;
import com.federatedquery.adapter.QueryResult;
import com.federatedquery.executor.ExecutionResult;
import com.federatedquery.plan.ExecutionPlan;
import com.federatedquery.plan.GlobalContext;

import java.util.*;

public class ResultStitcher {
    
    public StitchedResult stitch(ExecutionResult executionResult, ExecutionPlan plan) {
        StitchedResult result = new StitchedResult();
        
        Map<String, GraphEntity> entityById = new HashMap<>();
        
        for (List<QueryResult> qrList : executionResult.getPhysicalResults().values()) {
            for (QueryResult qr : qrList) {
                for (GraphEntity entity : qr.getEntities()) {
                    entityById.put(entity.getId(), entity);
                }
            }
        }
        
        for (QueryResult qr : executionResult.getExternalResults()) {
            for (GraphEntity entity : qr.getEntities()) {
                entityById.put(entity.getId(), entity);
            }
        }
        
        result.setEntityById(entityById);
        
        List<Map<String, Object>> rows = buildRows(executionResult, plan);
        result.setRows(rows);
        
        GlobalContext context = plan.getGlobalContext();
        if (context != null) {
            result.setPendingFilters(context.getPendingFilters());
            result.setGlobalOrder(context.getGlobalOrder());
            result.setGlobalLimit(context.getGlobalLimit());
        }
        
        return result;
    }
    
    private List<Map<String, Object>> buildRows(ExecutionResult execResult, ExecutionPlan plan) {
        List<Map<String, Object>> rows = new ArrayList<>();
        
        List<QueryResult> allResults = new ArrayList<>();
        for (List<QueryResult> qrList : execResult.getPhysicalResults().values()) {
            allResults.addAll(qrList);
        }
        allResults.addAll(execResult.getExternalResults());
        
        if (allResults.isEmpty()) {
            return rows;
        }
        
        QueryResult first = allResults.get(0);
        for (GraphEntity entity : first.getEntities()) {
            Map<String, Object> row = new HashMap<>();
            row.put(entity.getLabel() != null ? entity.getLabel().toLowerCase() : "entity", entity);
            rows.add(row);
        }
        
        return rows;
    }
}
