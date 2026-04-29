package com.fangyang.federatedquery.plan;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class PhysicalQuery {
    private String id;
    private String cypher;
    private Map<String, Object> parameters = new HashMap<>();
    private List<String> outputVariables = new ArrayList<>();
    private QueryType queryType = QueryType.MATCH;
    private boolean dependsOnExternalQuery = false;
    private String sourceVariableName;
    private String dependencySourceField;
    private String dependencyTargetVariable;
    private String dependencyTargetField;
    private String dependencyParameterName;
    private List<String> inputIds = new ArrayList<>();
    
    public void addParameter(String key, Object value) {
        this.parameters.put(key, value);
    }
    
    public void addOutputVariable(String variable) {
        this.outputVariables.add(variable);
    }

    public boolean needsInputIds() {
        return dependsOnExternalQuery
                && dependencySourceField != null
                && !dependencySourceField.isEmpty()
                && dependencyTargetField != null
                && !dependencyTargetField.isEmpty();
    }

    public boolean hasInputIds() {
        return inputIds != null && !inputIds.isEmpty();
    }

    public boolean isReadyToExecute() {
        if (!needsInputIds()) {
            return true;
        }
        return hasInputIds();
    }
    
    public enum QueryType {
        MATCH, RETURN, WITH
    }
}
