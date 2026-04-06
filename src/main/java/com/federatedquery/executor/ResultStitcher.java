package com.federatedquery.executor;

import com.federatedquery.adapter.GraphEntity;
import com.federatedquery.adapter.QueryResult;

import java.util.*;

public class ResultStitcher {
    
    public StitchedResult stitch(ExecutionResult execResult, Object plan) {
        StitchedResult result = new StitchedResult();
        
        Map<String, GraphEntity> entityById = new HashMap<>();
        
        for (List<QueryResult> qrList : execResult.getPhysicalResults().values()) {
            for (QueryResult qr : qrList) {
                for (GraphEntity entity : qr.getEntities()) {
                    entityById.put(entity.getId(), entity);
                }
            }
        }
        
        for (QueryResult qr : execResult.getExternalResults()) {
            for (GraphEntity entity : qr.getEntities()) {
                entityById.put(entity.getId(), entity);
            }
        }
        
        result.setEntityById(entityById);
        
        return result;
    }
}
