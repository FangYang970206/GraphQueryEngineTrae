package com.federatedquery.plan;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class ExternalQuery {
    private String id;
    private String dataSource;
    private String operator;
    private Map<String, Object> inputMapping = new HashMap<>();
    private List<String> inputIds = new ArrayList<>();
    private String inputIdField;
    private List<String> outputVariables = new ArrayList<>();
    private List<String> outputFields = new ArrayList<>();
    private Map<String, Object> filters = new HashMap<>();
    private Map<String, Object> parameters = new HashMap<>();
    private boolean batched = false;
    private String snapshotName;
    private Object snapshotTime;
    
    private boolean dependsOnPhysicalQuery = false;
    private String sourceVariableName;
    
    public boolean needsInputIds() {
        return inputIdField != null && !inputIdField.isEmpty();
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
    
    public void addInputId(String id) { this.inputIds.add(id); }
    public void addOutputVariable(String variable) { this.outputVariables.add(variable); }
    public void addFilter(String key, Object value) { this.filters.put(key, value); }
    public void addParameter(String key, Object value) { this.parameters.put(key, value); }
}
