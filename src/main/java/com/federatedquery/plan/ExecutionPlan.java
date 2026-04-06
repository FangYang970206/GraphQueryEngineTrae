package com.federatedquery.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExecutionPlan {
    private String planId;
    private String originalCypher;
    private List<PhysicalQuery> physicalQueries = new ArrayList<>();
    private List<ExternalQuery> externalQueries = new ArrayList<>();
    private List<UnionPart> unionParts = new ArrayList<>();
    private GlobalContext globalContext = new GlobalContext();
    private boolean hasVirtualElements = false;
    private List<String> warnings = new ArrayList<>();
    
    public String getPlanId() {
        return planId;
    }
    
    public void setPlanId(String planId) {
        this.planId = planId;
    }
    
    public String getOriginalCypher() {
        return originalCypher;
    }
    
    public void setOriginalCypher(String originalCypher) {
        this.originalCypher = originalCypher;
    }
    
    public List<PhysicalQuery> getPhysicalQueries() {
        return physicalQueries;
    }
    
    public void setPhysicalQueries(List<PhysicalQuery> physicalQueries) {
        this.physicalQueries = physicalQueries;
    }
    
    public void addPhysicalQuery(PhysicalQuery query) {
        this.physicalQueries.add(query);
    }
    
    public List<ExternalQuery> getExternalQueries() {
        return externalQueries;
    }
    
    public void setExternalQueries(List<ExternalQuery> externalQueries) {
        this.externalQueries = externalQueries;
    }
    
    public void addExternalQuery(ExternalQuery query) {
        this.externalQueries.add(query);
    }
    
    public List<UnionPart> getUnionParts() {
        return unionParts;
    }
    
    public void setUnionParts(List<UnionPart> unionParts) {
        this.unionParts = unionParts;
    }
    
    public void addUnionPart(UnionPart part) {
        this.unionParts.add(part);
    }
    
    public GlobalContext getGlobalContext() {
        return globalContext;
    }
    
    public void setGlobalContext(GlobalContext globalContext) {
        this.globalContext = globalContext;
    }
    
    public boolean hasVirtualElements() {
        return hasVirtualElements;
    }
    
    public void setHasVirtualElements(boolean hasVirtualElements) {
        this.hasVirtualElements = hasVirtualElements;
    }
    
    public List<String> getWarnings() {
        return warnings;
    }
    
    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }
    
    public void addWarning(String warning) {
        this.warnings.add(warning);
    }
}
