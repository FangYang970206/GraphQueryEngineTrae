package com.fangyang.federatedquery.ast;

public class WhereClause implements AstNode {
    private Expression expression;
    
    public WhereClause() {}
    
    public WhereClause(Expression expression) {
        this.expression = expression;
    }
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public Expression getExpression() {
        return expression;
    }
    
    public void setExpression(Expression expression) {
        this.expression = expression;
    }
    
    @Override
    public String toCypher() {
        return "WHERE " + (expression != null ? expression.toCypher() : "");
    }
}
