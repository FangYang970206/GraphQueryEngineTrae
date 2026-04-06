package com.federatedquery.plan;

import com.federatedquery.ast.Expression;
import com.federatedquery.ast.OrderByClause;
import com.federatedquery.ast.ProjectBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GlobalContext {
    private Map<String, Object> bindings = new HashMap<>();
    private List<WhereCondition> pendingFilters = new ArrayList<>();
    private OrderSpec globalOrder;
    private LimitSpec globalLimit;
    private ProjectBy projectBy;
    private int implicitLimit = 5000;
    private boolean hasImplicitLimit = false;
    
    public Map<String, Object> getBindings() {
        return bindings;
    }
    
    public void setBindings(Map<String, Object> bindings) {
        this.bindings = bindings;
    }
    
    public void addBinding(String variable, Object value) {
        this.bindings.put(variable, value);
    }
    
    public List<WhereCondition> getPendingFilters() {
        return pendingFilters;
    }
    
    public void setPendingFilters(List<WhereCondition> pendingFilters) {
        this.pendingFilters = pendingFilters;
    }
    
    public void addPendingFilter(WhereCondition filter) {
        this.pendingFilters.add(filter);
    }
    
    public OrderSpec getGlobalOrder() {
        return globalOrder;
    }
    
    public void setGlobalOrder(OrderSpec globalOrder) {
        this.globalOrder = globalOrder;
    }
    
    public LimitSpec getGlobalLimit() {
        return globalLimit;
    }
    
    public void setGlobalLimit(LimitSpec globalLimit) {
        this.globalLimit = globalLimit;
    }
    
    public ProjectBy getProjectBy() {
        return projectBy;
    }
    
    public void setProjectBy(ProjectBy projectBy) {
        this.projectBy = projectBy;
    }
    
    public int getImplicitLimit() {
        return implicitLimit;
    }
    
    public void setImplicitLimit(int implicitLimit) {
        this.implicitLimit = implicitLimit;
    }
    
    public boolean hasImplicitLimit() {
        return hasImplicitLimit;
    }
    
    public void setHasImplicitLimit(boolean hasImplicitLimit) {
        this.hasImplicitLimit = hasImplicitLimit;
    }
    
    public static class WhereCondition {
        private String variable;
        private String property;
        private String operator;
        private Object value;
        private Expression originalExpression;
        private boolean isVirtual = false;
        
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
        
        public boolean isVirtual() {
            return isVirtual;
        }
        
        public void setVirtual(boolean virtual) {
            isVirtual = virtual;
        }
    }
    
    public static class OrderSpec {
        private List<OrderItem> items = new ArrayList<>();
        
        public List<OrderItem> getItems() {
            return items;
        }
        
        public void setItems(List<OrderItem> items) {
            this.items = items;
        }
        
        public void addItem(OrderItem item) {
            this.items.add(item);
        }
    }
    
    public static class OrderItem {
        private String variable;
        private String property;
        private boolean descending = false;
        
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
        
        public boolean isDescending() {
            return descending;
        }
        
        public void setDescending(boolean descending) {
            this.descending = descending;
        }
    }
    
    public static class LimitSpec {
        private int limit;
        private int skip = 0;
        
        public int getLimit() {
            return limit;
        }
        
        public void setLimit(int limit) {
            this.limit = limit;
        }
        
        public int getSkip() {
            return skip;
        }
        
        public void setSkip(int skip) {
            this.skip = skip;
        }
    }
}
