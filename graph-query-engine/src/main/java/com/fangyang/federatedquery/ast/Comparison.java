package com.fangyang.federatedquery.ast;

public class Comparison extends Expression {
    private Expression left;
    private String operator;
    private Expression right;
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public Expression getLeft() {
        return left;
    }
    
    public void setLeft(Expression left) {
        this.left = left;
    }
    
    public String getOperator() {
        return operator;
    }
    
    public void setOperator(String operator) {
        this.operator = operator;
    }
    
    public Expression getRight() {
        return right;
    }
    
    public void setRight(Expression right) {
        this.right = right;
    }
    
    @Override
    public String toCypher() {
        return left.toCypher() + " " + operator + " " + right.toCypher();
    }
}
