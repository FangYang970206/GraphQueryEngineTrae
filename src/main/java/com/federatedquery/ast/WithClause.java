package com.federatedquery.ast;

import java.util.ArrayList;
import java.util.List;

public class WithClause implements AstNode {
    private boolean distinct = false;
    private List<ReturnClause.ReturnItem> returnItems = new ArrayList<>();
    private WhereClause whereClause;
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
    
    public List<ReturnClause.ReturnItem> getReturnItems() {
        return returnItems;
    }
    
    public void setReturnItems(List<ReturnClause.ReturnItem> returnItems) {
        this.returnItems = returnItems;
    }
    
    public WhereClause getWhereClause() {
        return whereClause;
    }
    
    public void setWhereClause(WhereClause whereClause) {
        this.whereClause = whereClause;
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
        sb.append("WITH ");
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
        if (whereClause != null) {
            sb.append(" ").append(whereClause.toCypher());
        }
        return sb.toString();
    }
}
