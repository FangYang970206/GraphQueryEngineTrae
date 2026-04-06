package com.federatedquery.ast;

import java.util.ArrayList;
import java.util.List;

public class MatchClause implements AstNode {
    private boolean optional = false;
    private Pattern pattern;
    private WhereClause whereClause;
    private List<Hint> hints = new ArrayList<>();
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public boolean isOptional() {
        return optional;
    }
    
    public void setOptional(boolean optional) {
        this.optional = optional;
    }
    
    public Pattern getPattern() {
        return pattern;
    }
    
    public void setPattern(Pattern pattern) {
        this.pattern = pattern;
    }
    
    public WhereClause getWhereClause() {
        return whereClause;
    }
    
    public void setWhereClause(WhereClause whereClause) {
        this.whereClause = whereClause;
    }
    
    public List<Hint> getHints() {
        return hints;
    }
    
    public void setHints(List<Hint> hints) {
        this.hints = hints;
    }
    
    @Override
    public String toCypher() {
        StringBuilder sb = new StringBuilder();
        sb.append(optional ? "OPTIONAL " : "");
        sb.append("MATCH ");
        sb.append(pattern.toCypher());
        if (!hints.isEmpty()) {
            for (Hint hint : hints) {
                sb.append(" ").append(hint.toCypher());
            }
        }
        if (whereClause != null) {
            sb.append(" ").append(whereClause.toCypher());
        }
        return sb.toString();
    }
    
    public static class Hint implements AstNode {
        private String type;
        private String variable;
        
        @Override
        public <T> T accept(AstVisitor<T> visitor) {
            return null;
        }
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public String getVariable() {
            return variable;
        }
        
        public void setVariable(String variable) {
            this.variable = variable;
        }
        
        @Override
        public String toCypher() {
            return "USING " + type + " ON " + variable;
        }
    }
}
