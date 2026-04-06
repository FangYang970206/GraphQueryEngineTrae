package com.federatedquery.ast;

import java.util.HashMap;
import java.util.Map;

public class MapExpression extends Expression {
    private Map<String, Expression> entries = new HashMap<>();
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public Map<String, Expression> getEntries() {
        return entries;
    }
    
    public void setEntries(Map<String, Expression> entries) {
        this.entries = entries;
    }
    
    public void put(String key, Expression value) {
        this.entries.put(key, value);
    }
    
    @Override
    public String toCypher() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Expression> entry : entries.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append(": ").append(entry.getValue().toCypher());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
