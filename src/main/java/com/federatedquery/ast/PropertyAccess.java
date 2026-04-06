package com.federatedquery.ast;

public class PropertyAccess extends Expression {
    private Expression target;
    private String propertyName;
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public Expression getTarget() {
        return target;
    }
    
    public void setTarget(Expression target) {
        this.target = target;
    }
    
    public String getPropertyName() {
        return propertyName;
    }
    
    public void setPropertyName(String propertyName) {
        this.propertyName = propertyName;
    }
    
    @Override
    public String toCypher() {
        return target.toCypher() + "." + propertyName;
    }
}
