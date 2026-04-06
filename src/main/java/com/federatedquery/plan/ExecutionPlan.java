package com.federatedquery.plan;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Data
public class ExecutionPlan {
    private String planId;
    private String originalCypher;
    private List<PhysicalQuery> physicalQueries = new ArrayList<>();
    private List<ExternalQuery> externalQueries = new ArrayList<>();
    private List<UnionPart> unionParts = new ArrayList<>();
    private GlobalContext globalContext = new GlobalContext();
    @Getter(AccessLevel.NONE)
    private boolean hasVirtualElements = false;
    private List<String> warnings = new ArrayList<>();
    
    public boolean hasVirtualElements() {
        return hasVirtualElements;
    }
    
    public void setHasVirtualElements(boolean hasVirtualElements) {
        this.hasVirtualElements = hasVirtualElements;
    }
    
    public void addPhysicalQuery(PhysicalQuery query) {
        this.physicalQueries.add(query);
    }
    
    public void addExternalQuery(ExternalQuery query) {
        this.externalQueries.add(query);
    }
    
    public void addUnionPart(UnionPart part) {
        this.unionParts.add(part);
    }
    
    public void addWarning(String warning) {
        this.warnings.add(warning);
    }
}
