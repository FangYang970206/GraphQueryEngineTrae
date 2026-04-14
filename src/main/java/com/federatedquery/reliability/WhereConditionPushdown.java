package com.federatedquery.reliability;

import com.federatedquery.ast.*;
import com.federatedquery.metadata.LabelMetadata;
import com.federatedquery.metadata.MetadataRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class WhereConditionPushdown {
    @Autowired
    private MetadataRegistry registry;

    public WhereConditionPushdown() {
    }

    public WhereConditionPushdown(MetadataRegistry registry) {
        this.registry = registry;
    }
    
    public PushdownResult analyze(WhereClause where, Pattern pattern) {
        PushdownResult result = new PushdownResult();
        
        if (where == null || where.getExpression() == null) {
            return result;
        }
        
        Set<String> virtualVariables = extractVirtualVariables(pattern);
        Set<String> physicalVariables = extractPhysicalVariables(pattern);
        
        extractConditions(where.getExpression(), virtualVariables, physicalVariables, result);
        
        return result;
    }
    
    private void extractConditions(Expression expr, Set<String> virtualVars, 
                                   Set<String> physicalVars, PushdownResult result) {
        if (expr instanceof LogicalExpression) {
            LogicalExpression logic = (LogicalExpression) expr;
            for (Expression operand : logic.getOperands()) {
                extractConditions(operand, virtualVars, physicalVars, result);
            }
        } else if (expr instanceof Comparison) {
            Comparison comp = (Comparison) expr;
            Condition condition = extractCondition(comp);
            
            if (condition != null) {
                if (virtualVars.contains(condition.getVariable())) {
                    result.addVirtualCondition(condition);
                } else if (physicalVars.contains(condition.getVariable())) {
                    result.addPhysicalCondition(condition);
                } else {
                    result.addUnknownCondition(condition);
                }
            }
        }
    }
    
    private Condition extractCondition(Comparison comp) {
        Condition condition = new Condition();
        condition.setOperator(comp.getOperator());
        condition.setOriginalExpression(comp);
        
        if (comp.getLeft() instanceof PropertyAccess) {
            PropertyAccess pa = (PropertyAccess) comp.getLeft();
            if (pa.getTarget() instanceof Variable) {
                condition.setVariable(((Variable) pa.getTarget()).getName());
            }
            condition.setProperty(pa.getPropertyName());
        } else if (comp.getLeft() instanceof Variable) {
            condition.setVariable(((Variable) comp.getLeft()).getName());
        }
        
        if (comp.getRight() instanceof Literal) {
            condition.setValue(((Literal) comp.getRight()).getValue());
        }
        
        return condition;
    }
    
    private Set<String> extractVirtualVariables(Pattern pattern) {
        Set<String> vars = new HashSet<>();
        
        for (Pattern.PatternPart part : pattern.getPatternParts()) {
            if (part.getPatternElement() != null) {
                extractVirtualVariablesFromElement(part.getPatternElement(), vars);
            }
        }
        
        return vars;
    }
    
    private void extractVirtualVariablesFromElement(Pattern.PatternElement element, Set<String> vars) {
        NodePattern node = element.getNodePattern();
        if (node != null && isVirtualNode(node)) {
            if (node.getVariable() != null) {
                vars.add(node.getVariable());
            }
        }
        
        for (Pattern.PatternElementChain chain : element.getChains()) {
            if (isVirtualRelationship(chain.getRelationshipPattern())) {
                if (chain.getRelationshipPattern().getVariable() != null) {
                    vars.add(chain.getRelationshipPattern().getVariable());
                }
            }
            
            NodePattern endNode = chain.getNodePattern();
            if (endNode != null && isVirtualNode(endNode)) {
                if (endNode.getVariable() != null) {
                    vars.add(endNode.getVariable());
                }
            }
        }
    }
    
    private Set<String> extractPhysicalVariables(Pattern pattern) {
        Set<String> vars = new HashSet<>();
        
        for (Pattern.PatternPart part : pattern.getPatternParts()) {
            if (part.getPatternElement() != null) {
                extractPhysicalVariablesFromElement(part.getPatternElement(), vars);
            }
        }
        
        return vars;
    }
    
    private void extractPhysicalVariablesFromElement(Pattern.PatternElement element, Set<String> vars) {
        NodePattern node = element.getNodePattern();
        if (node != null && !isVirtualNode(node)) {
            if (node.getVariable() != null) {
                vars.add(node.getVariable());
            }
        }
        
        for (Pattern.PatternElementChain chain : element.getChains()) {
            if (!isVirtualRelationship(chain.getRelationshipPattern())) {
                if (chain.getRelationshipPattern().getVariable() != null) {
                    vars.add(chain.getRelationshipPattern().getVariable());
                }
            }
            
            NodePattern endNode = chain.getNodePattern();
            if (endNode != null && !isVirtualNode(endNode)) {
                if (endNode.getVariable() != null) {
                    vars.add(endNode.getVariable());
                }
            }
        }
    }
    
    private boolean isVirtualNode(NodePattern node) {
        for (String label : node.getLabels()) {
            if (registry.isVirtualLabel(label)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isVirtualRelationship(RelationshipPattern rel) {
        for (String type : rel.getRelationshipTypes()) {
            if (registry.isVirtualEdge(type)) {
                return true;
            }
        }
        return false;
    }
    
    public static class PushdownResult {
        private List<Condition> physicalConditions = new ArrayList<>();
        private List<Condition> virtualConditions = new ArrayList<>();
        private List<Condition> unknownConditions = new ArrayList<>();
        
        public List<Condition> getPhysicalConditions() {
            return physicalConditions;
        }
        
        public void addPhysicalCondition(Condition condition) {
            this.physicalConditions.add(condition);
        }
        
        public List<Condition> getVirtualConditions() {
            return virtualConditions;
        }
        
        public void addVirtualCondition(Condition condition) {
            this.virtualConditions.add(condition);
        }
        
        public List<Condition> getUnknownConditions() {
            return unknownConditions;
        }
        
        public void addUnknownCondition(Condition condition) {
            this.unknownConditions.add(condition);
        }
    }
    
    public static class Condition {
        private String variable;
        private String property;
        private String operator;
        private Object value;
        private Expression originalExpression;
        
        public String getVariable() {
            return variable;
        }
        
        public void setVariable(String variable) {
            this.variable = variable;
        }
        
        public String getProperty() {
            return property;
        }
        
        public void setProperty(String property) {
            this.property = property;
        }
        
        public String getOperator() {
            return operator;
        }
        
        public void setOperator(String operator) {
            this.operator = operator;
        }
        
        public Object getValue() {
            return value;
        }
        
        public void setValue(Object value) {
            this.value = value;
        }
        
        public Expression getOriginalExpression() {
            return originalExpression;
        }
        
        public void setOriginalExpression(Expression originalExpression) {
            this.originalExpression = originalExpression;
        }
        
        public String toCypher() {
            if (property != null) {
                return variable + "." + property + " " + operator + " " + formatValue(value);
            }
            return variable + " " + operator + " " + formatValue(value);
        }
        
        private String formatValue(Object value) {
            if (value == null) return "NULL";
            if (value instanceof String) return "'" + value + "'";
            return value.toString();
        }
    }
}
