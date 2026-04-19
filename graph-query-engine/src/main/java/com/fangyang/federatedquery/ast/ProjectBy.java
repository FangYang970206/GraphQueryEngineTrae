package com.fangyang.federatedquery.ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProjectBy implements AstNode {
    private Map<String, List<String>> projections = new HashMap<>();
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public Map<String, List<String>> getProjections() {
        return projections;
    }
    
    public void setProjections(Map<String, List<String>> projections) {
        this.projections = projections;
    }
    
    public void addProjection(String label, List<String> fields) {
        this.projections.put(label, fields);
    }
    
    @Override
    public String toCypher() {
        StringBuilder sb = new StringBuilder("PROJECT BY {");
        boolean firstLabel = true;
        for (Map.Entry<String, List<String>> entry : projections.entrySet()) {
            if (!firstLabel) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append(": [");
            for (int i = 0; i < entry.getValue().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(entry.getValue().get(i));
            }
            sb.append("]");
            firstLabel = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
