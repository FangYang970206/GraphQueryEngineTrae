package com.federatedquery.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.federatedquery.aggregator.GlobalSorter;
import com.federatedquery.aggregator.PendingFilterApplier;
import com.federatedquery.aggregator.ResultConverter;
import com.federatedquery.ast.*;
import com.federatedquery.ast.UnionClause;
import com.federatedquery.connector.RecordConverter;
import com.federatedquery.connector.TuGraphConfig;
import com.federatedquery.connector.TuGraphConnector;
import com.federatedquery.connector.TuGraphConnectorImpl;
import com.federatedquery.exception.ErrorCode;
import com.federatedquery.exception.GraphQueryException;
import com.federatedquery.executor.ExecutionResult;
import com.federatedquery.metadata.MetadataRegistry;
import com.federatedquery.parser.CypherParserFacade;
import com.federatedquery.plan.ExecutionPlan;
import com.federatedquery.plan.GlobalContext;
import com.federatedquery.plan.PhysicalQuery;
import com.federatedquery.plan.ExternalQuery;
import com.federatedquery.rewriter.QueryRewriter;
import com.federatedquery.util.JsonUtil;
import com.federatedquery.executor.FederatedExecutor;
import com.federatedquery.aggregator.UnionDeduplicator;
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
    private MetadataRegistry registry;
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
        this.registry = rewriter != null ? rewriter.getRegistry() : null;
        this.tugraphConnector = tugraphConnector;
        this.pendingFilterApplier = new PendingFilterApplier();
        this.globalSorter = new GlobalSorter();
        this.resultConverter = new ResultConverter(this.registry);
    }
    
    public List<Map<String, Object>> executeRecords(String cypher) {
        try {
            Program ast = parser.parseCached(cypher);
            ensureExecuteRecordsMode(ast);
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
            ensureExecuteRecordsMode(ast);
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

            if (ast.getStatement() != null && ast.getStatement().isExplain()) {
                return buildExplainResponse(ast);
            }

            if (ast.getStatement() != null && ast.getStatement().isProfile()) {
                return executeWithProfile(cypher, ast, null);
            }

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
    
    public String executeRaw(String cypher) {
        try {
            Program ast = parser.parseCached(cypher);
            return JsonUtil.toJson(executeQuery(ast, null));
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

            if (ast.getStatement() != null && ast.getStatement().isExplain()) {
                return buildExplainResponse(ast);
            }

            if (ast.getStatement() != null && ast.getStatement().isProfile()) {
                return executeWithProfile(cypher, ast, params);
            }

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
    
    public String executeRaw(String cypher, Map<String, Object> params) {
        try {
            Program ast = parser.parseCached(cypher);
            return JsonUtil.toJson(executeQuery(ast, params));
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

    private void ensureExecuteRecordsMode(Program ast) {
        if (ast.getStatement() != null && ast.getStatement().isExplain()) {
            throw new GraphQueryException(ErrorCode.QUERY_EXECUTION_ERROR, "EXPLAIN not supported in executeRecords mode");
        }
        if (ast.getStatement() != null && ast.getStatement().isProfile()) {
            throw new GraphQueryException(ErrorCode.QUERY_EXECUTION_ERROR, "PROFILE not supported in executeRecords mode");
        }
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
    
    private String buildExplainResponse(Program ast) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "explain");
        response.put("originalCypher", ast.toCypher());
        
        try {
            ExecutionPlan plan = rewriter.rewrite(ast);
            
            List<Map<String, Object>> physicalQueries = new ArrayList<>();
            for (PhysicalQuery pq : plan.getPhysicalQueries()) {
                Map<String, Object> pqInfo = new LinkedHashMap<>();
                pqInfo.put("id", pq.getId());
                pqInfo.put("cypher", pq.getCypher());
                physicalQueries.add(pqInfo);
            }
            
            List<Map<String, Object>> externalQueries = new ArrayList<>();
            for (ExternalQuery eq : plan.getExternalQueries()) {
                Map<String, Object> eqInfo = new LinkedHashMap<>();
                eqInfo.put("id", eq.getId());
                eqInfo.put("dataSource", eq.getDataSource());
                eqInfo.put("operator", eq.getOperator());
                eqInfo.put("inputIds", eq.getInputIds());
                externalQueries.add(eqInfo);
            }
            
            response.put("physicalQueries", physicalQueries);
            response.put("externalQueries", externalQueries);
            response.put("hasVirtualElements", plan.hasVirtualElements());
            
            return JsonUtil.toJson(response);
        } catch (GraphQueryException e) {
            throw e;
        } catch (Exception e) {
            throw new GraphQueryException(ErrorCode.QUERY_EXECUTION_ERROR, e.getMessage(), e);
        }
    }

    private String executeWithProfile(String cypher, Program ast, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        try {
            ExecutionPlan plan = rewriter.rewrite(ast);
            
            if (params != null && !params.isEmpty()) {
                for (PhysicalQuery pq : plan.getPhysicalQueries()) {
                    pq.getParameters().putAll(params);
                }
            }

            ExecutionResult execResult = executor.execute(plan).join();

            long executionTime = System.currentTimeMillis() - startTime;

            List<Map<String, Object>> results = resultConverter.buildResults(ast, execResult);

            results = pendingFilterApplier.apply(results, plan.getGlobalContext().getPendingFilters());

            results = globalSorter.applySortAndPagination(results, plan.getGlobalContext());

            results = applyDeduplication(results, ast);

            Map<String, Object> profileResponse = new LinkedHashMap<>();
            profileResponse.put("type", "profile");
            profileResponse.put("originalCypher", cypher);
            profileResponse.put("executionTimeMs", executionTime);
            profileResponse.put("resultCount", results.size());
            profileResponse.put("results", results);

            return JsonUtil.toJson(profileResponse);
        } catch (GraphQueryException e) {
            throw e;
        } catch (CompletionException e) {
            throw unwrapAsyncException(e);
        } catch (Exception e) {
            throw new GraphQueryException(ErrorCode.QUERY_EXECUTION_ERROR, e.getMessage(), e);
        }
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
