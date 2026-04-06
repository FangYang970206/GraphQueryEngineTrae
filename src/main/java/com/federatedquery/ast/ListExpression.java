package com.federatedquery.ast;

import java.util.ArrayList;
import java.util.List;

public class ListExpression extends Expression {
    private List<Expression> elements = new ArrayList<>();
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public List<Expression> getElements() {
        return elements;
    }
    
    public void setElements(List<Expression> elements) {
        this.elements = elements;
    }
    
    public void addElement(Expression element) {
        this.elements.add(element);
    }
    
    @Override
    public String toCypher() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(elements.get(i).toCypher());
        }
        sb.append("]");
        return sb.toString();
    }
}
