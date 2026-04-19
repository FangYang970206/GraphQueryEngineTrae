package com.fangyang.federatedquery.plan;

import com.fangyang.federatedquery.ast.Expression;
import com.fangyang.federatedquery.ast.ProjectBy;
import com.fangyang.federatedquery.ast.UsingSnapshot;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class GlobalContext {
    private Map<String, Object> bindings = new HashMap<>();
    private List<WhereCondition> pendingFilters = new ArrayList<>();
    private OrderSpec globalOrder;
    private LimitSpec globalLimit;
    private ProjectBy projectBy;
    private UsingSnapshot usingSnapshot;
    private List<String> withVariables = new ArrayList<>();
    private int implicitLimit = 5000;
    private boolean hasImplicitLimit = false;
    
    public void addBinding(String variable, Object value) {
        this.bindings.put(variable, value);
    }
    
    public void addPendingFilter(WhereCondition filter) {
        this.pendingFilters.add(filter);
    }
    
    @Data
    public static class WhereCondition {
        private String variable;
        private String property;
        private String operator;
        private Object value;
        private Expression originalExpression;
        private boolean isVirtual = false;
    }
    
    @Data
    public static class OrderSpec {
        private List<OrderItem> items = new ArrayList<>();
        
        public void addItem(OrderItem item) {
            this.items.add(item);
        }
    }
    
    @Data
    public static class OrderItem {
        private String variable;
        private String property;
        private boolean descending = false;
    }
    
    @Data
    public static class LimitSpec {
        private int skip = 0;
        private int limit = -1;
    }
}
