package com.fangyang.federatedquery.ast;

public class UnionClause implements AstNode {
    private boolean all = false;
    
    public UnionClause() {}
    
    public UnionClause(boolean all) {
        this.all = all;
    }
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public boolean isAll() {
        return all;
    }
    
    public void setAll(boolean all) {
        this.all = all;
    }
    
    @Override
    public String toCypher() {
        return "UNION" + (all ? " ALL" : "");
    }
}
