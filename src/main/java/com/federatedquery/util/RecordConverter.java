package com.federatedquery.util;

import com.federatedquery.adapter.GraphEntity;

import java.util.*;
import java.util.stream.Collectors;

public class RecordConverter {
    
    public static List<Map<String, Object>> convertToRecordMaps(List<Map<String, Object>> internalResults) {
        if (internalResults == null) {
            return new ArrayList<>();
        }
        
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> row : internalResults) {
            Map<String, Object> recordMap = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                recordMap.put(key, convertValue(value));
            }
            records.add(recordMap);
        }
        return records;
    }
    
    private static Object convertValue(Object value) {
        if (value == null) {
            return null;
        }
        
        if (value instanceof GraphEntity) {
            return convertGraphEntity((GraphEntity) value);
        }
        
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            if (isNodeMap(map)) {
                return convertMapToNode(map);
            }
            return map;
        }
        
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<?> list = (List<?>) value;
            return list.stream()
                    .map(RecordConverter::convertValue)
                    .collect(Collectors.toList());
        }
        
        return value;
    }
    
    private static boolean isNodeMap(Map<String, Object> map) {
        return map.containsKey("label") || map.containsKey("id");
    }
    
    private static Map<String, Object> convertGraphEntity(GraphEntity entity) {
        Map<String, Object> nodeMap = new LinkedHashMap<>();
        
        if (entity.getType() == GraphEntity.EntityType.NODE) {
            nodeMap.put("_id", entity.getId());
            nodeMap.put("_labels", Collections.singletonList(entity.getLabel()));
            
            Map<String, Object> properties = new LinkedHashMap<>();
            if (entity.getProperties() != null) {
                properties.putAll(entity.getProperties());
            }
            nodeMap.put("_properties", properties);
        } else if (entity.getType() == GraphEntity.EntityType.EDGE) {
            nodeMap.put("_id", entity.getId());
            nodeMap.put("_type", entity.getLabel());
            nodeMap.put("_startId", entity.getStartNodeId());
            nodeMap.put("_endId", entity.getEndNodeId());
            
            Map<String, Object> properties = new LinkedHashMap<>();
            if (entity.getProperties() != null) {
                properties.putAll(entity.getProperties());
            }
            nodeMap.put("_properties", properties);
        }
        
        return nodeMap;
    }
    
    private static Map<String, Object> convertMapToNode(Map<String, Object> map) {
        Map<String, Object> nodeMap = new LinkedHashMap<>();
        
        String id = map.containsKey("id") ? String.valueOf(map.get("id")) : UUID.randomUUID().toString();
        nodeMap.put("_id", id);
        
        String label = map.containsKey("label") ? String.valueOf(map.get("label")) : "Unknown";
        nodeMap.put("_labels", Collections.singletonList(label));
        
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            if (!"id".equals(key) && !"label".equals(key)) {
                properties.put(key, entry.getValue());
            }
        }
        nodeMap.put("_properties", properties);
        
        return nodeMap;
    }
    
    public static Map<String, Object> convertPathToMap(List<Map<String, Object>> pathElements) {
        Map<String, Object> pathMap = new LinkedHashMap<>();
        
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> relationships = new ArrayList<>();
        
        for (Map<String, Object> element : pathElements) {
            String type = (String) element.get("type");
            if ("node".equals(type)) {
                nodes.add(element);
            } else if ("edge".equals(type)) {
                relationships.add(element);
            }
        }
        
        pathMap.put("nodes", nodes);
        pathMap.put("relationships", relationships);
        pathMap.put("length", relationships.size());
        
        return pathMap;
    }
}
