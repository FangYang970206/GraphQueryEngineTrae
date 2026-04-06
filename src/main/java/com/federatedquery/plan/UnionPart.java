package com.federatedquery.plan;

import java.util.ArrayList;
import java.util.List;

public class UnionPart {
    private String id;
    private boolean all = false;
    private List<ExecutionPlan> subPlans = new ArrayList<>();
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public boolean isAll() {
        return all;
    }
    
    public void setAll(boolean all) {
        this.all = all;
    }
    
    public List<ExecutionPlan> getSubPlans() {
        return subPlans;
    }
    
    public void setSubPlans(List<ExecutionPlan> subPlans) {
        this.subPlans = subPlans;
    }
    
    public void addSubPlan(ExecutionPlan plan) {
        this.subPlans.add(plan);
    }
}
