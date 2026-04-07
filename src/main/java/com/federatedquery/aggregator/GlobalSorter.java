package com.federatedquery.aggregator;

import com.federatedquery.adapter.GraphEntity;
import com.federatedquery.plan.GlobalContext;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class GlobalSorter {
    private int defaultLimit = 5000;
    
    public PagedResult sortAndPaginate(List<PathBuilder.Path> paths, 
                                       GlobalContext.OrderSpec order,
                                       GlobalContext.LimitSpec limit) {
        PagedResult result = new PagedResult();
        
        List<PathBuilder.Path> sorted = sort(paths, order);
        
        int skip = 0;
        int take = defaultLimit;
        
        if (limit != null) {
            skip = limit.getSkip();
            take = limit.getLimit() > 0 ? limit.getLimit() : defaultLimit;
        }
        
        List<PathBuilder.Path> paged = applyPagination(sorted, skip, take);
        
        result.setPaths(paged);
        result.setTotalCount(paths.size());
        result.setSkip(skip);
        result.setLimit(take);
        
        return result;
    }
    
    private List<PathBuilder.Path> sort(List<PathBuilder.Path> paths, GlobalContext.OrderSpec order) {
        if (order == null || order.getItems().isEmpty()) {
            return new ArrayList<>(paths);
        }
        
        return paths.stream()
                .sorted((p1, p2) -> comparePaths(p1, p2, order))
                .collect(Collectors.toList());
    }
    
    private int comparePaths(PathBuilder.Path p1, PathBuilder.Path p2, GlobalContext.OrderSpec order) {
        for (GlobalContext.OrderItem item : order.getItems()) {
            int cmp = compareByItem(p1, p2, item);
            if (cmp != 0) {
                return item.isDescending() ? -cmp : cmp;
            }
        }
        return 0;
    }
    
    private int compareByItem(PathBuilder.Path p1, PathBuilder.Path p2, GlobalContext.OrderItem item) {
        Object v1 = extractValue(p1, item);
        Object v2 = extractValue(p2, item);
        
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return 1;
        if (v2 == null) return -1;
        
        if (v1 instanceof Comparable && v2 instanceof Comparable) {
            return ((Comparable) v1).compareTo(v2);
        }
        
        return v1.toString().compareTo(v2.toString());
    }
    
    private Object extractValue(PathBuilder.Path path, GlobalContext.OrderItem item) {
        String variable = item.getVariable();
        String property = item.getProperty();
        
        for (PathBuilder.PathElement element : path.getElements()) {
            GraphEntity entity = element.getEntity();
            if (entity.getType() == GraphEntity.EntityType.NODE) {
                if (property != null) {
                    return entity.getProperty(property);
                }
            }
        }
        
        return null;
    }
    
    private List<PathBuilder.Path> applyPagination(List<PathBuilder.Path> paths, int skip, int limit) {
        if (skip >= paths.size()) {
            return new ArrayList<>();
        }
        
        int end = Math.min(skip + limit, paths.size());
        return new ArrayList<>(paths.subList(skip, end));
    }
    
    public static class PagedResult {
        private List<PathBuilder.Path> paths = new ArrayList<>();
        private int totalCount;
        private int skip;
        private int limit;
        
        public List<PathBuilder.Path> getPaths() {
            return paths;
        }
        
        public void setPaths(List<PathBuilder.Path> paths) {
            this.paths = paths;
        }
        
        public int getTotalCount() {
            return totalCount;
        }
        
        public void setTotalCount(int totalCount) {
            this.totalCount = totalCount;
        }
        
        public int getSkip() {
            return skip;
        }
        
        public void setSkip(int skip) {
            this.skip = skip;
        }
        
        public int getLimit() {
            return limit;
        }
        
        public void setLimit(int limit) {
            this.limit = limit;
        }
    }
}
