package com.federatedquery.metadata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VirtualEdgeBinding {
    private String edgeType;
    private String targetDataSource;
    private String operatorName;
    private Map<String, String> idMapping = new HashMap<>();
    private List<String> outputFields = new ArrayList<>();
    private boolean firstHopOnly = false;
    private boolean lastHopOnly = false;
    
    public String getEdgeType() {
        return edgeType;
    }
    
    public void setEdgeType(String edgeType) {
        this.edgeType = edgeType;
    }
    
    public String getTargetDataSource() {
        return targetDataSource;
    }
    
    public void setTargetDataSource(String targetDataSource) {
        this.targetDataSource = targetDataSource;
    }
    
    public String getOperatorName() {
        return operatorName;
    }
    
    public void setOperatorName(String operatorName) {
        this.operatorName = operatorName;
    }
    
    public Map<String, String> getIdMapping() {
        return idMapping;
    }
    
    public void setIdMapping(Map<String, String> idMapping) {
        this.idMapping = idMapping;
    }
    
    public List<String> getOutputFields() {
        return outputFields;
    }
    
    public void setOutputFields(List<String> outputFields) {
        this.outputFields = outputFields;
    }
    
    public boolean isFirstHopOnly() {
        return firstHopOnly;
    }
    
    public void setFirstHopOnly(boolean firstHopOnly) {
        this.firstHopOnly = firstHopOnly;
    }
    
    public boolean isLastHopOnly() {
        return lastHopOnly;
    }
    
    public void setLastHopOnly(boolean lastHopOnly) {
        this.lastHopOnly = lastHopOnly;
    }
}
