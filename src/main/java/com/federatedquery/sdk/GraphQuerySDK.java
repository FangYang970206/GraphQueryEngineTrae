package com.federatedquery.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.federatedquery.adapter.GraphEntity;
import com.federatedquery.adapter.QueryResult;
import com.federatedquery.aggregator.*;
import com.federatedquery.ast.Program;
import com.federatedquery.ast.ReturnClause;
import com.federatedquery.ast.Statement;
import com.federatedquery.ast.Variable;
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
        
        List<String> returnVariables = getReturnVariables(ast);
        
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
        
        if (returnVariables.isEmpty()) {
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
        
        String primaryVar = returnVariables.size() > 1 ? returnVariables.get(returnVariables.size() - 1) : returnVariables.get(0);
        List<GraphEntity> primaryEntities = entitiesByVarName.getOrDefault(primaryVar, new ArrayList<>());
        
        if (primaryEntities.isEmpty()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String varName : returnVariables) {
                List<GraphEntity> entities = entitiesByVarName.get(varName);
                if (entities != null && !entities.isEmpty()) {
                    row.put(varName, entityToTuGraphFormat(entities.get(0)));
                }
            }
            if (!row.isEmpty()) {
                results.add(row);
            }
            return results;
        }
        
        for (int i = 0; i < primaryEntities.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String varName : returnVariables) {
                List<GraphEntity> entities = entitiesByVarName.get(varName);
                if (entities != null && !entities.isEmpty()) {
                    if (varName.equals(primaryVar)) {
                        if (i < entities.size()) {
                            row.put(varName, entityToTuGraphFormat(entities.get(i)));
                        }
                    } else {
                        row.put(varName, entityToTuGraphFormat(entities.get(0)));
                    }
                }
            }
            if (!row.isEmpty()) {
                results.add(row);
            }
        }
        
        return results;
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
