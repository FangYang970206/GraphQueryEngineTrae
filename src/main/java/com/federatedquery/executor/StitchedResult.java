package com.federatedquery.executor;

import com.federatedquery.adapter.GraphEntity;

import java.util.*;

public class StitchedResult {
    private Map<String, GraphEntity> entityById = new HashMap<>();
    private Object globalOrder;
    private Object globalLimit;
    
    public Map<String, GraphEntity> getEntityById() {
        return entityById;
    }
    
    public void setEntityById(Map<String, GraphEntity> entityById) {
        this.entityById = entityById;
    }
    
    public Object getGlobalOrder() {
        return globalOrder;
    }
    
    public void setGlobalOrder(Object globalOrder) {
        this.globalOrder = globalOrder;
    }
    
    public Object getGlobalLimit() {
        return globalLimit;
    }
    
    public void setGlobalLimit(Object globalLimit) {
        this.globalLimit = globalLimit;
    }
}
