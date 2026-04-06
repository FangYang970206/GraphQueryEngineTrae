package com.federatedquery.ast;

import java.util.ArrayList;
import java.util.List;

public class UsingSnapshot implements AstNode {
    private String snapshotName;
    private Expression version;
    private List<String> labels = new ArrayList<>();
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public String getSnapshotName() {
        return snapshotName;
    }
    
    public void setSnapshotName(String snapshotName) {
        this.snapshotName = snapshotName;
    }
    
    public Expression getVersion() {
        return version;
    }
    
    public void setVersion(Expression version) {
        this.version = version;
    }
    
    public List<String> getLabels() {
        return labels;
    }
    
    public void setLabels(List<String> labels) {
        this.labels = labels;
    }
    
    public void addLabel(String label) {
        this.labels.add(label);
    }
    
    @Override
    public String toCypher() {
        StringBuilder sb = new StringBuilder("USING SNAPSHOT('");
        sb.append(snapshotName).append("', ");
        if (version != null) {
            sb.append(version.toCypher());
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
