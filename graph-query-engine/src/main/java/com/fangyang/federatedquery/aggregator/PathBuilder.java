package com.fangyang.federatedquery.aggregator;

import com.fangyang.federatedquery.GraphEntity;
import com.fangyang.federatedquery.ast.Pattern;

import java.util.*;

public class PathBuilder {
    
    public List<Path> buildPaths(List<GraphEntity> entities, Pattern pattern) {
        List<Path> paths = new ArrayList<>();
        
        Map<String, GraphEntity> nodes = new HashMap<>();
        List<GraphEntity> edges = new ArrayList<>();
        
        for (GraphEntity entity : entities) {
            if (entity.getType() == GraphEntity.EntityType.NODE) {
                nodes.put(entity.getId(), entity);
            } else {
                edges.add(entity);
            }
        }
        
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
        
        if (edges.isEmpty() && !nodes.isEmpty()) {
            for (GraphEntity node : nodes.values()) {
                Path path = new Path();
                path.addElement(node);
                paths.add(path);
            }
        }
        
        return paths;
    }
    
    public Path buildSinglePath(List<GraphEntity> entities) {
        Path path = new Path();
        
        for (GraphEntity entity : entities) {
            path.addElement(entity);
        }
        
        return path;
    }
    
    public List<Path> mergePaths(List<Path> left, List<Path> right) {
        List<Path> merged = new ArrayList<>(left);
        merged.addAll(right);
        return merged;
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
                sb.append(element.getEntity().getId()).append("-");
            }
            return sb.toString();
        }
        
        public GraphEntity getStartNode() {
            if (elements.isEmpty()) return null;
            return elements.get(0).getEntity();
        }
        
        public GraphEntity getEndNode() {
            if (elements.isEmpty()) return null;
            return elements.get(elements.size() - 1).getEntity();
        }
        
        public List<GraphEntity> getNodes() {
            List<GraphEntity> nodes = new ArrayList<>();
            for (PathElement element : elements) {
                if (element.getEntity().getType() == GraphEntity.EntityType.NODE) {
                    nodes.add(element.getEntity());
                }
            }
            return nodes;
        }
        
        public List<GraphEntity> getRelationships() {
            List<GraphEntity> rels = new ArrayList<>();
            for (PathElement element : elements) {
                if (element.getEntity().getType() == GraphEntity.EntityType.EDGE) {
                    rels.add(element.getEntity());
                }
            }
            return rels;
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
