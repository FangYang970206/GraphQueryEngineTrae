package com.federatedquery.ast;

import java.util.ArrayList;
import java.util.List;

public class ReturnClause implements AstNode {
    private boolean distinct = false;
    private List<ReturnItem> returnItems = new ArrayList<>();
    private OrderByClause orderByClause;
    private SkipClause skipClause;
    private LimitClause limitClause;
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public boolean isDistinct() {
        return distinct;
    }
    
    public void setDistinct(boolean distinct) {
        this.distinct = distinct;
    }
    
    public List<ReturnItem> getReturnItems() {
        return returnItems;
    }
    
    public void setReturnItems(List<ReturnItem> returnItems) {
        this.returnItems = returnItems;
    }
    
    public OrderByClause getOrderByClause() {
        return orderByClause;
    }
    
    public void setOrderByClause(OrderByClause orderByClause) {
        this.orderByClause = orderByClause;
    }
    
    public SkipClause getSkipClause() {
        return skipClause;
    }
    
    public void setSkipClause(SkipClause skipClause) {
        this.skipClause = skipClause;
    }
    
    public LimitClause getLimitClause() {
        return limitClause;
    }
    
    public void setLimitClause(LimitClause limitClause) {
        this.limitClause = limitClause;
    }
    
    @Override
    public String toCypher() {
        StringBuilder sb = new StringBuilder();
        sb.append("RETURN ");
        if (distinct) {
            sb.append("DISTINCT ");
        }
        for (int i = 0; i < returnItems.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(returnItems.get(i).toCypher());
        }
        if (orderByClause != null) {
            sb.append(" ").append(orderByClause.toCypher());
        }
        if (skipClause != null) {
            sb.append(" ").append(skipClause.toCypher());
        }
        if (limitClause != null) {
            sb.append(" ").append(limitClause.toCypher());
        }
        return sb.toString();
    }
    
    public static class ReturnItem implements AstNode {
        private Expression expression;
        private String alias;
        
        @Override
        public <T> T accept(AstVisitor<T> visitor) {
            return null;
        }
        
        public Expression getExpression() {
            return expression;
        }
        
        public void setExpression(Expression expression) {
            this.expression = expression;
        }
        
        public String getAlias() {
            return alias;
        }
        
        public void setAlias(String alias) {
            this.alias = alias;
        }
        
        @Override
        public String toCypher() {
            if (alias != null) {
                return expression.toCypher() + " AS " + alias;
            }
            return expression.toCypher();
        }
    }
}
