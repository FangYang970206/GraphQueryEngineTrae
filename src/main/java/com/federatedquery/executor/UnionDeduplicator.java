package com.federatedquery.executor;

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
}
