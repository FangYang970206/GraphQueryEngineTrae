package com.fangyang.federatedquery.reliability;

import com.fangyang.federatedquery.ast.*;
import com.fangyang.metadata.MetadataQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class WhereConditionPushdown {
    @Autowired
    private MetadataQueryService metadataQueryService;

    public WhereConditionPushdown(MetadataQueryService metadataQueryService) {
        this.metadataQueryService = metadataQueryService;
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
            if (!"AND".equalsIgnoreCase(logic.getOperator())) {
                routeWholeExpression(expr, virtualVars, physicalVars, result);
                return;
            }
            for (Expression operand : logic.getOperands()) {
                extractConditions(operand, virtualVars, physicalVars, result);
            }
            return;
        }

        if (!(expr instanceof Comparison)) {
            routeWholeExpression(expr, virtualVars, physicalVars, result);
            return;
        }

        Comparison comp = (Comparison) expr;
        Condition condition = extractCondition(comp);
        if (condition == null) {
            routeWholeExpression(expr, virtualVars, physicalVars, result);
            return;
        }

        if (virtualVars.contains(condition.getVariable())) {
            result.addVirtualCondition(condition);
        } else if (physicalVars.contains(condition.getVariable())) {
            result.addPhysicalCondition(condition);
        } else {
            result.addUnknownCondition(condition);
            result.addPostFilterCondition(condition);
        }
    }

    private void routeWholeExpression(
            Expression expr,
            Set<String> virtualVars,
            Set<String> physicalVars,
            PushdownResult result) {
        ExpressionOwnership ownership = determineOwnership(expr, virtualVars, physicalVars);
        if (ownership == ExpressionOwnership.PHYSICAL) {
            result.addPhysicalCondition(Condition.forExpression(expr));
            return;
        }
        result.addPostFilterCondition(Condition.forPostFilter(expr));
    }

    private Condition extractCondition(Comparison comp) {
        if (comp == null || comp.getLeft() == null) {
            return null;
        }

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
        } else {
            return null;
        }

        if (condition.getVariable() == null || !isPushdownSupportedOperator(comp.getOperator())) {
            return null;
        }

        if (isUnaryOperator(comp.getOperator())) {
            return condition;
        }

        ResolvedValue resolvedValue = resolveStaticValue(comp.getRight());
        if (!resolvedValue.isResolved()) {
            return null;
        }
        condition.setValue(resolvedValue.getValue());

        return condition;
    }

    private boolean isPushdownSupportedOperator(String operator) {
        if (operator == null || operator.isEmpty()) {
            return false;
        }
        switch (operator.toUpperCase()) {
            case "=":
            case "==":
            case "<>":
            case "!=":
            case ">":
            case ">=":
            case "<":
            case "<=":
            case "IN":
            case "CONTAINS":
            case "STARTS WITH":
            case "ENDS WITH":
            case "IS NULL":
            case "IS NOT NULL":
                return true;
            default:
                return false;
        }
    }

    private boolean isUnaryOperator(String operator) {
        return "IS NULL".equalsIgnoreCase(operator) || "IS NOT NULL".equalsIgnoreCase(operator);
    }

    private ResolvedValue resolveStaticValue(Expression expr) {
        if (expr instanceof Literal) {
            return ResolvedValue.resolved(((Literal) expr).getValue());
        }
        if (expr instanceof ListExpression) {
            List<Object> values = new ArrayList<>();
            for (Expression element : ((ListExpression) expr).getElements()) {
                ResolvedValue resolved = resolveStaticValue(element);
                if (!resolved.isResolved()) {
                    return ResolvedValue.unresolved();
                }
                values.add(resolved.getValue());
            }
            return ResolvedValue.resolved(values);
        }
        if (expr instanceof MapExpression) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, Expression> entry : ((MapExpression) expr).getEntries().entrySet()) {
                ResolvedValue resolved = resolveStaticValue(entry.getValue());
                if (!resolved.isResolved()) {
                    return ResolvedValue.unresolved();
                }
                values.put(entry.getKey(), resolved.getValue());
            }
            return ResolvedValue.resolved(values);
        }
        return ResolvedValue.unresolved();
    }

    private ExpressionOwnership determineOwnership(
            Expression expr,
            Set<String> virtualVars,
            Set<String> physicalVars) {
        Set<String> referencedVariables = new HashSet<>();
        collectReferencedVariables(expr, referencedVariables);
        if (referencedVariables.isEmpty()) {
            return ExpressionOwnership.UNKNOWN;
        }

        boolean hasVirtual = false;
        boolean hasPhysical = false;
        boolean hasUnknown = false;
        for (String variable : referencedVariables) {
            if (virtualVars.contains(variable)) {
                hasVirtual = true;
            } else if (physicalVars.contains(variable)) {
                hasPhysical = true;
            } else {
                hasUnknown = true;
            }
        }

        if (hasUnknown || (hasVirtual && hasPhysical)) {
            return ExpressionOwnership.MIXED;
        }
        if (hasPhysical) {
            return ExpressionOwnership.PHYSICAL;
        }
        if (hasVirtual) {
            return ExpressionOwnership.VIRTUAL;
        }
        return ExpressionOwnership.UNKNOWN;
    }

    private void collectReferencedVariables(Expression expr, Set<String> variables) {
        if (expr == null) {
            return;
        }
        if (expr instanceof Variable) {
            variables.add(((Variable) expr).getName());
            return;
        }
        if (expr instanceof PropertyAccess) {
            PropertyAccess propertyAccess = (PropertyAccess) expr;
            if (propertyAccess.getTarget() instanceof Variable) {
                variables.add(((Variable) propertyAccess.getTarget()).getName());
            } else {
                collectReferencedVariables(propertyAccess.getTarget(), variables);
            }
            return;
        }
        if (expr instanceof Comparison) {
            Comparison comparison = (Comparison) expr;
            collectReferencedVariables(comparison.getLeft(), variables);
            collectReferencedVariables(comparison.getRight(), variables);
            return;
        }
        if (expr instanceof LogicalExpression) {
            for (Expression operand : ((LogicalExpression) expr).getOperands()) {
                collectReferencedVariables(operand, variables);
            }
            return;
        }
        if (expr instanceof FunctionCall) {
            for (Expression argument : ((FunctionCall) expr).getArguments()) {
                collectReferencedVariables(argument, variables);
            }
            return;
        }
        if (expr instanceof ListExpression) {
            for (Expression element : ((ListExpression) expr).getElements()) {
                collectReferencedVariables(element, variables);
            }
            return;
        }
        if (expr instanceof MapExpression) {
            for (Expression value : ((MapExpression) expr).getEntries().values()) {
                collectReferencedVariables(value, variables);
            }
            return;
        }
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
            boolean virtualRelationship = isVirtualRelationship(chain.getRelationshipPattern());
            if (virtualRelationship) {
                if (chain.getRelationshipPattern().getVariable() != null) {
                    vars.add(chain.getRelationshipPattern().getVariable());
                }
            }
            
            NodePattern endNode = chain.getNodePattern();
            if (endNode != null && (virtualRelationship || isVirtualNode(endNode))) {
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
            boolean virtualRelationship = isVirtualRelationship(chain.getRelationshipPattern());
            if (!virtualRelationship) {
                if (chain.getRelationshipPattern().getVariable() != null) {
                    vars.add(chain.getRelationshipPattern().getVariable());
                }
            }
            
            NodePattern endNode = chain.getNodePattern();
            if (endNode != null && !virtualRelationship && !isVirtualNode(endNode)) {
                if (endNode.getVariable() != null) {
                    vars.add(endNode.getVariable());
                }
            }
        }
    }

    private boolean isVirtualNode(NodePattern node) {
        for (String label : node.getLabels()) {
            if (metadataQueryService.isVirtualLabel(label)) {
                return true;
            }
        }
        return false;
    }

    private boolean isVirtualRelationship(RelationshipPattern rel) {
        for (String type : rel.getRelationshipTypes()) {
            if (metadataQueryService.isVirtualEdge(type)) {
                return true;
            }
        }
        return false;
    }

    public static class PushdownResult {
        private List<Condition> physicalConditions = new ArrayList<>();
        private List<Condition> virtualConditions = new ArrayList<>();
        private List<Condition> unknownConditions = new ArrayList<>();
        private List<Condition> postFilterConditions = new ArrayList<>();

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

        public List<Condition> getPostFilterConditions() {
            return postFilterConditions;
        }

        public void addPostFilterCondition(Condition condition) {
            this.postFilterConditions.add(condition);
        }
    }

    public static class Condition {
        private String variable;
        private String property;
        private String operator;
        private Object value;
        private Expression originalExpression;

        public static Condition forPostFilter(Expression expression) {
            Condition condition = new Condition();
            condition.setOriginalExpression(expression);
            return condition;
        }

        public static Condition forExpression(Expression expression) {
            Condition condition = new Condition();
            condition.setOriginalExpression(expression);
            return condition;
        }

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
            if (originalExpression != null) {
                return originalExpression.toCypher();
            }
            if (property != null) {
                return variable + "." + property + " " + operator + " " + formatValue(value);
            }
            return variable + " " + operator + " " + formatValue(value);
        }

        private String formatValue(Object value) {
            if (value == null) return "NULL";
            if (value instanceof String) return "'" + value + "'";
            if (value instanceof List) {
                List<String> parts = new ArrayList<>();
                for (Object item : (List<?>) value) {
                    parts.add(formatValue(item));
                }
                return "[" + String.join(", ", parts) + "]";
            }
            if (value instanceof Map) {
                List<String> parts = new ArrayList<>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    parts.add(entry.getKey() + ": " + formatValue(entry.getValue()));
                }
                return "{" + String.join(", ", parts) + "}";
            }
            return value.toString();
        }
    }

    private static class ResolvedValue {
        private final boolean resolved;
        private final Object value;

        private ResolvedValue(boolean resolved, Object value) {
            this.resolved = resolved;
            this.value = value;
        }

        public static ResolvedValue resolved(Object value) {
            return new ResolvedValue(true, value);
        }

        public static ResolvedValue unresolved() {
            return new ResolvedValue(false, null);
        }

        public boolean isResolved() {
            return resolved;
        }

        public Object getValue() {
            return value;
        }
    }

    private enum ExpressionOwnership {
        PHYSICAL,
        VIRTUAL,
        MIXED,
        UNKNOWN
    }
}
