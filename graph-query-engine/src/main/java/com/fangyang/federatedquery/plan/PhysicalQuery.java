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
    
    public void addParameter(String key, Object value) {
        this.parameters.put(key, value);
    }
    
    public void addOutputVariable(String variable) {
        this.outputVariables.add(variable);
    }
    
    public enum QueryType {
        MATCH, RETURN, WITH
    }
}
