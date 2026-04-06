package com.federatedquery.plan;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class UnionPart {
    private String id;
    private boolean all = false;
    private List<ExecutionPlan> subPlans = new ArrayList<>();
    
    public void addSubPlan(ExecutionPlan plan) {
        this.subPlans.add(plan);
    }
}
