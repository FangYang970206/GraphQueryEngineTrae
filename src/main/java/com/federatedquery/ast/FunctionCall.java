package com.federatedquery.ast;

import java.util.ArrayList;
import java.util.List;

public class FunctionCall extends Expression {
    private String functionName;
    private List<Expression> arguments = new ArrayList<>();
    private boolean distinct = false;
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public String getFunctionName() {
        return functionName;
    }
    
    public void setFunctionName(String functionName) {
        this.functionName = functionName;
    }
    
    public List<Expression> getArguments() {
        return arguments;
    }
    
    public void setArguments(List<Expression> arguments) {
        this.arguments = arguments;
    }
    
    public void addArgument(Expression argument) {
        this.arguments.add(argument);
    }
    
    public boolean isDistinct() {
        return distinct;
    }
    
    public void setDistinct(boolean distinct) {
        this.distinct = distinct;
    }
    
    @Override
    public String toCypher() {
        StringBuilder sb = new StringBuilder(functionName);
        sb.append("(");
        if (distinct) {
            sb.append("DISTINCT ");
        }
        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(arguments.get(i).toCypher());
        }
        sb.append(")");
        return sb.toString();
    }
}
