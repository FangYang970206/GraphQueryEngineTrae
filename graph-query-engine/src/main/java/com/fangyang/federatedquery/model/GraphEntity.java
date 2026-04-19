package com.fangyang.federatedquery.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.HashMap;
import java.util.Map;

@Data
@Accessors(chain = true)
public class GraphEntity {
    private String id;
    private EntityType type;
    private Map<String, Object> properties = new HashMap<>();
    private String label;
    private String startNodeId;
    private String endNodeId;
    private String variableName;
    private String sourceEdgeType;
    
    public Object getProperty(String key) {
        return properties.get(key);
    }
    
    public GraphEntity setProperty(String key, Object value) {
        this.properties.put(key, value);
        return this;
    }
    
    public static GraphEntity node(String id, String label) {
        return new GraphEntity()
                .setId(id)
                .setType(EntityType.NODE)
                .setLabel(label);
    }
    
    public static GraphEntity edge(String id, String type, String startId, String endId) {
        return new GraphEntity()
                .setId(id)
                .setType(EntityType.EDGE)
                .setLabel(type)
                .setStartNodeId(startId)
                .setEndNodeId(endId);
    }
    
    public enum EntityType {
        NODE, EDGE
    }
}
