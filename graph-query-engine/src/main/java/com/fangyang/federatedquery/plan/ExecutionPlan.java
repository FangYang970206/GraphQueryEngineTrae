package com.fangyang.federatedquery.plan;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

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
    private boolean directExecution = false;

    public boolean hasVirtualElements() {
        return hasVirtualElements;
    }

    public void setHasVirtualElements(boolean hasVirtualElements) {
        this.hasVirtualElements = hasVirtualElements;
    }

    public boolean isDirectExecution() {
        return directExecution;
    }

    public void setDirectExecution(boolean directExecution) {
        this.directExecution = directExecution;
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
}
