package com.federatedquery.executor;

import com.federatedquery.plan.GlobalContext;

import java.util.*;

public class GlobalSorter {
    private int defaultLimit = 5000;
    
    public PagedResult sortAndPaginate(List<PathBuilder.Path> paths, 
                                       Object order,
                                       Object limit) {
        PagedResult result = new PagedResult();
        
        int skip = 0;
        int take = defaultLimit;
        
        if (limit instanceof GlobalContext.LimitSpec) {
            GlobalContext.LimitSpec limitSpec = (GlobalContext.LimitSpec) limit;
            skip = limitSpec.getSkip();
            take = limitSpec.getLimit() > 0 ? limitSpec.getLimit() : defaultLimit;
        }
        
        List<PathBuilder.Path> paged = applyPagination(paths, skip, take);
        
        result.setPaths(paged);
        result.setTotalCount(paths.size());
        result.setSkip(skip);
        result.setLimit(take);
        
        return result;
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
