package com.fangyang.federatedquery.aggregator;

import com.fangyang.federatedquery.model.GraphEntity;
import com.fangyang.federatedquery.plan.GlobalContext;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class PendingFilterApplier {

    public List<Map<String, Object>> apply(List<Map<String, Object>> results, List<GlobalContext.WhereCondition> pendingFilters) {
        return apply(results, pendingFilters, Collections.emptyMap());
    }

    public List<Map<String, Object>> apply(List<Map<String, Object>> results,
                                           List<GlobalContext.WhereCondition> pendingFilters,
                                           Map<String, Object> params) {
        if (pendingFilters == null || pendingFilters.isEmpty()) {
            return results;
        }

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : results) {
            if (matchesAllConditions(row, pendingFilters, params)) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private boolean matchesAllConditions(Map<String, Object> row,
                                         List<GlobalContext.WhereCondition> conditions,
                                         Map<String, Object> params) {
        for (GlobalContext.WhereCondition condition : conditions) {
            if (!matchesCondition(row, condition, params)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesCondition(Map<String, Object> row,
                                     GlobalContext.WhereCondition condition,
                                     Map<String, Object> params) {
        if (condition.getOriginalExpression() != null) {
            return evaluateBooleanExpression(row, condition.getOriginalExpression(), params);
        }

        String variable = condition.getVariable();
        String property = condition.getProperty();
        String operator = condition.getOperator();
        Object expectedValue = condition.getValue();

        Object entityObj = row.get(variable);
        if (entityObj == null) {
            return false;
        }

        Object actualValue = extractPropertyValue(entityObj, property);
        return evaluateCondition(actualValue, operator, expectedValue);
    }

    private Object extractPropertyValue(Object entityObj, String property) {
        if (property == null || property.isEmpty()) {
            return entityObj;
        }
        if (entityObj instanceof Map) {
            return ((Map<String, Object>) entityObj).get(property);
        } else if (entityObj instanceof GraphEntity) {
            return ((GraphEntity) entityObj).getProperty(property);
        }
        return null;
    }

    private boolean evaluateBooleanExpression(Map<String, Object> row,
                                              com.fangyang.federatedquery.ast.Expression expression,
                                              Map<String, Object> params) {
        if (expression instanceof com.fangyang.federatedquery.ast.LogicalExpression) {
            com.fangyang.federatedquery.ast.LogicalExpression logic =
                    (com.fangyang.federatedquery.ast.LogicalExpression) expression;
            String operator = logic.getOperator() != null ? logic.getOperator().toUpperCase(Locale.ROOT) : "";
            switch (operator) {
                case "AND":
                    for (com.fangyang.federatedquery.ast.Expression operand : logic.getOperands()) {
                        if (!evaluateBooleanExpression(row, operand, params)) {
                            return false;
                        }
                    }
                    return true;
                case "OR":
                    for (com.fangyang.federatedquery.ast.Expression operand : logic.getOperands()) {
                        if (evaluateBooleanExpression(row, operand, params)) {
                            return true;
                        }
                    }
                    return false;
                case "XOR":
                    boolean result = false;
                    for (com.fangyang.federatedquery.ast.Expression operand : logic.getOperands()) {
                        result ^= evaluateBooleanExpression(row, operand, params);
                    }
                    return result;
                case "NOT":
                    return logic.getOperands().isEmpty()
                            || !evaluateBooleanExpression(row, logic.getOperands().get(0), params);
                default:
                    return false;
            }
        }

        if (expression instanceof com.fangyang.federatedquery.ast.Comparison) {
            com.fangyang.federatedquery.ast.Comparison comparison =
                    (com.fangyang.federatedquery.ast.Comparison) expression;
            Object actualValue = resolveExpressionValue(row, comparison.getLeft(), params);
            String operator = comparison.getOperator();
            Object expectedValue = isUnaryOperator(operator)
                    ? null
                    : resolveExpressionValue(row, comparison.getRight(), params);
            return evaluateCondition(actualValue, operator, expectedValue);
        }

        Object value = resolveExpressionValue(row, expression, params);
        return toBoolean(value);
    }

    private Object resolveExpressionValue(Map<String, Object> row,
                                          com.fangyang.federatedquery.ast.Expression expression,
                                          Map<String, Object> params) {
        if (expression == null) {
            return null;
        }
        if (expression instanceof com.fangyang.federatedquery.ast.Literal) {
            return ((com.fangyang.federatedquery.ast.Literal) expression).getValue();
        }
        if (expression instanceof com.fangyang.federatedquery.ast.Parameter) {
            return resolveParameterValue((com.fangyang.federatedquery.ast.Parameter) expression, params);
        }
        if (expression instanceof com.fangyang.federatedquery.ast.Variable) {
            return row.get(((com.fangyang.federatedquery.ast.Variable) expression).getName());
        }
        if (expression instanceof com.fangyang.federatedquery.ast.PropertyAccess) {
            com.fangyang.federatedquery.ast.PropertyAccess access =
                    (com.fangyang.federatedquery.ast.PropertyAccess) expression;
            Object target = resolveExpressionValue(row, access.getTarget(), params);
            return extractPropertyValue(target, access.getPropertyName());
        }
        if (expression instanceof com.fangyang.federatedquery.ast.ListExpression) {
            List<Object> values = new ArrayList<>();
            for (com.fangyang.federatedquery.ast.Expression element :
                    ((com.fangyang.federatedquery.ast.ListExpression) expression).getElements()) {
                values.add(resolveExpressionValue(row, element, params));
            }
            return values;
        }
        if (expression instanceof com.fangyang.federatedquery.ast.MapExpression) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, com.fangyang.federatedquery.ast.Expression> entry :
                    ((com.fangyang.federatedquery.ast.MapExpression) expression).getEntries().entrySet()) {
                values.put(entry.getKey(), resolveExpressionValue(row, entry.getValue(), params));
            }
            return values;
        }
        return null;
    }

    private Object resolveParameterValue(com.fangyang.federatedquery.ast.Parameter parameter, Map<String, Object> params) {
        if (params == null || params.isEmpty() || parameter == null) {
            return null;
        }
        if (parameter.getName() != null) {
            return params.get(parameter.getName());
        }
        if (parameter.getIndex() != null) {
            Object byIndex = params.get(parameter.getIndex());
            if (byIndex != null) {
                return byIndex;
            }
            return params.get(String.valueOf(parameter.getIndex()));
        }
        return null;
    }

    private boolean evaluateCondition(Object actualValue, String operator, Object expectedValue) {
        if (actualValue == null && expectedValue == null) {
            return "=".equals(operator) || "==".equals(operator) || "IS".equals(operator) || "IS NULL".equals(operator);
        }
        if ("IS NULL".equals(operator)) {
            return actualValue == null;
        }
        if ("IS NOT NULL".equals(operator)) {
            return actualValue != null;
        }
        if (actualValue == null) {
            return false;
        }

        switch (operator) {
            case "=":
            case "==":
                return actualValue.equals(expectedValue) ||
                        (actualValue instanceof Number && expectedValue instanceof Number &&
                                ((Number) actualValue).doubleValue() == ((Number) expectedValue).doubleValue());
            case "<>":
            case "!=":
                return !actualValue.equals(expectedValue);
            case ">":
                return compareValues(actualValue, expectedValue) > 0;
            case ">=":
                return compareValues(actualValue, expectedValue) >= 0;
            case "<":
                return compareValues(actualValue, expectedValue) < 0;
            case "<=":
                return compareValues(actualValue, expectedValue) <= 0;
            case "IN":
                return expectedValue instanceof Collection && ((Collection<?>) expectedValue).contains(actualValue);
            case "CONTAINS":
                return actualValue.toString().contains(expectedValue.toString());
            case "STARTS WITH":
                return actualValue.toString().startsWith(expectedValue.toString());
            case "ENDS WITH":
                return actualValue.toString().endsWith(expectedValue.toString());
            default:
                return actualValue.equals(expectedValue);
        }
    }

    private int compareValues(Object v1, Object v2) {
        if (v1 instanceof Comparable && v2 instanceof Comparable) {
            if (v1 instanceof Number && v2 instanceof Number) {
                return Double.compare(((Number) v1).doubleValue(), ((Number) v2).doubleValue());
            }
            return ((Comparable) v1).compareTo(v2);
        }
        return v1.toString().compareTo(v2.toString());
    }

    private boolean isUnaryOperator(String operator) {
        return "IS NULL".equals(operator) || "IS NOT NULL".equals(operator);
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0d;
        }
        return value != null;
    }
}
