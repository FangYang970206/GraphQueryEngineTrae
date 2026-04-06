package com.federatedquery.adapter;

import java.util.HashMap;
import java.util.Map;

public class GraphEntity {
    private String id;
    private EntityType type;
    private Map<String, Object> properties = new HashMap<>();
    private String label;
    private String startNodeId;
    private String endNodeId;
    private String variableName;
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public EntityType getType() {
        return type;
    }
    
    public void setType(EntityType type) {
        this.type = type;
    }
    
    public Map<String, Object> getProperties() {
        return properties;
    }
    
    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }
    
    public Object getProperty(String key) {
        return properties.get(key);
    }
    
    public void setProperty(String key, Object value) {
        this.properties.put(key, value);
    }
    
    public String getLabel() {
        return label;
    }
    
    public void setLabel(String label) {
        this.label = label;
    }
    
    public String getStartNodeId() {
        return startNodeId;
    }
    
    public void setStartNodeId(String startNodeId) {
        this.startNodeId = startNodeId;
    }
    
    public String getEndNodeId() {
        return endNodeId;
    }
    
    public void setEndNodeId(String endNodeId) {
        this.endNodeId = endNodeId;
    }
    
    public String getVariableName() {
        return variableName;
    }
    
    public void setVariableName(String variableName) {
        this.variableName = variableName;
    }
    
    public static GraphEntity node(String id, String label) {
        GraphEntity entity = new GraphEntity();
        entity.setId(id);
        entity.setType(EntityType.NODE);
        entity.setLabel(label);
        return entity;
    }
    
    public static GraphEntity edge(String id, String type, String startId, String endId) {
        GraphEntity entity = new GraphEntity();
        entity.setId(id);
        entity.setType(EntityType.EDGE);
        entity.setLabel(type);
        entity.setStartNodeId(startId);
        entity.setEndNodeId(endId);
        return entity;
    }
    
    public enum EntityType {
        NODE, EDGE
    }
}
