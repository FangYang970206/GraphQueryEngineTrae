package com.federatedquery.ast;

public class LimitClause implements AstNode {
    private Expression limit;
    
    public LimitClause() {}
    
    public LimitClause(Expression limit) {
        this.limit = limit;
    }
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public Expression getLimit() {
        return limit;
    }
    
    public void setLimit(Expression limit) {
        this.limit = limit;
    }
    
    public Integer getLimitValue() {
        if (limit instanceof Literal && ((Literal) limit).getValue() instanceof Number) {
            return ((Number) ((Literal) limit).getValue()).intValue();
        }
        return null;
    }
    
    @Override
    public String toCypher() {
        return "LIMIT " + (limit != null ? limit.toCypher() : "");
    }
}
