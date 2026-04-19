package com.fangyang.federatedquery.ast;

public class SkipClause implements AstNode {
    private Expression skip;
    
    public SkipClause() {}
    
    public SkipClause(Expression skip) {
        this.skip = skip;
    }
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public Expression getSkip() {
        return skip;
    }
    
    public void setSkip(Expression skip) {
        this.skip = skip;
    }
    
    public Integer getSkipValue() {
        if (skip instanceof Literal && ((Literal) skip).getValue() instanceof Number) {
            return ((Number) ((Literal) skip).getValue()).intValue();
        }
        return null;
    }
    
    @Override
    public String toCypher() {
        return "SKIP " + (skip != null ? skip.toCypher() : "");
    }
}
