package com.federatedquery.executor;

import com.federatedquery.adapter.GraphEntity;

import java.util.*;

public class PathBuilder {
    
    public List<Path> buildPaths(List<GraphEntity> entities, Object pattern) {
        List<Path> paths = new ArrayList<>();
        
        if (entities == null || entities.isEmpty()) {
            return paths;
        }
        
        Map<String, GraphEntity> nodes = new HashMap<>();
        List<GraphEntity> edges = new ArrayList<>();
        
        for (GraphEntity entity : entities) {
            if (entity.getType() == GraphEntity.EntityType.NODE) {
                nodes.put(entity.getId(), entity);
            } else {
                edges.add(entity);
            }
        }
        
        if (edges.isEmpty()) {
            for (GraphEntity node : nodes.values()) {
                Path path = new Path();
                path.addElement(node);
                paths.add(path);
            }
        } else {
            for (GraphEntity edge : edges) {
                GraphEntity startNode = nodes.get(edge.getStartNodeId());
                GraphEntity endNode = nodes.get(edge.getEndNodeId());
                
                if (startNode != null && endNode != null) {
                    Path path = new Path();
                    path.addElement(startNode);
                    path.addElement(edge);
                    path.addElement(endNode);
                    paths.add(path);
                }
            }
        }
        
        return paths;
    }
    
    public static class Path {
        private List<PathElement> elements = new ArrayList<>();
        private Map<String, Object> properties = new HashMap<>();
        
        public List<PathElement> getElements() {
            return elements;
        }
        
        public void setElements(List<PathElement> elements) {
            this.elements = elements;
        }
        
        public void addElement(GraphEntity entity) {
            this.elements.add(new PathElement(entity));
        }
        
        public Map<String, Object> getProperties() {
            return properties;
        }
        
        public void setProperties(Map<String, Object> properties) {
            this.properties = properties;
        }
        
        public int length() {
            return elements.size();
        }
        
        public String computeHash() {
            StringBuilder sb = new StringBuilder();
            for (PathElement element : elements) {
                sb.append(element.getEntity().getType().name())
                  .append(":")
                  .append(element.getEntity().getId())
                  .append("->");
            }
            return sb.toString();
        }
    }
    
    public static class PathElement {
        private final GraphEntity entity;
        
        public PathElement(GraphEntity entity) {
            this.entity = entity;
        }
        
        public GraphEntity getEntity() {
            return entity;
        }
        
        public boolean isNode() {
            return entity.getType() == GraphEntity.EntityType.NODE;
        }
        
        public boolean isRelationship() {
            return entity.getType() == GraphEntity.EntityType.EDGE;
        }
    }
}
