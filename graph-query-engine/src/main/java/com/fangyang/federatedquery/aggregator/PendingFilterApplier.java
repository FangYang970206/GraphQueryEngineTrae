package com.fangyang.federatedquery.aggregator;

import com.fangyang.federatedquery.model.GraphEntity;
import com.fangyang.federatedquery.plan.GlobalContext;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class PendingFilterApplier {

    public List<Map<String, Object>> apply(List<Map<String, Object>> results, List<GlobalContext.WhereCondition> pendingFilters) {
        if (pendingFilters == null || pendingFilters.isEmpty()) {
            return results;
        }

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> row : results) {
            if (matchesAllConditions(row, pendingFilters)) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    private boolean matchesAllConditions(Map<String, Object> row, List<GlobalContext.WhereCondition> conditions) {
        for (GlobalContext.WhereCondition condition : conditions) {
            if (!matchesCondition(row, condition)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesCondition(Map<String, Object> row, GlobalContext.WhereCondition condition) {
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
        if (entityObj instanceof Map) {
            return ((Map<String, Object>) entityObj).get(property);
        } else if (entityObj instanceof GraphEntity) {
            return ((GraphEntity) entityObj).getProperty(property);
        }
        return null;
    }

    private boolean evaluateCondition(Object actualValue, String operator, Object expectedValue) {
        if (actualValue == null && expectedValue == null) {
            return "=".equals(operator) || "IS".equals(operator);
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
            case "IS NULL":
                return actualValue == null;
            case "IS NOT NULL":
                return actualValue != null;
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
}
