package com.fangyang.federatedquery.aggregator;

import com.fangyang.federatedquery.model.GraphEntity;
import com.fangyang.federatedquery.ast.ProjectBy;
import com.fangyang.federatedquery.plan.GlobalContext;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GlobalSorter {

    public static class PagedResult {
        private final List<PathBuilder.Path> paths;
        private final int totalCount;
        private final int skip;
        private final int limit;

        public PagedResult(List<PathBuilder.Path> paths, int totalCount, int skip, int limit) {
            this.paths = paths;
            this.totalCount = totalCount;
            this.skip = skip;
            this.limit = limit;
        }

        public List<PathBuilder.Path> getPaths() { return paths; }
        public int getTotalCount() { return totalCount; }
        public int getSkip() { return skip; }
        public int getLimit() { return limit; }
    }

    private static final int DEFAULT_LIMIT = Integer.MAX_VALUE;

    public PagedResult sortAndPaginate(List<PathBuilder.Path> paths, GlobalContext.OrderSpec orderSpec, GlobalContext.LimitSpec limitSpec) {
        if (paths == null || paths.isEmpty()) {
            return new PagedResult(new ArrayList<>(), 0, limitSpec != null ? limitSpec.getSkip() : 0, limitSpec != null ? limitSpec.getLimit() : 0);
        }

        int totalCount = paths.size();
        int skip = limitSpec != null ? limitSpec.getSkip() : 0;
        int limit = limitSpec != null ? limitSpec.getLimit() : totalCount;

        List<PathBuilder.Path> resultPaths = new ArrayList<>(paths);

        if (orderSpec != null && !orderSpec.getItems().isEmpty()) {
            resultPaths.sort((p1, p2) -> {
                for (GlobalContext.OrderItem item : orderSpec.getItems()) {
                    int cmp = comparePathsByOrderItem(p1, p2, item);
                    if (cmp != 0) {
                        return item.isDescending() ? -cmp : cmp;
                    }
                }
                return 0;
            });
        }

        if (skip >= resultPaths.size()) {
            return new PagedResult(new ArrayList<>(), totalCount, skip, limit);
        }

        int end = Math.min(skip + limit, resultPaths.size());
        return new PagedResult(new ArrayList<>(resultPaths.subList(skip, end)), totalCount, skip, limit);
    }

    public List<PathBuilder.Path> applyPathSortAndPagination(List<PathBuilder.Path> paths, ProjectBy projectBy, GlobalContext context) {
        if (paths == null || paths.isEmpty()) {
            return paths;
        }
        List<PathBuilder.Path> projected = applyPathProjection(paths, projectBy);
        List<PathBuilder.Path> sorted = applyPathSorting(projected, context.getGlobalOrder());
        return applyPathPagination(sorted, context.getGlobalLimit());
    }

    private int comparePathsByOrderItem(PathBuilder.Path p1, PathBuilder.Path p2, GlobalContext.OrderItem item) {
        Object v1 = extractValueFromPath(p1, item);
        Object v2 = extractValueFromPath(p2, item);

        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return 1;
        if (v2 == null) return -1;

        if (v1 instanceof Comparable && v2 instanceof Comparable) {
            return ((Comparable) v1).compareTo(v2);
        }

        return v1.toString().compareTo(v2.toString());
    }

    private Object extractValueFromPath(PathBuilder.Path path, GlobalContext.OrderItem item) {
        String variable = item.getVariable();
        String property = item.getProperty();

        for (PathBuilder.PathElement element : path.getElements()) {
            GraphEntity entity = element.getEntity();
            if (entity.getVariableName() != null && entity.getVariableName().equals(variable)) {
                if (property != null) {
                    return entity.getProperty(property);
                }
                return entity.getId();
            }
        }

        if (!path.getElements().isEmpty()) {
            GraphEntity entity = path.getElements().get(0).getEntity();
            if (property != null) {
                return entity.getProperty(property);
            }
            return entity.getId();
        }

        return null;
    }

    private List<PathBuilder.Path> applyPathProjection(List<PathBuilder.Path> paths, ProjectBy projectBy) {
        if (projectBy == null || projectBy.getProjections().isEmpty()) {
            return paths;
        }
        return paths;
    }

    private List<PathBuilder.Path> applyPathSorting(List<PathBuilder.Path> paths, GlobalContext.OrderSpec orderSpec) {
        if (orderSpec == null || orderSpec.getItems().isEmpty()) {
            return paths;
        }

        List<PathBuilder.Path> sorted = new ArrayList<>(paths);
        sorted.sort((p1, p2) -> {
            for (GlobalContext.OrderItem item : orderSpec.getItems()) {
                int cmp = comparePathsByOrderItem(p1, p2, item);
                if (cmp != 0) {
                    return item.isDescending() ? -cmp : cmp;
                }
            }
            return 0;
        });

        return sorted;
    }

    private List<PathBuilder.Path> applyPathPagination(List<PathBuilder.Path> paths, GlobalContext.LimitSpec limitSpec) {
        if (limitSpec == null) {
            return paths;
        }

        int skip = limitSpec.getSkip();
        int limit = limitSpec.getLimit() > 0 ? limitSpec.getLimit() : paths.size();

        if (skip >= paths.size()) {
            return new ArrayList<>();
        }

        int end = Math.min(skip + limit, paths.size());
        return new ArrayList<>(paths.subList(skip, end));
    }

    public List<Map<String, Object>> applySortAndPagination(List<Map<String, Object>> results, GlobalContext context) {
        if (results == null || results.isEmpty()) {
            return results;
        }

        List<Map<String, Object>> projected = applyProjection(results, context.getProjectBy());
        List<Map<String, Object>> sorted = applySorting(projected, context.getGlobalOrder());
        List<Map<String, Object>> paged = applyPagination(sorted, context.getGlobalLimit());
        return paged;
    }

    private List<Map<String, Object>> applyProjection(List<Map<String, Object>> results, ProjectBy projectBy) {
        if (projectBy == null || projectBy.getProjections().isEmpty()) {
            return results;
        }

        Map<String, List<String>> projections = projectBy.getProjections();
        List<Map<String, Object>> projectedResults = new ArrayList<>();

        for (Map<String, Object> row : results) {
            Map<String, Object> projectedRow = new LinkedHashMap<>();

            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String varName = entry.getKey();
                Object value = entry.getValue();

                if (value instanceof Map) {
                    Map<String, Object> entityMap = (Map<String, Object>) value;
                    String label = (String) entityMap.get("label");

                    if (label != null && projections.containsKey(label)) {
                        List<String> fields = projections.get(label);
                        Map<String, Object> projectedEntity = new LinkedHashMap<>();

                        for (String field : fields) {
                            if (entityMap.containsKey(field)) {
                                projectedEntity.put(field, entityMap.get(field));
                            }
                        }

                        if (!projectedEntity.containsKey("label")) {
                            projectedEntity.put("label", label);
                        }

                        projectedRow.put(varName, projectedEntity);
                    } else {
                        projectedRow.put(varName, value);
                    }
                } else {
                    projectedRow.put(varName, value);
                }
            }

            projectedResults.add(projectedRow);
        }

        return projectedResults;
    }

    private List<Map<String, Object>> applySorting(List<Map<String, Object>> results, GlobalContext.OrderSpec orderSpec) {
        if (orderSpec == null || orderSpec.getItems().isEmpty()) {
            return results;
        }

        List<Map<String, Object>> sorted = new ArrayList<>(results);
        sorted.sort((row1, row2) -> {
            for (GlobalContext.OrderItem item : orderSpec.getItems()) {
                int cmp = compareByOrderItem(row1, row2, item);
                if (cmp != 0) {
                    return item.isDescending() ? -cmp : cmp;
                }
            }
            return 0;
        });

        return sorted;
    }

    private int compareByOrderItem(Map<String, Object> row1, Map<String, Object> row2, GlobalContext.OrderItem item) {
        Object v1 = extractValueFromRow(row1, item);
        Object v2 = extractValueFromRow(row2, item);

        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return 1;
        if (v2 == null) return -1;

        if (v1 instanceof Comparable && v2 instanceof Comparable) {
            return ((Comparable) v1).compareTo(v2);
        }

        return v1.toString().compareTo(v2.toString());
    }

    private Object extractValueFromRow(Map<String, Object> row, GlobalContext.OrderItem item) {
        String variable = item.getVariable();
        String property = item.getProperty();

        Object value = row.get(variable);
        if (value instanceof Map) {
            Map<String, Object> entityMap = (Map<String, Object>) value;
            if (property != null) {
                return entityMap.get(property);
            }
            return entityMap.get("id");
        }

        return value;
    }

    private List<Map<String, Object>> applyPagination(List<Map<String, Object>> results, GlobalContext.LimitSpec limitSpec) {
        if (limitSpec == null) {
            return results;
        }

        int skip = limitSpec.getSkip();
        int limit = limitSpec.getLimit() > 0 ? limitSpec.getLimit() : results.size();

        if (skip >= results.size()) {
            return new ArrayList<>();
        }

        int end = Math.min(skip + limit, results.size());
        return new ArrayList<>(results.subList(skip, end));
    }
}
