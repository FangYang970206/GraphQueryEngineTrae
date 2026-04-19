package com.fangyang.federatedquery.ast;

import java.util.ArrayList;
import java.util.List;

public class UnwindClause implements AstNode {
    private Expression expression;
    private String variable;
    
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
    
    public String getVariable() {
        return variable;
    }
    
    public void setVariable(String variable) {
        this.variable = variable;
    }
    
    @Override
    public String toCypher() {
        return "UNWIND " + (expression != null ? expression.toCypher() : "") + " AS " + variable;
    }
}
