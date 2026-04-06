package com.federatedquery.ast;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class UsingSnapshot implements AstNode {
    private String snapshotName;
    private Expression snapshotTime;
    private List<String> labels = new ArrayList<>();
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public void addLabel(String label) {
        this.labels.add(label);
    }
    
    public Long getSnapshotTimeAsUnixTimestamp() {
        if (snapshotTime == null) {
            return null;
        }
        if (snapshotTime instanceof Literal) {
            Object value = ((Literal) snapshotTime).getValue();
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        }
        return null;
    }
    
    @Override
    public String toCypher() {
        StringBuilder sb = new StringBuilder("USING SNAPSHOT('");
        sb.append(snapshotName).append("', ");
        if (snapshotTime != null) {
            sb.append(snapshotTime.toCypher());
        }
        sb.append(") ON [");
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(labels.get(i));
        }
        sb.append("]");
        return sb.toString();
    }
}
