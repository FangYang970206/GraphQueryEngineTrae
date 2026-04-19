package com.fangyang.federatedquery.ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RelationshipPattern implements AstNode {
    private Direction direction = Direction.BOTH;
    private String variable;
    private List<String> relationshipTypes = new ArrayList<>();
    private Map<String, Object> properties = new HashMap<>();
    private RangeLiteral rangeLiteral;
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public Direction getDirection() {
        return direction;
    }
    
    public void setDirection(Direction direction) {
        this.direction = direction;
    }
    
    public String getVariable() {
        return variable;
    }
    
    public void setVariable(String variable) {
        this.variable = variable;
    }
    
    public List<String> getRelationshipTypes() {
        return relationshipTypes;
    }
    
    public void setRelationshipTypes(List<String> relationshipTypes) {
        this.relationshipTypes = relationshipTypes;
    }
    
    public void addRelationshipType(String type) {
        this.relationshipTypes.add(type);
    }
    
    public Map<String, Object> getProperties() {
        return properties;
    }
    
    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }
    
    public RangeLiteral getRangeLiteral() {
        return rangeLiteral;
    }
    
    public void setRangeLiteral(RangeLiteral rangeLiteral) {
        this.rangeLiteral = rangeLiteral;
    }
    
    @Override
    public String toCypher() {
        StringBuilder sb = new StringBuilder();
        
        if (direction == Direction.LEFT) {
            sb.append("<");
        }
        sb.append("-");
        
        if (variable != null || !relationshipTypes.isEmpty() || !properties.isEmpty() || rangeLiteral != null) {
            sb.append("[");
            if (variable != null) {
                sb.append(variable);
            }
            for (int i = 0; i < relationshipTypes.size(); i++) {
                if (i == 0) {
                    sb.append(":");
                } else {
                    sb.append("|:");
                }
                sb.append(relationshipTypes.get(i));
            }
            if (rangeLiteral != null) {
                sb.append(rangeLiteral.toCypher());
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
            sb.append("]");
        }
        
        sb.append("-");
        if (direction == Direction.RIGHT) {
            sb.append(">");
        }
        
        return sb.toString();
    }
    
    public enum Direction {
        LEFT, RIGHT, BOTH
    }
    
    public static class RangeLiteral implements AstNode {
        private Integer min;
        private Integer max;
        
        @Override
        public <T> T accept(AstVisitor<T> visitor) {
            return null;
        }
        
        public Integer getMin() {
            return min;
        }
        
        public void setMin(Integer min) {
            this.min = min;
        }
        
        public Integer getMax() {
            return max;
        }
        
        public void setMax(Integer max) {
            this.max = max;
        }
        
        @Override
        public String toCypher() {
            StringBuilder sb = new StringBuilder("*");
            if (min != null) {
                sb.append(min);
            }
            if (max != null) {
                sb.append("..").append(max);
            }
            return sb.toString();
        }
    }
}
