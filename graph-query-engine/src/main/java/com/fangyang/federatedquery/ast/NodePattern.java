package com.fangyang.federatedquery.ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NodePattern implements AstNode {
    private String variable;
    private List<String> labels = new ArrayList<>();
    private Map<String, Object> properties = new HashMap<>();
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public String getVariable() {
        return variable;
    }
    
    public void setVariable(String variable) {
        this.variable = variable;
    }
    
    public List<String> getLabels() {
        return labels;
    }
    
    public void setLabels(List<String> labels) {
        this.labels = labels;
    }
    
    public void addLabel(String label) {
        this.labels.add(label);
    }
    
    public Map<String, Object> getProperties() {
        return properties;
    }
    
    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }
    
    public void addProperty(String key, Object value) {
        this.properties.put(key, value);
    }
    
    @Override
    public String toCypher() {
        StringBuilder sb = new StringBuilder("(");
        if (variable != null) {
            sb.append(variable);
        }
        for (String label : labels) {
            sb.append(":").append(label);
        }
        if (!properties.isEmpty()) {
            sb.append(" {");
            boolean first = true;
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(entry.getKey()).append(": ");
                if (entry.getValue() instanceof String) {
                    sb.append("'").append(entry.getValue()).append("'");
                } else {
                    sb.append(entry.getValue());
                }
                first = false;
            }
            sb.append("}");
        }
        sb.append(")");
        return sb.toString();
    }
}
