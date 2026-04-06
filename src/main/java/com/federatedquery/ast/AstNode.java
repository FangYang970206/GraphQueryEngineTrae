package com.federatedquery.ast;

public interface AstNode {
    <T> T accept(AstVisitor<T> visitor);
    
    default String toCypher() {
        return toString();
    }
}
