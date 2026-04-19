package com.fangyang.federatedquery.aggregator;

import com.fangyang.federatedquery.GraphEntity;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
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
                    Object value = entry.getValue();
                    if (value instanceof GraphEntity) {
                        sb.append(((GraphEntity) value).getId());
                    } else if (value instanceof List) {
                        sb.append(computeListHash((List<?>) value));
                    } else {
                        sb.append(value);
                    }
                });
        
        return sb.toString();
    }
    
    private String computeListHash(List<?> list) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof Map) {
                sb.append(computeMapHash((Map<String, Object>) item));
            } else if (item instanceof List) {
                sb.append(computeListHash((List<?>) item));
            } else if (item != null) {
                sb.append(item);
            }
            if (i < list.size() - 1) {
                sb.append(",");
            }
        }
        
        sb.append("]");
        return sb.toString();
    }
    
    private String computeMapHash(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        
        List<String> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys);
        
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            sb.append(key).append("=");
            Object value = map.get(key);
            if (value instanceof Map) {
                sb.append(computeMapHash((Map<String, Object>) value));
            } else if (value instanceof List) {
                sb.append(computeListHash((List<?>) value));
            } else if (value != null) {
                sb.append(value);
            }
            if (i < keys.size() - 1) {
                sb.append(",");
            }
        }
        
        sb.append("}");
        return sb.toString();
    }
}
