package com.federatedquery.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.federatedquery.adapter.GraphEntity;
import com.federatedquery.adapter.QueryResult;
import com.federatedquery.aggregator.*;
import com.federatedquery.ast.*;
import com.federatedquery.executor.ExecutionResult;
import com.federatedquery.parser.CypherParserFacade;
import com.federatedquery.plan.ExecutionPlan;
import com.federatedquery.rewriter.QueryRewriter;
import com.federatedquery.executor.FederatedExecutor;
import com.federatedquery.reliability.OOMProtectionInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GraphQuerySDK {
    private static final Logger log = LoggerFactory.getLogger(GraphQuerySDK.class);
    
    private final CypherParserFacade parser;
    private final QueryRewriter rewriter;
    private final FederatedExecutor executor;
    private final ResultStitcher stitcher;
    private final GlobalSorter sorter;
    private final UnionDeduplicator deduplicator;
    private final OOMProtectionInterceptor oomProtection;
    private final ObjectMapper objectMapper;
    
    public GraphQuerySDK(CypherParserFacade parser,
                        QueryRewriter rewriter,
                        FederatedExecutor executor,
                        ResultStitcher stitcher,
                        GlobalSorter sorter,
                        UnionDeduplicator deduplicator,
                        OOMProtectionInterceptor oomProtection) {
        this.parser = parser;
        this.rewriter = rewriter;
        this.executor = executor;
        this.stitcher = stitcher;
        this.sorter = sorter;
        this.deduplicator = deduplicator;
        this.oomProtection = oomProtection;
        this.objectMapper = new ObjectMapper();
    }
    
    public String execute(String cypher) {
        long startTime = System.currentTimeMillis();
        
        try {
            Program ast = parser.parseCached(cypher);
            
            ast = oomProtection.enforceLimit(ast);
            
            ExecutionPlan plan = rewriter.rewrite(ast);
            
            ExecutionResult execResult = executor.execute(plan).join();
            
            StitchedResult stitched = stitcher.stitch(execResult, plan);
            
            List<Map<String, Object>> results = buildTuGraphFormatResults(ast, stitched, execResult);
            
            return objectMapper.writeValueAsString(results);
            
        } catch (Exception e) {
            log.error("Failed to execute query: {}", cypher, e);
            return buildErrorResponse(e);
        }
    }
    
    public String executeRaw(String cypher) {
        try {
            Program ast = parser.parseCached(cypher);
            ExecutionPlan plan = rewriter.rewrite(ast);
            ExecutionResult execResult = executor.execute(plan).join();
            
            List<Map<String, Object>> results = buildTuGraphFormatResults(ast, execResult, execResult);
            
            return objectMapper.writeValueAsString(results);
        } catch (Exception e) {
            return buildErrorResponse(e);
        }
    }
    
    private List<Map<String, Object>> buildTuGraphFormatResults(Program ast, StitchedResult stitched, ExecutionResult execResult) {
        List<Map<String, Object>> results = new ArrayList<>();
        
        List<String> returnVariables = getReturnVariables(ast);
        
        if (returnVariables.isEmpty()) {
            return results;
        }
        
        Map<String, GraphEntity> entityById = stitched.getEntityById();
        
        for (String varName : returnVariables) {
            GraphEntity entity = entityById.get(varName);
            if (entity != null) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put(varName, entityToTuGraphFormat(entity));
                results.add(row);
            }
        }
        
        if (results.isEmpty() && !entityById.isEmpty()) {
            for (GraphEntity entity : entityById.values()) {
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
    
    private List<Map<String, Object>> buildTuGraphFormatResults(Program ast, ExecutionResult execResult, ExecutionResult dummy) {
        List<Map<String, Object>> results = new ArrayList<>();
        
        List<ReturnInfo> returnInfos = getReturnInfos(ast);
        
        Map<String, List<GraphEntity>> entitiesByVarName = new LinkedHashMap<>();
        
        for (List<QueryResult> qrList : execResult.getPhysicalResults().values()) {
            for (QueryResult qr : qrList) {
                for (GraphEntity entity : qr.getEntities()) {
                    String varName = entity.getVariableName();
                    if (varName != null && !varName.isEmpty()) {
                        entitiesByVarName.computeIfAbsent(varName, k -> new ArrayList<>()).add(entity);
                    } else if (entity.getId() != null) {
                        entitiesByVarName.computeIfAbsent(entity.getId(), k -> new ArrayList<>()).add(entity);
                    }
                }
            }
        }
        for (QueryResult qr : execResult.getExternalResults()) {
            for (GraphEntity entity : qr.getEntities()) {
                String varName = entity.getVariableName();
                if (varName != null && !varName.isEmpty()) {
                    entitiesByVarName.computeIfAbsent(varName, k -> new ArrayList<>()).add(entity);
                } else if (entity.getId() != null) {
                    entitiesByVarName.computeIfAbsent(entity.getId(), k -> new ArrayList<>()).add(entity);
                }
            }
        }
        for (QueryResult qr : execResult.getBatchResults().values()) {
            for (GraphEntity entity : qr.getEntities()) {
                String varName = entity.getVariableName();
                if (varName != null && !varName.isEmpty()) {
                    entitiesByVarName.computeIfAbsent(varName, k -> new ArrayList<>()).add(entity);
                } else if (entity.getId() != null) {
                    entitiesByVarName.computeIfAbsent(entity.getId(), k -> new ArrayList<>()).add(entity);
                }
            }
        }
        
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
                log.debug("Building path results for variable: {}, entitiesByVarName: {}", info.variableName, entitiesByVarName.keySet());
                List<Map<String, Object>> pathResults = buildPathResults(info.variableName, entitiesByVarName, ast);
                results.addAll(pathResults);
            }
        }
        
        if (!results.isEmpty()) {
            return results;
        }
        
        String primaryVar = returnInfos.size() > 1 ? returnInfos.get(returnInfos.size() - 1).variableName : returnInfos.get(0).variableName;
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
    
    private List<PathInfo> extractPathInfo(Program ast, String pathVarName) {
        List<PathInfo> pathInfos = new ArrayList<>();
        
        if (ast.getStatement() != null && ast.getStatement().getQuery() != null) {
            for (Statement.SingleQuery sq : ast.getStatement().getQuery().getSingleQueries()) {
                for (MatchClause match : sq.getMatchClauses()) {
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
            }
        }
        
        return pathInfos;
    }
    
    private List<Map<String, Object>> buildPathResults(String pathVarName, Map<String, List<GraphEntity>> entitiesByVarName, Program ast) {
        List<Map<String, Object>> results = new ArrayList<>();
        
        List<PathInfo> pathInfos = extractPathInfo(ast, pathVarName);
        
        if (pathInfos.isEmpty()) {
            return results;
        }
        
        List<List<Map<String, Object>>> allPaths = new ArrayList<>();
        
        for (PathInfo pathInfo : pathInfos) {
            List<Map<String, Object>> pathElements = buildPathElementsList(pathInfo, entitiesByVarName);
            if (pathElements != null && !pathElements.isEmpty()) {
                allPaths.add(pathElements);
            }
        }
        
        if (!allPaths.isEmpty()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("paths", allPaths);
            results.add(row);
        }
        
        return results;
    }
    
    private List<Map<String, Object>> buildPathElementsList(PathInfo pathInfo, Map<String, List<GraphEntity>> entitiesByVarName) {
        List<Map<String, Object>> pathElements = new ArrayList<>();
        
        if (pathInfo.patternElement == null) {
            return pathElements;
        }
        
        NodePattern startNode = pathInfo.patternElement.getNodePattern();
        String startVar = startNode != null ? startNode.getVariable() : null;
        log.debug("Start node variable: {}, available entities: {}", startVar, entitiesByVarName.keySet());
        
        if (startNode != null && startNode.getVariable() != null) {
            List<GraphEntity> startEntities = entitiesByVarName.get(startNode.getVariable());
            if (startEntities != null && !startEntities.isEmpty()) {
                pathElements.add(buildNodeElement(startEntities.get(0)));
            }
        }
        
        for (Pattern.PatternElementChain chain : pathInfo.patternElement.getChains()) {
            RelationshipPattern relPattern = chain.getRelationshipPattern();
            if (relPattern != null && relPattern.getRelationshipTypes() != null) {
                for (String relType : relPattern.getRelationshipTypes()) {
                    pathElements.add(buildEdgeElement(relType));
                }
            }
            
            NodePattern endNode = chain.getNodePattern();
            String endVar = endNode != null ? endNode.getVariable() : null;
            log.debug("End node variable: {}", endVar);
            
            if (endNode != null && endNode.getVariable() != null) {
                List<GraphEntity> endEntities = entitiesByVarName.get(endNode.getVariable());
                if (endEntities != null && !endEntities.isEmpty()) {
                    pathElements.add(buildNodeElement(endEntities.get(0)));
                }
            }
        }
        
        return pathElements;
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
    
    private List<String> getReturnVariables(Program ast) {
        List<String> variables = new ArrayList<>();
        
        if (ast.getStatement() != null && ast.getStatement().getQuery() != null) {
            for (Statement.SingleQuery sq : ast.getStatement().getQuery().getSingleQueries()) {
                if (sq.getReturnClause() != null) {
                    for (ReturnClause.ReturnItem item : sq.getReturnClause().getReturnItems()) {
                        if (item.getExpression() instanceof Variable) {
                            variables.add(((Variable) item.getExpression()).getName());
                        }
                    }
                }
            }
        }
        
        return variables;
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
    
    private Map<String, Object> buildPathFormat(GraphEntity startNode, List<GraphEntity> relationships, List<GraphEntity> nodes) {
        Map<String, Object> pathMap = new LinkedHashMap<>();
        
        List<Map<String, Object>> nodeList = new ArrayList<>();
        if (startNode != null) {
            nodeList.add(entityToTuGraphFormat(startNode));
        }
        for (GraphEntity node : nodes) {
            nodeList.add(entityToTuGraphFormat(node));
        }
        pathMap.put("nodes", nodeList);
        
        List<Map<String, Object>> relList = new ArrayList<>();
        for (GraphEntity rel : relationships) {
            relList.add(entityToTuGraphFormat(rel));
        }
        pathMap.put("relationships", relList);
        
        return pathMap;
    }
    
    private String buildErrorResponse(Exception e) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", e.getMessage());
        
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            return "{\"success\":false,\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }
}
