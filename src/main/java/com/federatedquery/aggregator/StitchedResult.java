package com.federatedquery.aggregator;

import com.federatedquery.adapter.GraphEntity;
import com.federatedquery.plan.GlobalContext;

import java.util.*;

public class StitchedResult {
    private Map<String, GraphEntity> entityById = new HashMap<>();
    private List<Map<String, Object>> rows = new ArrayList<>();
    private List<GlobalContext.WhereCondition> pendingFilters = new ArrayList<>();
    private GlobalContext.OrderSpec globalOrder;
    private GlobalContext.LimitSpec globalLimit;
    
    public Map<String, GraphEntity> getEntityById() {
        return entityById;
    }
    
    public void setEntityById(Map<String, GraphEntity> entityById) {
        this.entityById = entityById;
    }
    
    public List<Map<String, Object>> getRows() {
        return rows;
    }
    
    public void setRows(List<Map<String, Object>> rows) {
        this.rows = rows;
    }
    
    public List<GlobalContext.WhereCondition> getPendingFilters() {
        return pendingFilters;
    }
    
    public void setPendingFilters(List<GlobalContext.WhereCondition> pendingFilters) {
        this.pendingFilters = pendingFilters;
    }
    
    public GlobalContext.OrderSpec getGlobalOrder() {
        return globalOrder;
    }
    
    public void setGlobalOrder(GlobalContext.OrderSpec globalOrder) {
        this.globalOrder = globalOrder;
    }
    
    public GlobalContext.LimitSpec getGlobalLimit() {
        return globalLimit;
    }
    
    public void setGlobalLimit(GlobalContext.LimitSpec globalLimit) {
        this.globalLimit = globalLimit;
    }
    
    public int size() {
        return rows.size();
    }
}
