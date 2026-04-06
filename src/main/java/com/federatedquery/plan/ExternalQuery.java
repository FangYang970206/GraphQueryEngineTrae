package com.federatedquery.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getDataSource() { return dataSource; }
    public void setDataSource(String dataSource) { this.dataSource = dataSource; }
    
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    
    public Map<String, Object> getInputMapping() { return inputMapping; }
    public void setInputMapping(Map<String, Object> inputMapping) { this.inputMapping = inputMapping; }
    
    public List<String> getInputIds() { return inputIds; }
    public void setInputIds(List<String> inputIds) { this.inputIds = inputIds; }
    public void addInputId(String id) { this.inputIds.add(id); }
    
    public String getInputIdField() { return inputIdField; }
    public void setInputIdField(String inputIdField) { this.inputIdField = inputIdField; }
    
    public List<String> getOutputVariables() { return outputVariables; }
    public void setOutputVariables(List<String> outputVariables) { this.outputVariables = outputVariables; }
    public void addOutputVariable(String variable) { this.outputVariables.add(variable); }
    
    public List<String> getOutputFields() { return outputFields; }
    public void setOutputFields(List<String> outputFields) { this.outputFields = outputFields; }
    
    public Map<String, Object> getFilters() { return filters; }
    public void setFilters(Map<String, Object> filters) { this.filters = filters; }
    public void addFilter(String key, Object value) { this.filters.put(key, value); }
    
    public Map<String, Object> getParameters() { return parameters; }
    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
    public void addParameter(String key, Object value) { this.parameters.put(key, value); }
    
    public boolean isBatched() { return batched; }
    public void setBatched(boolean batched) { this.batched = batched; }
}
