package com.federatedquery.ast;

import java.util.ArrayList;
import java.util.List;

public class Pattern implements AstNode {
    private List<PatternPart> patternParts = new ArrayList<>();
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public List<PatternPart> getPatternParts() {
        return patternParts;
    }
    
    public void setPatternParts(List<PatternPart> patternParts) {
        this.patternParts = patternParts;
    }
    
    public void addPatternPart(PatternPart patternPart) {
        this.patternParts.add(patternPart);
    }
    
    @Override
    public String toCypher() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < patternParts.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(patternParts.get(i).toCypher());
        }
        return sb.toString();
    }
    
    public static class PatternPart implements AstNode {
        private String variable;
        private PatternElement patternElement;
        
        @Override
        public <T> T accept(AstVisitor<T> visitor) {
            return null;
        }
        
        public String getVariable() {
            return variable;
        }
        
        public void setVariable(String variable) {
            this.variable = variable;
        }
        
        public PatternElement getPatternElement() {
            return patternElement;
        }
        
        public void setPatternElement(PatternElement patternElement) {
            this.patternElement = patternElement;
        }
        
        @Override
        public String toCypher() {
            if (variable != null) {
                return variable + " = " + patternElement.toCypher();
            }
            return patternElement.toCypher();
        }
    }
    
    public static class PatternElement implements AstNode {
        private NodePattern nodePattern;
        private List<PatternElementChain> chains = new ArrayList<>();
        
        @Override
        public <T> T accept(AstVisitor<T> visitor) {
            return null;
        }
        
        public NodePattern getNodePattern() {
            return nodePattern;
        }
        
        public void setNodePattern(NodePattern nodePattern) {
            this.nodePattern = nodePattern;
        }
        
        public List<PatternElementChain> getChains() {
            return chains;
        }
        
        public void setChains(List<PatternElementChain> chains) {
            this.chains = chains;
        }
        
        public void addChain(PatternElementChain chain) {
            this.chains.add(chain);
        }
        
        @Override
        public String toCypher() {
            StringBuilder sb = new StringBuilder();
            sb.append(nodePattern.toCypher());
            for (PatternElementChain chain : chains) {
                sb.append(chain.toCypher());
            }
            return sb.toString();
        }
    }
    
    public static class PatternElementChain implements AstNode {
        private RelationshipPattern relationshipPattern;
        private NodePattern nodePattern;
        
        @Override
        public <T> T accept(AstVisitor<T> visitor) {
            return null;
        }
        
        public RelationshipPattern getRelationshipPattern() {
            return relationshipPattern;
        }
        
        public void setRelationshipPattern(RelationshipPattern relationshipPattern) {
            this.relationshipPattern = relationshipPattern;
        }
        
        public NodePattern getNodePattern() {
            return nodePattern;
        }
        
        public void setNodePattern(NodePattern nodePattern) {
            this.nodePattern = nodePattern;
        }
        
        @Override
        public String toCypher() {
            return relationshipPattern.toCypher() + nodePattern.toCypher();
        }
    }
}
