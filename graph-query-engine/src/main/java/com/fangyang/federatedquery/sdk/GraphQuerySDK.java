package com.fangyang.federatedquery.sdk;

import com.fangyang.federatedquery.aggregator.GlobalSorter;
import com.fangyang.federatedquery.aggregator.PendingFilterApplier;
import com.fangyang.federatedquery.aggregator.ResultConverter;
import com.fangyang.federatedquery.ast.*;
import com.fangyang.federatedquery.util.RecordConverter;
import com.fangyang.datasource.TuGraphConnector;
import com.fangyang.federatedquery.exception.ErrorCode;
import com.fangyang.federatedquery.exception.GraphQueryException;
import com.fangyang.federatedquery.executor.ExecutionResult;
import com.fangyang.metadata.MetadataQueryService;
import com.fangyang.federatedquery.parser.CypherParserFacade;
import com.fangyang.federatedquery.plan.ExecutionPlan;
import com.fangyang.federatedquery.plan.PhysicalQuery;
import com.fangyang.federatedquery.rewriter.QueryRewriter;
import com.fangyang.common.JsonUtil;
import com.fangyang.federatedquery.executor.FederatedExecutor;
import com.fangyang.federatedquery.aggregator.UnionDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletionException;

@Component
public class GraphQuerySDK {
    private static final Logger log = LoggerFactory.getLogger(GraphQuerySDK.class);

    @Autowired
    private CypherParserFacade parser;
    @Autowired
    private QueryRewriter rewriter;
    @Autowired
    private FederatedExecutor executor;
    @Autowired(required = false)
    private MetadataQueryService metadataQueryService;
    @Autowired(required = false)
    private TuGraphConnector tugraphConnector;

    private PendingFilterApplier pendingFilterApplier;
    private GlobalSorter globalSorter;
    private UnionDeduplicator deduplicator;
    private ResultConverter resultConverter;

    public GraphQuerySDK(CypherParserFacade parser,
                        QueryRewriter rewriter,
                        FederatedExecutor executor,
                        UnionDeduplicator deduplicator) {
        this(parser, rewriter, executor, deduplicator, null);
    }
    
    public GraphQuerySDK(CypherParserFacade parser,
                        QueryRewriter rewriter,
                        FederatedExecutor executor,
                        UnionDeduplicator deduplicator,
                        TuGraphConnector tugraphConnector) {
        this.parser = parser;
        this.rewriter = rewriter;
        this.executor = executor;
        this.deduplicator = deduplicator;
        this.metadataQueryService = rewriter != null ? rewriter.getMetadataQueryService() : null;
        this.tugraphConnector = tugraphConnector;
        this.pendingFilterApplier = new PendingFilterApplier();
        this.globalSorter = new GlobalSorter();
        this.resultConverter = new ResultConverter(this.metadataQueryService);
    }
    
    public List<Map<String, Object>> executeRecords(String cypher) {
        try {
            Program ast = parser.parseCached(cypher);
            return RecordConverter.convertToRecordMaps(executeQuery(ast, null));
        } catch (GraphQueryException e) {
            throw e;
        } catch (CompletionException e) {
            throw unwrapAsyncException(e);
        } catch (Exception e) {
            throw new GraphQueryException(ErrorCode.QUERY_EXECUTION_ERROR, e.getMessage(), e);
        }
    }
    
    public List<Map<String, Object>> executeRecords(String cypher, Map<String, Object> params) {
        try {
            Program ast = parser.parseCached(cypher);
            return RecordConverter.convertToRecordMaps(executeQuery(ast, params));
        } catch (GraphQueryException e) {
            throw e;
        } catch (CompletionException e) {
            throw unwrapAsyncException(e);
        } catch (Exception e) {
            throw new GraphQueryException(ErrorCode.QUERY_EXECUTION_ERROR, e.getMessage(), e);
        }
    }
    
    public List<org.neo4j.driver.Record> executeWithConnector(String cypher) {
        if (tugraphConnector == null) {
            throw new IllegalStateException("TuGraphConnector not configured");
        }
        return tugraphConnector.executeQuery(cypher);
    }
    
    public List<org.neo4j.driver.Record> executeWithConnector(String cypher, Object... parameters) {
        if (tugraphConnector == null) {
            throw new IllegalStateException("TuGraphConnector not configured");
        }
        return tugraphConnector.executeQuery(cypher, parameters);
    }
    
    public String execute(String cypher) {
        try {
            Program ast = parser.parseCached(cypher);
            List<Map<String, Object>> results = executeQuery(ast, null);
            return JsonUtil.toJson(results);
        } catch (GraphQueryException e) {
            throw e;
        } catch (CompletionException e) {
            throw unwrapAsyncException(e);
        } catch (Exception e) {
            throw new GraphQueryException(ErrorCode.QUERY_EXECUTION_ERROR, e.getMessage(), e);
        }
    }
    
    public String execute(String cypher, Map<String, Object> params) {
        try {
            Program ast = parser.parseCached(cypher);
            List<Map<String, Object>> results = executeQuery(ast, params);
            return JsonUtil.toJson(results);
        } catch (GraphQueryException e) {
            throw e;
        } catch (CompletionException e) {
            throw unwrapAsyncException(e);
        } catch (Exception e) {
            throw new GraphQueryException(ErrorCode.QUERY_EXECUTION_ERROR, e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> executeQuery(Program ast, Map<String, Object> params) {
        ExecutionPlan plan = rewriter.rewrite(ast);
        
        if (plan.isDirectExecution() && tugraphConnector != null) {
            return executeDirectQuery(ast, params);
        }
        
        if (params != null && !params.isEmpty()) {
            for (PhysicalQuery pq : plan.getPhysicalQueries()) {
                pq.getParameters().putAll(params);
            }
        }
        
        ExecutionResult execResult = executor.execute(plan).join();

        List<Map<String, Object>> results = resultConverter.buildResults(ast, execResult);
        results = pendingFilterApplier.apply(results, plan.getGlobalContext().getPendingFilters());
        results = globalSorter.applySortAndPagination(results, plan.getGlobalContext());
        results = applyDeduplication(results, ast);
        return results;
    }

    private List<Map<String, Object>> executeDirectQuery(Program ast, Map<String, Object> params) {
        if (tugraphConnector == null) {
            throw new GraphQueryException(ErrorCode.DATASOURCE_CONNECTION_ERROR, 
                "TuGraphConnector not configured for direct execution");
        }
        
        String cypher = ast.toCypher();
        List<org.neo4j.driver.Record> records;
        
        if (params != null && !params.isEmpty()) {
            records = tugraphConnector.executeQuery(cypher, params.values().toArray());
        } else {
            records = tugraphConnector.executeQuery(cypher);
        }
        
        return convertRecordsToMaps(records);
    }

    private List<Map<String, Object>> convertRecordsToMaps(List<org.neo4j.driver.Record> records) {
        if (records == null || records.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<Map<String, Object>> results = new ArrayList<>();
        for (org.neo4j.driver.Record record : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String key : record.keys()) {
                org.neo4j.driver.Value value = record.get(key);
                row.put(key, convertNeo4jValue(value));
            }
            results.add(row);
        }
        return results;
    }

    private Object convertNeo4jValue(org.neo4j.driver.Value value) {
        if (value == null || value.isNull()) {
            return null;
        }
        
        try {
            if (value.type().name().equals("NODE")) {
                return convertNode(value.asNode());
            }
        } catch (Exception ignored) {}
        
        try {
            if (value.type().name().equals("RELATIONSHIP")) {
                return convertRelationship(value.asRelationship());
            }
        } catch (Exception ignored) {}
        
        try {
            if (value.type().name().equals("PATH")) {
                return convertPath(value.asPath());
            }
        } catch (Exception ignored) {}
        
        try {
            if (value.type().name().equals("LIST")) {
                List<Object> list = new ArrayList<>();
                for (Object item : value.asList()) {
                    if (item instanceof org.neo4j.driver.Value) {
                        list.add(convertNeo4jValue((org.neo4j.driver.Value) item));
                    } else {
                        list.add(item);
                    }
                }
                return list;
            }
        } catch (Exception ignored) {}
        
        try {
            if (value.type().name().equals("MAP")) {
                Map<String, Object> map = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : value.asMap().entrySet()) {
                    if (entry.getValue() instanceof org.neo4j.driver.Value) {
                        map.put(entry.getKey(), convertNeo4jValue((org.neo4j.driver.Value) entry.getValue()));
                    } else {
                        map.put(entry.getKey(), entry.getValue());
                    }
                }
                return map;
            }
        } catch (Exception ignored) {}
        
        return value.asObject();
    }

    private Map<String, Object> convertNode(org.neo4j.driver.types.Node node) {
        Map<String, Object> nodeMap = new LinkedHashMap<>();
        
        List<String> labels = new ArrayList<>();
        for (String label : node.labels()) {
            labels.add(label);
        }
        
        if (!labels.isEmpty()) {
            nodeMap.put("label", labels.get(0));
        }
        
        for (String key : node.keys()) {
            Object value = node.get(key).asObject();
            nodeMap.put(key, value);
            
            if ("resId".equals(key) && !nodeMap.containsKey("id")) {
                nodeMap.put("id", value);
            }
        }
        
        return nodeMap;
    }

    private Map<String, Object> convertRelationship(org.neo4j.driver.types.Relationship rel) {
        Map<String, Object> relMap = new LinkedHashMap<>();
        relMap.put("_id", rel.id());
        relMap.put("_type", rel.type());
        relMap.put("_startId", rel.startNodeId());
        relMap.put("_endId", rel.endNodeId());
        
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String key : rel.keys()) {
            properties.put(key, rel.get(key).asObject());
        }
        relMap.put("_properties", properties);
        
        return relMap;
    }

    private Map<String, Object> convertPath(org.neo4j.driver.types.Path path) {
        Map<String, Object> pathMap = new LinkedHashMap<>();
        
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (org.neo4j.driver.types.Node node : path.nodes()) {
            nodes.add(convertNode(node));
        }
        
        List<Map<String, Object>> relationships = new ArrayList<>();
        for (org.neo4j.driver.types.Relationship rel : path.relationships()) {
            relationships.add(convertRelationship(rel));
        }
        
        pathMap.put("nodes", nodes);
        pathMap.put("relationships", relationships);
        pathMap.put("length", relationships.size());
        
        return pathMap;
    }
    
    private List<Map<String, Object>> applyDeduplication(List<Map<String, Object>> results, Program ast) {
        if (ast == null || ast.getStatement() == null || ast.getStatement().getQuery() == null) {
            return results;
        }
        
        List<UnionClause> unions = ast.getStatement().getQuery().getUnions();
        if (unions == null || unions.isEmpty()) {
            return results;
        }
        
        boolean hasUnionAll = unions.stream().anyMatch(UnionClause::isAll);
        if (hasUnionAll) {
            return results;
        }
        
        return deduplicator.deduplicateRows(results, true);
    }

    private GraphQueryException unwrapAsyncException(Throwable e) {
        Throwable cause = e.getCause();
        if (cause instanceof GraphQueryException) {
            return (GraphQueryException) cause;
        }
        return new GraphQueryException(ErrorCode.QUERY_EXECUTION_ERROR, 
                cause != null ? cause.getMessage() : e.getMessage(), cause);
    }
}
