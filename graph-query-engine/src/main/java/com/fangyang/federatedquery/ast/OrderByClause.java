package com.fangyang.federatedquery.ast;

import java.util.ArrayList;
import java.util.List;

public class OrderByClause implements AstNode {
    private List<SortItem> sortItems = new ArrayList<>();
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public List<SortItem> getSortItems() {
        return sortItems;
    }
    
    public void setSortItems(List<SortItem> sortItems) {
        this.sortItems = sortItems;
    }
    
    @Override
    public String toCypher() {
        StringBuilder sb = new StringBuilder("ORDER BY ");
        for (int i = 0; i < sortItems.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(sortItems.get(i).toCypher());
        }
        return sb.toString();
    }
    
    public static class SortItem implements AstNode {
        private Expression expression;
        private SortDirection direction = SortDirection.ASC;
        
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
        
        public SortDirection getDirection() {
            return direction;
        }
        
        public void setDirection(SortDirection direction) {
            this.direction = direction;
        }
        
        @Override
        public String toCypher() {
            return expression.toCypher() + (direction == SortDirection.DESC ? " DESC" : "");
        }
    }
    
    public enum SortDirection {
        ASC, DESC
    }
}
