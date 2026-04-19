package com.fangyang.federatedquery.aggregator;

import com.fangyang.federatedquery.GraphEntity;
import com.fangyang.federatedquery.QueryResult;
import com.fangyang.federatedquery.executor.ExecutionResult;
import com.fangyang.metadata.MetadataQueryService;
import com.fangyang.metadata.VirtualEdgeBinding;
import com.fangyang.federatedquery.ast.*;
import com.fangyang.federatedquery.ast.UnionClause;

import java.util.*;
import java.util.function.Consumer;

public class ResultConverter {

    private final MetadataQueryService metadataQueryService;

    public ResultConverter(MetadataQueryService metadataQueryService) {
        this.metadataQueryService = metadataQueryService;
    }

    public List<Map<String, Object>> buildResults(Program ast, ExecutionResult execResult) {
        List<Map<String, Object>> results = new ArrayList<>();

        List<ReturnInfo> returnInfos = getReturnInfos(ast);
        Map<String, List<GraphEntity>> entitiesByVarName = collectEntitiesByVarName(execResult, true, true);
        List<QueryResult.ResultRow> rows = collectRows(execResult, true, false);

        if (returnInfos.isEmpty()) {
            for (List<GraphEntity> entities : entitiesByVarName.values()) {
                for (GraphEntity entity : entities) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    String varName = entity.getVariableName();
                    if (varName == null || varName.isEmpty()) {
                        varName = entity.getLabel() != null ? entity.getLabel().toLowerCase() : "entity";
                    }
                    row.put(varName, entityToTuGraphFormat(entity));
                    results.add(row);
                }
            }
            return results;
        }

        for (ReturnInfo info : returnInfos) {
            if (info.isPath) {
                List<Map<String, Object>> pathResults = buildStructuredPathResults(info.variableName, ast, execResult);
                if (pathResults.isEmpty()) {
                    pathResults = buildPathResults(info.variableName, entitiesByVarName, rows, ast);
                }
                results.addAll(pathResults);
            }
        }

        if (!results.isEmpty()) {
            return results;
        }

        List<Map<String, Object>> rowResults = buildRowResults(returnInfos, rows, entitiesByVarName);
        if (!rowResults.isEmpty()) {
            return rowResults;
        }

        return buildEntityAlignedResults(returnInfos, entitiesByVarName);
    }

    private List<Map<String, Object>> buildEntityAlignedResults(
            List<ReturnInfo> returnInfos,
            Map<String, List<GraphEntity>> entitiesByVarName) {
        List<Map<String, Object>> results = new ArrayList<>();

        String primaryVar = returnInfos.size() > 1
                ? returnInfos.get(returnInfos.size() - 1).variableName
                : returnInfos.get(0).variableName;
        List<GraphEntity> primaryEntities = entitiesByVarName.getOrDefault(primaryVar, new ArrayList<>());

        if (primaryEntities.isEmpty()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (ReturnInfo info : returnInfos) {
                List<GraphEntity> entities = entitiesByVarName.get(info.variableName);
                if (entities != null && !entities.isEmpty()) {
                    row.put(info.variableName, entityToTuGraphFormat(entities.get(0)));
                }
            }
            if (!row.isEmpty()) {
                results.add(row);
            }
            return results;
        }

        for (int i = 0; i < primaryEntities.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (ReturnInfo info : returnInfos) {
                List<GraphEntity> entities = entitiesByVarName.get(info.variableName);
                if (entities != null && !entities.isEmpty()) {
                    if (info.variableName.equals(primaryVar)) {
                        if (i < entities.size()) {
                            row.put(info.variableName, entityToTuGraphFormat(entities.get(i)));
                        }
                    } else {
                        row.put(info.variableName, entityToTuGraphFormat(entities.get(0)));
                    }
                }
            }
            if (!row.isEmpty()) {
                results.add(row);
            }
        }

        return results;
    }

    private Map<String, List<GraphEntity>> collectEntitiesByVarName(
            ExecutionResult execResult,
            boolean includeBatchResults,
            boolean includeUnionResults) {
        Map<String, List<GraphEntity>> entitiesByVarName = new LinkedHashMap<>();
        visitQueryResults(execResult, includeBatchResults, includeUnionResults,
                queryResult -> addEntitiesToVarMap(entitiesByVarName, queryResult.getEntities()));
        return entitiesByVarName;
    }

    private List<QueryResult.ResultRow> collectRows(
            ExecutionResult execResult,
            boolean includeBatchResults,
            boolean includeUnionResults) {
        List<QueryResult.ResultRow> rows = new ArrayList<>();
        visitQueryResults(execResult, includeBatchResults, includeUnionResults,
                queryResult -> rows.addAll(queryResult.getRows()));
        return rows;
    }

    private void visitQueryResults(
            ExecutionResult execResult,
            boolean includeBatchResults,
            boolean includeUnionResults,
            Consumer<QueryResult> consumer) {
        for (List<QueryResult> qrList : execResult.getPhysicalResults().values()) {
            qrList.forEach(consumer);
        }
        execResult.getExternalResults().forEach(consumer);
        if (includeBatchResults) {
            execResult.getBatchResults().values().forEach(consumer);
        }
        if (includeUnionResults) {
            execResult.getUnionResults().values().forEach(consumer);
        }
    }

    private void addEntitiesToVarMap(Map<String, List<GraphEntity>> entitiesByVarName, List<GraphEntity> entities) {
        for (GraphEntity entity : entities) {
            String varName = entity.getVariableName();
            if (varName != null && !varName.isEmpty()) {
                entitiesByVarName.computeIfAbsent(varName, k -> new ArrayList<>()).add(entity);
            } else if (entity.getId() != null) {
                entitiesByVarName.computeIfAbsent(entity.getId(), k -> new ArrayList<>()).add(entity);
            }
        }
    }

    private List<Map<String, Object>> buildStructuredPathResults(String pathVarName, Program ast, ExecutionResult execResult) {
        if (ast == null || ast.getStatement() == null || ast.getStatement().getQuery() == null) {
            return new ArrayList<>();
        }

        if (ast.getStatement().getQuery().getUnions().isEmpty() || execResult.getUnionResults().isEmpty()) {
            return new ArrayList<>();
        }

        List<List<Map<String, Object>>> allPaths = new ArrayList<>();
        List<Statement.SingleQuery> singleQueries = ast.getStatement().getQuery().getSingleQueries();

        for (QueryResult unionResult : execResult.getUnionResults().values()) {
            List<ExecutionResult> subResults = extractUnionSubResults(unionResult);
            if (subResults.isEmpty()) {
                continue;
            }

            int bound = Math.min(singleQueries.size(), subResults.size());
            for (int i = 0; i < bound; i++) {
                List<PathInfo> pathInfos = extractPathInfo(singleQueries.get(i), pathVarName);
                if (pathInfos.isEmpty()) {
                    continue;
                }
                Map<String, List<GraphEntity>> branchEntitiesByVarName = collectEntitiesByVarName(subResults.get(i), false, false);
                List<QueryResult.ResultRow> branchRows = collectRows(subResults.get(i), false, false);
                allPaths.addAll(buildPaths(pathInfos, branchEntitiesByVarName, branchRows));
            }
        }

        return wrapPaths(pathVarName, allPaths);
    }

    private List<ExecutionResult> extractUnionSubResults(QueryResult unionResult) {
        if (!(unionResult.getData() instanceof List<?> dataList)) {
            return new ArrayList<>();
        }

        List<ExecutionResult> subResults = new ArrayList<>();
        for (Object item : dataList) {
            if (item instanceof ExecutionResult executionResult) {
                subResults.add(executionResult);
            }
        }
        return subResults;
    }

    private List<Map<String, Object>> buildPathResults(
            String pathVarName,
            Map<String, List<GraphEntity>> entitiesByVarName,
            List<QueryResult.ResultRow> rows,
            Program ast) {
        List<PathInfo> pathInfos = extractPathInfo(ast, pathVarName);
        List<List<Map<String, Object>>> allPaths = buildPaths(pathInfos, entitiesByVarName, rows);
        return wrapPaths(pathVarName, allPaths);
    }

    private List<List<Map<String, Object>>> buildPaths(
            List<PathInfo> pathInfos,
            Map<String, List<GraphEntity>> entitiesByVarName,
            List<QueryResult.ResultRow> rows) {
        List<List<Map<String, Object>>> rowBasedPaths = filterValidPaths(buildPathsFromRows(pathInfos, entitiesByVarName, rows));
        if (!rowBasedPaths.isEmpty()) {
            return rowBasedPaths;
        }
        return filterValidPaths(buildPathsFromEntities(pathInfos, entitiesByVarName));
    }

    private List<List<Map<String, Object>>> buildPathsFromEntities(
            List<PathInfo> pathInfos,
            Map<String, List<GraphEntity>> entitiesByVarName) {
        List<List<Map<String, Object>>> allPaths = new ArrayList<>();
        if (pathInfos.isEmpty()) {
            return allPaths;
        }

        Map<String, List<GraphEntity>> normalizedEntities = deduplicateEntitiesByVarName(entitiesByVarName);
        for (PathInfo pathInfo : pathInfos) {
            if (pathInfo.patternElement == null) {
                continue;
            }

            NodePattern startNode = pathInfo.patternElement.getNodePattern();
            String startVar = startNode != null ? startNode.getVariable() : null;
            if (startVar == null) {
                continue;
            }

            List<GraphEntity> startEntities = normalizedEntities.get(startVar);
            if (startEntities == null || startEntities.isEmpty()) {
                continue;
            }

            for (GraphEntity startEntity : startEntities) {
                List<Map<String, Object>> basePath = new ArrayList<>();
                basePath.add(buildNodeElement(startEntity));
                buildPathVariantsRecursive(
                        pathInfo.patternElement.getChains(), 0, basePath, normalizedEntities, allPaths);
            }
        }
        return allPaths;
    }

    private List<List<Map<String, Object>>> buildPathsFromRows(
            List<PathInfo> pathInfos,
            Map<String, List<GraphEntity>> entitiesByVarName,
            List<QueryResult.ResultRow> rows) {
        List<List<Map<String, Object>>> allPaths = new ArrayList<>();
        if (pathInfos.isEmpty() || rows == null || rows.isEmpty()) {
            return allPaths;
        }

        Map<String, List<GraphEntity>> normalizedEntities = deduplicateEntitiesByVarName(entitiesByVarName);
        for (PathInfo pathInfo : pathInfos) {
            if (pathInfo.patternElement == null) {
                continue;
            }

            NodePattern startNode = pathInfo.patternElement.getNodePattern();
            String startVar = startNode != null ? startNode.getVariable() : null;
            if (startVar == null) {
                continue;
            }

            for (QueryResult.ResultRow row : rows) {
                GraphEntity startEntity = row.getEntitiesByVariable().get(startVar);
                if (startEntity == null) {
                    continue;
                }

                List<Map<String, Object>> basePath = new ArrayList<>();
                basePath.add(buildNodeElement(startEntity));
                buildPathVariantsRecursiveWithRow(
                        pathInfo.patternElement.getChains(), 0, basePath, normalizedEntities, row, allPaths);
            }
        }
        return allPaths;
    }

    private void buildPathVariantsRecursiveWithRow(
            List<Pattern.PatternElementChain> chains,
            int chainIndex,
            List<Map<String, Object>> currentPath,
            Map<String, List<GraphEntity>> entitiesByVarName,
            QueryResult.ResultRow row,
            List<List<Map<String, Object>>> allPaths) {

        if (chainIndex >= chains.size()) {
            allPaths.add(new ArrayList<>(currentPath));
            return;
        }

        Pattern.PatternElementChain chain = chains.get(chainIndex);
        RelationshipPattern relPattern = chain.getRelationshipPattern();
        NodePattern endNode = chain.getNodePattern();
        String endVar = endNode != null ? endNode.getVariable() : null;
        GraphEntity rowEntity = endVar != null ? row.getEntitiesByVariable().get(endVar) : null;

        List<GraphEntity> endEntities = new ArrayList<>();
        if (endVar != null) {
            List<GraphEntity> entities = entitiesByVarName.get(endVar);
            if (entities != null) {
                endEntities.addAll(entities);
            }
        }

        if (relPattern != null && relPattern.getRelationshipTypes() != null) {
            for (String relType : relPattern.getRelationshipTypes()) {
                List<GraphEntity> linkedEntities = new ArrayList<>();
                if (rowEntity != null) {
                    if (matchesPathStep(rowEntity, currentPath, relType)) {
                        linkedEntities.add(rowEntity);
                    }
                } else if (isVirtualEdge(relType)) {
                    List<GraphEntity> matchedEntities = filterEntitiesByEdgeType(endEntities, relType);
                    linkedEntities.addAll(filterEntitiesByLinkage(matchedEntities, currentPath, relType));
                }

                if (linkedEntities.isEmpty()) {
                    continue;
                }

                for (GraphEntity endEntity : linkedEntities) {
                    List<Map<String, Object>> newPath = new ArrayList<>(currentPath);
                    newPath.add(buildEdgeElement(relType));
                    newPath.add(buildNodeElement(endEntity));
                    buildPathVariantsRecursiveWithRow(chains, chainIndex + 1, newPath, entitiesByVarName, row, allPaths);
                }
            }
        } else if (rowEntity != null) {
            List<Map<String, Object>> newPath = new ArrayList<>(currentPath);
            newPath.add(buildNodeElement(rowEntity));
            buildPathVariantsRecursiveWithRow(chains, chainIndex + 1, newPath, entitiesByVarName, row, allPaths);
        }
    }

    private boolean matchesPathStep(GraphEntity entity, List<Map<String, Object>> currentPath, String relType) {
        List<GraphEntity> matchedEntities = filterEntitiesByEdgeType(List.of(entity), relType);
        return !filterEntitiesByLinkage(matchedEntities, currentPath, relType).isEmpty();
    }

    private void buildPathVariantsRecursive(
            List<Pattern.PatternElementChain> chains,
            int chainIndex,
            List<Map<String, Object>> currentPath,
            Map<String, List<GraphEntity>> entitiesByVarName,
            List<List<Map<String, Object>>> allPaths) {

        if (chainIndex >= chains.size()) {
            allPaths.add(new ArrayList<>(currentPath));
            return;
        }

        Pattern.PatternElementChain chain = chains.get(chainIndex);
        RelationshipPattern relPattern = chain.getRelationshipPattern();
        NodePattern endNode = chain.getNodePattern();
        String endVar = endNode != null ? endNode.getVariable() : null;

        List<GraphEntity> endEntities = new ArrayList<>();
        if (endNode != null && endNode.getVariable() != null) {
            List<GraphEntity> entities = entitiesByVarName.get(endNode.getVariable());
            if (entities != null) {
                endEntities.addAll(entities);
            }
        }

        if (relPattern != null && relPattern.getRelationshipTypes() != null) {
            for (String relType : relPattern.getRelationshipTypes()) {
                List<GraphEntity> matchedEntities = filterEntitiesByEdgeType(endEntities, relType);
                List<GraphEntity> linkedEntities = filterEntitiesByLinkage(matchedEntities, currentPath, relType);

                if (linkedEntities.isEmpty()) {
                    List<Map<String, Object>> newPath = new ArrayList<>(currentPath);
                    newPath.add(buildEdgeElement(relType));
                    buildPathVariantsRecursive(chains, chainIndex + 1, newPath, entitiesByVarName, allPaths);
                } else {
                    for (GraphEntity endEntity : linkedEntities) {
                        List<Map<String, Object>> newPath = new ArrayList<>(currentPath);
                        newPath.add(buildEdgeElement(relType));
                        newPath.add(buildNodeElement(endEntity));
                        buildPathVariantsRecursive(chains, chainIndex + 1, newPath, entitiesByVarName, allPaths);
                    }
                }
            }
        } else if (!endEntities.isEmpty()) {
            for (GraphEntity endEntity : endEntities) {
                List<Map<String, Object>> newPath = new ArrayList<>(currentPath);
                newPath.add(buildNodeElement(endEntity));
                buildPathVariantsRecursive(chains, chainIndex + 1, newPath, entitiesByVarName, allPaths);
            }
        } else {
            buildPathVariantsRecursive(chains, chainIndex + 1, currentPath, entitiesByVarName, allPaths);
        }
    }

    private List<List<Map<String, Object>>> filterValidPaths(List<List<Map<String, Object>>> paths) {
        List<List<Map<String, Object>>> validPaths = new ArrayList<>();
        for (List<Map<String, Object>> path : paths) {
            if (isValidPath(path)) {
                validPaths.add(path);
            }
        }
        return validPaths;
    }

    private boolean isValidPath(List<Map<String, Object>> path) {
        if (path == null || path.isEmpty() || path.size() % 2 == 0) {
            return false;
        }
        for (int i = 0; i < path.size(); i++) {
            Object type = path.get(i).get("type");
            if (!(type instanceof String typeValue)) {
                return false;
            }
            if (i % 2 == 0 && !"node".equals(typeValue)) {
                return false;
            }
            if (i % 2 == 1 && !"edge".equals(typeValue)) {
                return false;
            }
        }
        return true;
    }

    private List<Map<String, Object>> wrapPaths(String pathVarName, List<List<Map<String, Object>>> allPaths) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (!allPaths.isEmpty()) {
            List<List<Map<String, Object>>> deduplicatedPaths = new ArrayList<>(new LinkedHashSet<>(allPaths));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(pathVarName, deduplicatedPaths);
            results.add(row);
        }
        return results;
    }

    private Map<String, List<GraphEntity>> deduplicateEntitiesByVarName(Map<String, List<GraphEntity>> entitiesByVarName) {
        Map<String, List<GraphEntity>> deduplicated = new LinkedHashMap<>();

        for (Map.Entry<String, List<GraphEntity>> entry : entitiesByVarName.entrySet()) {
            Map<String, GraphEntity> uniqueEntities = new LinkedHashMap<>();
            for (GraphEntity entity : entry.getValue()) {
                uniqueEntities.putIfAbsent(buildGraphEntityDedupKey(entity), entity);
            }
            deduplicated.put(entry.getKey(), new ArrayList<>(uniqueEntities.values()));
        }

        return deduplicated;
    }

    private String buildGraphEntityDedupKey(GraphEntity entity) {
        StringBuilder key = new StringBuilder();
        key.append(entity.getType()).append('|')
                .append(entity.getVariableName()).append('|')
                .append(entity.getLabel()).append('|')
                .append(entity.getId()).append('|')
                .append(entity.getSourceEdgeType()).append('|')
                .append(entity.getStartNodeId()).append('|')
                .append(entity.getEndNodeId()).append('|');

        Map<String, Object> properties = entity.getProperties();
        if (properties != null && !properties.isEmpty()) {
            List<String> propertyKeys = new ArrayList<>(properties.keySet());
            Collections.sort(propertyKeys);
            for (String propertyKey : propertyKeys) {
                key.append(propertyKey).append('=').append(properties.get(propertyKey)).append(';');
            }
        }

        return key.toString();
    }

    private List<GraphEntity> filterEntitiesByLinkage(
            List<GraphEntity> entities,
            List<Map<String, Object>> currentPath,
            String edgeType) {
        if (currentPath.isEmpty()) {
            return entities;
        }

        Map<String, Object> lastElement = currentPath.get(currentPath.size() - 1);
        if (!"node".equals(lastElement.get("type"))) {
            return entities;
        }

        VirtualEdgeBinding binding = getVirtualEdgeBinding(edgeType);
        if (binding == null || binding.getIdMapping().isEmpty()) {
            return entities;
        }

        Map<String, Object> lastNodeProps = (Map<String, Object>) lastElement.get("props");
        String lastNodeLabel = (String) lastElement.get("label");
        if (lastNodeProps == null || lastNodeLabel == null) {
            return entities;
        }

        List<GraphEntity> filtered = new ArrayList<>();
        for (GraphEntity entity : entities) {
            if (entity.getSourceEdgeType() != null && !edgeType.equals(entity.getSourceEdgeType())) {
                continue;
            }

            if (!isVirtualEdge(edgeType) || matchesVirtualEdgeLink(entity, lastNodeProps, lastNodeLabel, binding)) {
                filtered.add(entity);
            }
        }

        return filtered;
    }

    private boolean isVirtualEdge(String edgeType) {
        return metadataQueryService != null && edgeType != null && metadataQueryService.isVirtualEdge(edgeType);
    }

    private List<GraphEntity> filterEntitiesByEdgeType(List<GraphEntity> entities, String edgeType) {
        List<GraphEntity> filtered = new ArrayList<>();
        String expectedLabel = getExpectedLabelForEdgeType(edgeType);

        for (GraphEntity entity : entities) {
            boolean matches = false;

            if (entity.getSourceEdgeType() != null) {
                matches = edgeType.equals(entity.getSourceEdgeType());
            } else if (expectedLabel != null && entity.getLabel() != null) {
                matches = expectedLabel.equals(entity.getLabel());
            } else {
                matches = true;
            }

            if (matches) {
                filtered.add(entity);
            }
        }
        return filtered;
    }

    private String getExpectedLabelForEdgeType(String edgeType) {
        return metadataQueryService != null ? metadataQueryService.getTargetLabelForEdge(edgeType) : null;
    }

    private VirtualEdgeBinding getVirtualEdgeBinding(String edgeType) {
        if (metadataQueryService == null || edgeType == null) {
            return null;
        }
        return metadataQueryService.getVirtualEdgeBinding(edgeType).orElse(null);
    }

    private boolean matchesVirtualEdgeLink(
            GraphEntity entity,
            Map<String, Object> sourceNodeProps,
            String sourceNodeLabel,
            VirtualEdgeBinding binding) {
        for (Map.Entry<String, String> mapping : binding.getIdMapping().entrySet()) {
            Object sourceValue = resolveMappedValue(sourceNodeProps, sourceNodeLabel, mapping.getKey(), true, null);
            Object targetValue = resolveMappedValue(entity.getProperties(), entity.getLabel(), mapping.getValue(), false, entity.getId());
            if (!Objects.equals(normalizeMappedValue(sourceValue), normalizeMappedValue(targetValue))) {
                return false;
            }
        }
        return true;
    }

    private Object resolveMappedValue(
            Map<String, Object> properties,
            String label,
            String mappingKey,
            boolean useLabelMetadata,
            String entityId) {
        if (mappingKey == null) {
            return null;
        }
        if ("_id".equals(mappingKey)) {
            String idProperty = resolveIdProperty(label, useLabelMetadata);
            Object idValue = properties != null ? properties.get(idProperty) : null;
            return idValue != null ? idValue : entityId;
        }
        return properties != null ? properties.get(mappingKey) : null;
    }

    private String resolveIdProperty(String label, boolean useLabelMetadata) {
        if (useLabelMetadata && metadataQueryService != null && label != null) {
            return metadataQueryService.getLabel(label)
                    .map(metadata -> metadata.getIdProperty() != null ? metadata.getIdProperty() : "id")
                    .orElse("id");
        }
        return "id";
    }

    private String normalizeMappedValue(Object value) {
        return value == null ? null : value.toString();
    }

    private Map<String, Object> buildNodeElement(GraphEntity entity) {
        Map<String, Object> element = new LinkedHashMap<>();
        element.put("type", "node");
        element.put("label", entity.getLabel() != null ? entity.getLabel() : "Unknown");

        Map<String, Object> props = new LinkedHashMap<>();
        if (entity.getProperties() != null) {
            props.putAll(entity.getProperties());
        }
        if (entity.getId() != null && !props.containsKey("id")) {
            props.put("id", entity.getId());
        }
        element.put("props", props);

        return element;
    }

    private Map<String, Object> buildEdgeElement(String edgeType) {
        Map<String, Object> element = new LinkedHashMap<>();
        element.put("type", "edge");
        element.put("label", edgeType);
        element.put("props", new LinkedHashMap<>());

        return element;
    }

    private List<Map<String, Object>> buildRowResults(
            List<ReturnInfo> returnInfos,
            List<QueryResult.ResultRow> rows,
            Map<String, List<GraphEntity>> entitiesByVarName) {
        if (returnInfos == null || returnInfos.isEmpty() || rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<QueryResult.ResultRow> matchedRows = new ArrayList<>();
        int maxWidth = 0;
        for (QueryResult.ResultRow row : rows) {
            if (row == null || row.getEntitiesByVariable() == null || row.getEntitiesByVariable().isEmpty()) {
                continue;
            }

            boolean containsAllReturnVariables = true;
            for (ReturnInfo info : returnInfos) {
                if (!row.getEntitiesByVariable().containsKey(info.variableName)) {
                    containsAllReturnVariables = false;
                    break;
                }
            }

            if (!containsAllReturnVariables) {
                continue;
            }

            int width = row.getEntitiesByVariable().size();
            if (width > maxWidth) {
                matchedRows.clear();
                maxWidth = width;
            }
            if (width == maxWidth) {
                matchedRows.add(row);
            }
        }

        boolean rowsCoverReturnedEntities = true;
        if (returnInfos.size() > 1 && entitiesByVarName != null) {
            for (ReturnInfo info : returnInfos) {
                List<GraphEntity> entities = entitiesByVarName.get(info.variableName);
                int uniqueEntityCount = countUniqueEntities(entities);
                if (uniqueEntityCount > matchedRows.size()) {
                    rowsCoverReturnedEntities = false;
                    break;
                }
            }
        }

        boolean shouldUseRows = rowsCoverReturnedEntities && (
                maxWidth > returnInfos.size()
                        || (returnInfos.size() > 1 && matchedRows.size() > 1)
        );
        if (matchedRows.isEmpty() || !shouldUseRows) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (QueryResult.ResultRow row : matchedRows) {
            Map<String, Object> resultRow = new LinkedHashMap<>();
            for (ReturnInfo info : returnInfos) {
                GraphEntity entity = row.getEntitiesByVariable().get(info.variableName);
                if (entity != null) {
                    resultRow.put(info.variableName, entityToTuGraphFormat(entity));
                }
            }
            if (!resultRow.isEmpty()) {
                String signature = resultRow.toString();
                if (seen.add(signature)) {
                    results.add(resultRow);
                }
            }
        }

        return results;
    }

    private int countUniqueEntities(List<GraphEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }

        Set<String> uniqueKeys = new LinkedHashSet<>();
        for (GraphEntity entity : entities) {
            uniqueKeys.add(buildGraphEntityDedupKey(entity));
        }
        return uniqueKeys.size();
    }

    private Map<String, Object> entityToTuGraphFormat(GraphEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();

        if (entity.getType() == GraphEntity.EntityType.NODE) {
            if (entity.getId() != null) {
                map.put("id", entity.getId());
            }
            if (entity.getLabel() != null) {
                map.put("label", entity.getLabel());
            }
            if (entity.getProperties() != null && !entity.getProperties().isEmpty()) {
                map.putAll(entity.getProperties());
            }
        } else if (entity.getType() == GraphEntity.EntityType.EDGE) {
            map.put("type", entity.getLabel());
            if (entity.getStartNodeId() != null) {
                map.put("startNodeId", entity.getStartNodeId());
            }
            if (entity.getEndNodeId() != null) {
                map.put("endNodeId", entity.getEndNodeId());
            }
            if (entity.getProperties() != null && !entity.getProperties().isEmpty()) {
                map.put("properties", entity.getProperties());
            }
        }

        return map;
    }

    private List<PathInfo> extractPathInfo(Program ast, String pathVarName) {
        List<PathInfo> pathInfos = new ArrayList<>();

        if (ast.getStatement() != null && ast.getStatement().getQuery() != null) {
            for (Statement.SingleQuery sq : ast.getStatement().getQuery().getSingleQueries()) {
                pathInfos.addAll(extractPathInfo(sq, pathVarName));
            }
        }

        return pathInfos;
    }

    private List<PathInfo> extractPathInfo(Statement.SingleQuery singleQuery, String pathVarName) {
        List<PathInfo> pathInfos = new ArrayList<>();
        for (MatchClause match : singleQuery.getMatchClauses()) {
            Pattern pattern = match.getPattern();
            if (pattern != null) {
                for (Pattern.PatternPart part : pattern.getPatternParts()) {
                    if (pathVarName.equals(part.getVariable())) {
                        PathInfo info = new PathInfo();
                        info.pathVariable = pathVarName;
                        info.patternElement = part.getPatternElement();
                        pathInfos.add(info);
                    }
                }
            }
        }
        return pathInfos;
    }

    private List<ReturnInfo> getReturnInfos(Program ast) {
        List<ReturnInfo> infos = new ArrayList<>();
        Set<String> pathVariables = getPathVariables(ast);

        if (ast.getStatement() != null && ast.getStatement().getQuery() != null) {
            for (Statement.SingleQuery sq : ast.getStatement().getQuery().getSingleQueries()) {
                if (sq.getReturnClause() != null) {
                    for (ReturnClause.ReturnItem item : sq.getReturnClause().getReturnItems()) {
                        if (item.getExpression() instanceof Variable) {
                            String varName = ((Variable) item.getExpression()).getName();
                            boolean isPath = pathVariables.contains(varName);
                            infos.add(new ReturnInfo(varName, isPath));
                        }
                    }
                }
            }
        }

        return infos;
    }

    private Set<String> getPathVariables(Program ast) {
        Set<String> pathVars = new HashSet<>();

        if (ast.getStatement() != null && ast.getStatement().getQuery() != null) {
            for (Statement.SingleQuery sq : ast.getStatement().getQuery().getSingleQueries()) {
                for (MatchClause match : sq.getMatchClauses()) {
                    Pattern pattern = match.getPattern();
                    if (pattern != null) {
                        for (Pattern.PatternPart part : pattern.getPatternParts()) {
                            if (part.getVariable() != null) {
                                pathVars.add(part.getVariable());
                            }
                        }
                    }
                }
            }
        }

        return pathVars;
    }

    private static class PathInfo {
        String pathVariable;
        Pattern.PatternElement patternElement;
    }

    private static class ReturnInfo {
        String variableName;
        boolean isPath;

        ReturnInfo(String name, boolean path) {
            this.variableName = name;
            this.isPath = path;
        }
    }
}
