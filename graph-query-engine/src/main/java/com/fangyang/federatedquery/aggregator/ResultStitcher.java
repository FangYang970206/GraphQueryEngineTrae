package com.fangyang.federatedquery.aggregator;

import com.fangyang.federatedquery.model.GraphEntity;
import com.fangyang.federatedquery.model.QueryResult;
import com.fangyang.federatedquery.executor.ExecutionResult;
import com.fangyang.federatedquery.plan.ExecutionPlan;
import com.fangyang.federatedquery.plan.GlobalContext;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
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
        
        for (List<QueryResult> qrList : execResult.getPhysicalResults().values()) {
            for (QueryResult qr : qrList) {
                for (GraphEntity entity : qr.getEntities()) {
                    Map<String, Object> row = new HashMap<>();
                    String varName = entity.getLabel() != null ? entity.getLabel().toLowerCase() : "entity";
                    row.put(varName, entity);
                    rows.add(row);
                }
            }
        }
        
        for (QueryResult qr : execResult.getExternalResults()) {
            for (GraphEntity entity : qr.getEntities()) {
                Map<String, Object> row = new HashMap<>();
                String varName = entity.getLabel() != null ? entity.getLabel().toLowerCase() : "entity";
                row.put(varName, entity);
                rows.add(row);
            }
        }
        
        for (QueryResult qr : execResult.getUnionResults().values()) {
            for (GraphEntity entity : qr.getEntities()) {
                Map<String, Object> row = new HashMap<>();
                String varName = entity.getLabel() != null ? entity.getLabel().toLowerCase() : "entity";
                row.put(varName, entity);
                rows.add(row);
            }
        }
        
        return rows;
    }
}
