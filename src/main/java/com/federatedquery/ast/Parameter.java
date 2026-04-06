package com.federatedquery.ast;

public class Parameter extends Expression {
    private String name;
    private Integer index;
    
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
    
    public Integer getIndex() {
        return index;
    }
    
    public void setIndex(Integer index) {
        this.index = index;
    }
    
    @Override
    public String toCypher() {
        if (name != null) {
            return "$" + name;
        }
        return "$" + index;
    }
}
