package com.federatedquery.aggregator;

import com.federatedquery.adapter.GraphEntity;

import java.util.*;

public class UnionDeduplicator {
    
    public List<PathBuilder.Path> deduplicate(List<PathBuilder.Path> paths, boolean distinct) {
        if (!distinct) {
            return new ArrayList<>(paths);
        }
        
        Set<String> seen = new LinkedHashSet<>();
        List<PathBuilder.Path> result = new ArrayList<>();
        
        for (PathBuilder.Path path : paths) {
            String hash = computePathHash(path);
            if (!seen.contains(hash)) {
                seen.add(hash);
                result.add(path);
            }
        }
        
        return result;
    }
    
    public String computePathHash(PathBuilder.Path path) {
        StringBuilder sb = new StringBuilder();
        
        for (PathBuilder.PathElement element : path.getElements()) {
            sb.append(element.getEntity().getType().name())
              .append(":")
              .append(element.getEntity().getId())
              .append("->");
        }
        
        return sb.toString();
    }
    
    public List<Map<String, Object>> deduplicateRows(List<Map<String, Object>> rows, boolean distinct) {
        if (!distinct) {
            return new ArrayList<>(rows);
        }
        
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (Map<String, Object> row : rows) {
            String hash = computeRowHash(row);
            if (!seen.contains(hash)) {
                seen.add(hash);
                result.add(row);
            }
        }
        
        return result;
    }
    
    private String computeRowHash(Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        
        row.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    sb.append(entry.getKey()).append(":");
                    Object value = entry.getValue();
                    if (value instanceof GraphEntity) {
                        sb.append(((GraphEntity) value).getId());
                    } else {
                        sb.append(value);
                    }
                    sb.append(";");
                });
        
        return sb.toString();
    }
}
