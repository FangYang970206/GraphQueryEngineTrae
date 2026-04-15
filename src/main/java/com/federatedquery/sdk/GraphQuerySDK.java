package com.federatedquery.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.federatedquery.adapter.GraphEntity;
import com.federatedquery.adapter.QueryResult;
import com.federatedquery.aggregator.*;
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
import com.federatedquery.metadata.VirtualEdgeBinding;
import com.federatedquery.parser.CypherParserFacade;
import com.federatedquery.plan.ExecutionPlan;
import com.federatedquery.plan.GlobalContext;
import com.federatedquery.plan.PhysicalQuery;
import com.federatedquery.plan.ExternalQuery;
import com.federatedquery.rewriter.QueryRewriter;
import com.federatedquery.util.JsonUtil;
import com.federatedquery.executor.FederatedExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

@Component
public class GraphQuerySDK {
    private static final Logger log = LoggerFactory.getLogger(GraphQuerySDK.class);

    @Autowired
    private CypherParserFacade parser;
    @Autowired
    private QueryRewriter rewriter;
    @Autowired
    private FederatedExecutor executor;
    @Autowired
    private UnionDeduplicator deduplicator;
    @Autowired(required = false)
    private MetadataRegistry registry;
    @Autowired(required = false)
    private TuGraphConnector tugraphConnector;

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
    }
    
    public List<Map<String, Object>> executeRecords(String cypher) {
        try {
            Program ast = parser.parseCached(cypher);
            ensureExecuteRecordsMode(ast);
            return RecordConverter.convertToRecordMaps(executeQuery(ast));
        } catch (GraphQueryException e) {
            throw e;
        } catch (CompletionException e) {
            throw unwrapAsyncException(e);
        } catch (Exception e) {
            throw new GraphQueryException(ErrorCode.QUERY_EXECUTION_ERROR, e.getMessage(), e);
        }
    }
    
    public List<Map<String, Object>> executeRecords(String cypher, Map<String, Object> params) {
        String resolvedCypher = resolveParameters(cypher, params);
        return executeRecords(resolvedCypher);
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
                return executeWithProfile(cypher, ast);
            }

            List<Map<String, Object>> results = executeQuery(ast);
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
            return JsonUtil.toJson(executeQuery(ast));
        } catch (GraphQueryException e) {
            throw e;
        } catch (CompletionException e) {
            throw unwrapAsyncException(e);
        } catch (Exception e) {
            throw new GraphQueryException(ErrorCode.QUERY_EXECUTION_ERROR, e.getMessage(), e);
        }
    }
    
    public String execute(String cypher, Map<String, Object> params) {
        String resolvedCypher = resolveParameters(cypher, params);
        return execute(resolvedCypher);
    }
    
    public String executeRaw(String cypher, Map<String, Object> params) {
        String resolvedCypher = resolveParameters(cypher, params);
        return executeRaw(resolvedCypher);
    }

    private List<Map<String, Object>> executeQuery(Program ast) {
        ExecutionPlan plan = rewriter.rewrite(ast);
        ExecutionResult execResult = executor.execute(plan).join();

        List<Map<String, Object>> results = buildTuGraphFormatResults(ast, execResult);
        results = applyPendingFilters(results, plan.getGlobalContext().getPendingFilters());
        results = applyGlobalSortAndPagination(results, plan.getGlobalContext());
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
    
    private String resolveParameters(String cypher, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return cypher;
        }
        
        String result = cypher;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String paramName = entry.getKey();
            
            if (!isValidParameterName(paramName)) {
                log.warn("Invalid parameter name: {}, skipping", paramName);
                continue;
            }
            
            String placeholder = "$" + paramName;
            String value = formatParameterValue(entry.getValue());
            result = result.replace(placeholder, value);
        }
        
        return result;
    }
    
    private boolean isValidParameterName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        
        if (!name.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            return false;
        }
        
        if (name.length() > 128) {
            return false;
        }
        
        return true;
    }
    
    private String formatParameterValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof String) {
            String str = (String) value;
            str = str.replace("\\", "\\\\");
            str = str.replace("'", "\\'");
            str = str.replace("\"", "\\\"");
            str = str.replace("\n", "\\n");
            str = str.replace("\r", "\\r");
            str = str.replace("\t", "\\t");
            return "'" + str + "'";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Collection) {
            Collection<?> collection = (Collection<?>) value;
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(formatParameterValue(item));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(formatParameterValue(entry.getKey()));
                sb.append(": ");
                sb.append(formatParameterValue(entry.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        String str = value.toString();
        str = str.replace("\\", "\\\\");
        str = str.replace("'", "\\'");
        return "'" + str + "'";
    }
    
    private List<Map<String, Object>> applyPendingFilters(List<Map<String, Object>> results, List<GlobalContext.WhereCondition> pendingFilters) {
        if (pendingFilters == null || pendingFilters.isEmpty()) {
            return results;
        }
        
        List<Map<String, Object>> filtered = new ArrayList<>();
        
        for (Map<String, Object> row : results) {
            if (matchesAllConditions(row, pendingFilters)) {
                filtered.add(row);
            }
        }
        
        return filtered;
    }
    
    private boolean matchesAllConditions(Map<String, Object> row, List<GlobalContext.WhereCondition> conditions) {
        for (GlobalContext.WhereCondition condition : conditions) {
            if (!matchesCondition(row, condition)) {
                return false;
            }
        }
        return true;
    }
    
    private boolean matchesCondition(Map<String, Object> row, GlobalContext.WhereCondition condition) {
        String variable = condition.getVariable();
        String property = condition.getProperty();
        String operator = condition.getOperator();
        Object expectedValue = condition.getValue();
        
        Object entityObj = row.get(variable);
        if (entityObj == null) {
            return false;
        }
        
        Object actualValue = null;
        if (entityObj instanceof Map) {
            Map<String, Object> entityMap = (Map<String, Object>) entityObj;
            actualValue = entityMap.get(property);
        } else if (entityObj instanceof GraphEntity) {
            GraphEntity entity = (GraphEntity) entityObj;
            actualValue = entity.getProperty(property);
        }
        
        return evaluateCondition(actualValue, operator, expectedValue);
    }
    
    private boolean evaluateCondition(Object actualValue, String operator, Object expectedValue) {
        if (actualValue == null && expectedValue == null) {
            return "=".equals(operator) || "IS".equals(operator);
        }
        if (actualValue == null) {
            return false;
        }
        
        switch (operator) {
            case "=":
            case "==":
                return actualValue.equals(expectedValue) || 
                       (actualValue instanceof Number && expectedValue instanceof Number &&
                        ((Number) actualValue).doubleValue() == ((Number) expectedValue).doubleValue());
            case "<>":
            case "!=":
                return !actualValue.equals(expectedValue);
            case ">":
                return compareValues(actualValue, expectedValue) > 0;
            case ">=":
                return compareValues(actualValue, expectedValue) >= 0;
            case "<":
                return compareValues(actualValue, expectedValue) < 0;
            case "<=":
                return compareValues(actualValue, expectedValue) <= 0;
            case "IN":
                if (expectedValue instanceof Collection) {
                    return ((Collection<?>) expectedValue).contains(actualValue);
                }
                return false;
            case "CONTAINS":
                return actualValue.toString().contains(expectedValue.toString());
            case "STARTS WITH":
                return actualValue.toString().startsWith(expectedValue.toString());
            case "ENDS WITH":
                return actualValue.toString().endsWith(expectedValue.toString());
            case "IS NULL":
                return actualValue == null;
            case "IS NOT NULL":
                return actualValue != null;
            default:
                return actualValue.equals(expectedValue);
        }
    }
    
    private int compareValues(Object v1, Object v2) {
        if (v1 instanceof Comparable && v2 instanceof Comparable) {
            if (v1 instanceof Number && v2 instanceof Number) {
                return Double.compare(((Number) v1).doubleValue(), ((Number) v2).doubleValue());
            }
            return ((Comparable) v1).compareTo(v2);
        }
        return v1.toString().compareTo(v2.toString());
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
    
    private List<Map<String, Object>> applyGlobalSortAndPagination(List<Map<String, Object>> results, GlobalContext context) {
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
    
    private List<Map<String, Object>> buildTuGraphFormatResults(Program ast, ExecutionResult execResult) {
        List<Map<String, Object>> results = new ArrayList<>();
        
        List<ReturnInfo> returnInfos = getReturnInfos(ast);
        Map<String, List<GraphEntity>> entitiesByVarName = collectEntitiesByVarName(execResult, false, true);
        List<QueryResult.ResultRow> rows = collectRows(execResult, false, false);
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
                    log.debug("Building path results for variable: {}, entitiesByVarName: {}", info.variableName, entitiesByVarName.keySet());
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

    private List<List<Map<String, Object>>> buildPathsFromEntities(List<PathInfo> pathInfos, Map<String, List<GraphEntity>> entitiesByVarName) {
        List<List<Map<String, Object>>> allPaths = new ArrayList<>();
        if (pathInfos.isEmpty()) {
            return allPaths;
        }

        Map<String, List<GraphEntity>> normalizedEntitiesByVarName = deduplicateEntitiesByVarName(entitiesByVarName);
        for (PathInfo pathInfo : pathInfos) {
            if (pathInfo.patternElement == null) {
                continue;
            }
            
            NodePattern startNode = pathInfo.patternElement.getNodePattern();
            String startVar = startNode != null ? startNode.getVariable() : null;
            
            if (startVar == null) {
                continue;
            }
            
            List<GraphEntity> startEntities = normalizedEntitiesByVarName.get(startVar);
            if (startEntities == null || startEntities.isEmpty()) {
                continue;
            }
            
            for (GraphEntity startEntity : startEntities) {
                List<Map<String, Object>> basePath = new ArrayList<>();
                basePath.add(buildNodeElement(startEntity));
                buildPathVariantsRecursive(pathInfo.patternElement.getChains(), 0, basePath, normalizedEntitiesByVarName, allPaths);
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

        Map<String, List<GraphEntity>> normalizedEntitiesByVarName = deduplicateEntitiesByVarName(entitiesByVarName);
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
                        pathInfo.patternElement.getChains(),
                        0,
                        basePath,
                        normalizedEntitiesByVarName,
                        row,
                        allPaths);
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
    
    private List<GraphEntity> filterEntitiesByLinkage(List<GraphEntity> entities, List<Map<String, Object>> currentPath, String edgeType) {
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
        return registry != null && edgeType != null && registry.isVirtualEdge(edgeType);
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
        return registry != null ? registry.getTargetLabelForEdge(edgeType) : null;
    }

    private VirtualEdgeBinding getVirtualEdgeBinding(String edgeType) {
        if (registry == null || edgeType == null) {
            return null;
        }
        return registry.getVirtualEdgeBinding(edgeType).orElse(null);
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
        if (useLabelMetadata && registry != null && label != null) {
            return registry.getLabel(label)
                    .map(metadata -> metadata.getIdProperty() != null ? metadata.getIdProperty() : "id")
                    .orElse("id");
        }
        return "id";
    }

    private String normalizeMappedValue(Object value) {
        return value == null ? null : value.toString();
    }
    
    private List<GraphEntity> filterEntitiesByLabel(List<GraphEntity> entities, String expectedLabel) {
        if (expectedLabel == null) {
            return entities;
        }
        List<GraphEntity> filtered = new ArrayList<>();
        for (GraphEntity entity : entities) {
            if (expectedLabel.equals(entity.getLabel())) {
                filtered.add(entity);
            }
        }
        return filtered;
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

    private String executeWithProfile(String cypher, Program ast) {
        long startTime = System.currentTimeMillis();

        try {
            ExecutionPlan plan = rewriter.rewrite(ast);

            ExecutionResult execResult = executor.execute(plan).join();

            long executionTime = System.currentTimeMillis() - startTime;

            List<Map<String, Object>> results = buildTuGraphFormatResults(ast, execResult);

            results = applyPendingFilters(results, plan.getGlobalContext().getPendingFilters());

            results = applyGlobalSortAndPagination(results, plan.getGlobalContext());

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
