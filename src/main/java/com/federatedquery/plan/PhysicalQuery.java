package com.federatedquery.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PhysicalQuery {
    private String id;
    private String cypher;
    private Map<String, Object> parameters = new HashMap<>();
    private List<String> outputVariables = new ArrayList<>();
    private QueryType queryType = QueryType.MATCH;
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getCypher() {
        return cypher;
    }
    
    public void setCypher(String cypher) {
        this.cypher = cypher;
    }
    
    public Map<String, Object> getParameters() {
        return parameters;
    }
    
    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }
    
    public void addParameter(String key, Object value) {
        this.parameters.put(key, value);
    }
    
    public List<String> getOutputVariables() {
        return outputVariables;
    }
    
    public void setOutputVariables(List<String> outputVariables) {
        this.outputVariables = outputVariables;
    }
    
    public void addOutputVariable(String variable) {
        this.outputVariables.add(variable);
    }
    
    public QueryType getQueryType() {
        return queryType;
    }
    
    public void setQueryType(QueryType queryType) {
        this.queryType = queryType;
    }
    
    public enum QueryType {
        MATCH, RETURN, WITH
    }
}
