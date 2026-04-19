package com.fangyang.federatedquery.ast;

public class Variable extends Expression {
    private String name;
    
    public Variable() {}
    
    public Variable(String name) {
        this.name = name;
    }
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    @Override
    public String toCypher() {
        return name;
    }
}
