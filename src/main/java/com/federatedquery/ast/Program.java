package com.federatedquery.ast;

import java.util.ArrayList;
import java.util.List;

public class Program implements AstNode {
    private Statement statement;
    private List<String> warnings = new ArrayList<>();
    
    public Program() {}
    
    public Program(Statement statement) {
        this.statement = statement;
    }
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public Statement getStatement() {
        return statement;
    }
    
    public void setStatement(Statement statement) {
        this.statement = statement;
    }
    
    public List<String> getWarnings() {
        return warnings;
    }
    
    public void addWarning(String warning) {
        this.warnings.add(warning);
    }
    
    @Override
    public String toCypher() {
        return statement != null ? statement.toCypher() : "";
    }
}
