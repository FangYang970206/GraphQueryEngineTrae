package com.fangyang.federatedquery.adapter;

import com.fangyang.datasource.DataSourceAdapter;
import com.fangyang.datasource.DataSourceQueryParams;
import com.fangyang.federatedquery.model.GraphEntity;
import com.fangyang.federatedquery.model.QueryResult;
import com.fangyang.federatedquery.exception.ErrorCode;
import com.fangyang.federatedquery.exception.GraphQueryException;
import com.fangyang.federatedquery.plan.ExternalQuery;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.mockito.Mockito;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class MockExternalAdapter implements DataSourceAdapter {
    private final Map<String, MockResponse> responses = new HashMap<>();
    private String dataSourceName = "mock";
    private int defaultDelayMs = 0;
    
    public void registerResponse(String operator, MockResponse response) {
        responses.put(operator, response);
    }
    
    public void clearResponses() {
        responses.clear();
    }
    
    public void setDefaultDelayMs(int delayMs) {
        this.defaultDelayMs = delayMs;
    }
    
    @Override
    public String getDataSourceType() {
        return "MOCK";
    }
    
    @Override
    public String getDataSourceName() {
        return dataSourceName;
    }
    
    public void setDataSourceName(String name) {
        this.dataSourceName = name;
    }
    
    public CompletableFuture<QueryResult> execute(ExternalQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                int delay = defaultDelayMs;
                MockResponse mock = responses.get(query.getOperator());
                if (mock != null && mock.getDelayMs() > 0) {
                    delay = mock.getDelayMs();
                }
                if (delay > 0) {
                    Thread.sleep(delay);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return executeSync(query);
        });
    }
    
    public QueryResult executeSync(ExternalQuery query) {
        long startTime = System.currentTimeMillis();
        QueryResult result = new QueryResult();
        result.setDataSource(dataSourceName);
        
        MockResponse mock = responses.get(query.getOperator());
        
        if (mock == null) {
            throw new GraphQueryException(ErrorCode.EXTERNAL_DATASOURCE_ERROR, 
                    "No mock response registered for operator: " + query.getOperator());
        }
        
        if (mock.isError()) {
            throw new GraphQueryException(ErrorCode.EXTERNAL_DATASOURCE_ERROR, 
                    mock.getErrorMessage());
        }
        
        List<GraphEntity> entities = mock.execute(query);
        if (mock.hasGenerator()) {
            entities = filterEntities(query, entities);
        }
        result.setEntities(entities);
        if (!mock.getRows().isEmpty()) {
            result.setRows(mock.copyRows(query.getId() != null ? query.getId() : "mock"));
        } else if (mock.hasGenerator()) {
            for (int i = 0; i < entities.size(); i++) {
                GraphEntity entity = entities.get(i);
                QueryResult.ResultRow row = new QueryResult.ResultRow();
                row.setRowId(query.getId() != null ? query.getId() + "#" + i : "mock#" + i);
                if (entity.getVariableName() != null && !entity.getVariableName().isEmpty()) {
                    row.put(entity.getVariableName(), entity);
                }
                if (!row.getEntitiesByVariable().isEmpty()) {
                    result.addRow(row);
                }
            }
        } else {
            QueryResult.ResultRow row = new QueryResult.ResultRow();
            row.setRowId(query.getId() != null ? query.getId() + "#0" : "mock#0");
            for (GraphEntity entity : entities) {
                if (entity.getVariableName() != null && !entity.getVariableName().isEmpty()) {
                    row.put(entity.getVariableName(), entity);
                }
            }
            if (!row.getEntitiesByVariable().isEmpty()) {
                result.addRow(row);
            }
        }
        result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
        
        return result;
    }

    private List<GraphEntity> filterEntities(ExternalQuery query, List<GraphEntity> entities) {
        if (query == null || query.getFilters() == null || query.getFilters().isEmpty() || entities == null) {
            return entities;
        }

        List<GraphEntity> filtered = new ArrayList<>();
        for (GraphEntity entity : entities) {
            if (entity == null) {
                continue;
            }

            boolean matches = true;
            for (Map.Entry<String, Object> filter : query.getFilters().entrySet()) {
                String key = filter.getKey();
                Object expected = filter.getValue();
                if (expected == null) {
                    continue;
                }

                if ("_label".equals(key)) {
                    if (!expected.equals(entity.getLabel())) {
                        matches = false;
                        break;
                    }
                    continue;
                }

                Object actual = entity.getProperties() != null ? entity.getProperties().get(key) : null;
                if (actual == null && "name".equals(key) && entity.getProperties() != null) {
                    actual = entity.getProperties().get("MENAME");
                }
                if (actual == null || !expected.equals(actual)) {
                    matches = false;
                    break;
                }
            }

            if (matches) {
                filtered.add(entity);
            }
        }

        return filtered;
    }
    
    @Override
    public boolean isHealthy() {
        return true;
    }
    
    @Override
    public List<Record> executeTuGraphQuery(String cypher) {
        return executeMockTuGraphQuery(cypher, Collections.emptyMap());
    }
    
    @Override
    public List<Record> executeTuGraphQuery(String cypher, Map<String, Object> params) {
        return executeMockTuGraphQuery(cypher, params);
    }
    
    @Override
    public List<Map<String, Object>> executeExternalQuery(DataSourceQueryParams params) {
        MockResponse mock = responses.get(params.getOperator());
        
        if (mock == null) {
            throw new GraphQueryException(ErrorCode.EXTERNAL_DATASOURCE_ERROR, 
                    "No mock response registered for operator: " + params.getOperator());
        }
        
        if (mock.isError()) {
            throw new GraphQueryException(ErrorCode.EXTERNAL_DATASOURCE_ERROR, 
                    mock.getErrorMessage());
        }

        ExternalQuery query = convertParamsToQuery(params);
        List<GraphEntity> entities = mock.generateFromParams(params);
        if (entities.isEmpty() && mock.hasGenerator()) {
            entities = mock.execute(query);
        }
        entities = filterEntities(query, entities);

        List<Map<String, Object>> result = new ArrayList<>();
        for (GraphEntity entity : entities) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", entity.getId());
            map.put("label", entity.getLabel());
            if (entity.getProperties() != null) {
                map.putAll(entity.getProperties());
            }
            result.add(map);
        }
        return result;
    }
    
    private ExternalQuery convertParamsToQuery(DataSourceQueryParams params) {
        ExternalQuery query = new ExternalQuery();
        query.setId(UUID.randomUUID().toString());
        query.setDataSource(params.getDataSource());
        query.setOperator(params.getOperator());
        query.setTargetLabel(params.getTargetLabel());
        if (params.getInputIds() != null) {
            query.getInputIds().addAll(params.getInputIds());
        }
        query.setInputIdField(params.getInputIdField());
        query.setOutputIdField(params.getOutputIdField());
        if (params.getOutputVariables() != null) {
            query.getOutputVariables().addAll(params.getOutputVariables());
        }
        query.setOutputFields(params.getOutputFields());
        if (params.getFilters() != null) {
            query.getFilters().putAll(params.getFilters());
        }
        if (params.getParameters() != null) {
            query.getParameters().putAll(params.getParameters());
        }
        return query;
    }

    private List<Record> executeMockTuGraphQuery(String cypher, Map<String, Object> params) {
        MockResponse mock = responses.get("cypher");
        if (mock == null) {
            throw new GraphQueryException(ErrorCode.DATASOURCE_QUERY_ERROR,
                    "No mock response registered for operator: cypher");
        }
        if (mock.isError()) {
            throw new GraphQueryException(ErrorCode.DATASOURCE_QUERY_ERROR, mock.getErrorMessage());
        }

        List<QueryResult.ResultRow> rows = buildTuGraphRows(mock);
        List<Record> records = new ArrayList<>();
        for (QueryResult.ResultRow row : rows) {
            if (row == null || row.getEntitiesByVariable() == null || row.getEntitiesByVariable().isEmpty()) {
                continue;
            }
            records.add(createMockRecord(row.getEntitiesByVariable()));
        }
        return records;
    }

    private List<QueryResult.ResultRow> buildTuGraphRows(MockResponse mock) {
        if (!mock.getRows().isEmpty()) {
            return mock.copyRows("cypher");
        }

        List<GraphEntity> entities = mock.execute(new ExternalQuery());
        if (entities == null || entities.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, Integer> countsByVariable = new HashMap<>();
        for (GraphEntity entity : entities) {
            if (entity != null && entity.getVariableName() != null && !entity.getVariableName().isEmpty()) {
                countsByVariable.merge(entity.getVariableName(), 1, Integer::sum);
            }
        }

        boolean hasDuplicateVariables = countsByVariable.values().stream().anyMatch(count -> count > 1);
        List<QueryResult.ResultRow> rows = new ArrayList<>();
        if (hasDuplicateVariables) {
            int rowIndex = 0;
            for (GraphEntity entity : entities) {
                if (entity == null || entity.getVariableName() == null || entity.getVariableName().isEmpty()) {
                    continue;
                }
                QueryResult.ResultRow row = new QueryResult.ResultRow();
                row.setRowId("cypher#" + rowIndex++);
                row.put(entity.getVariableName(), entity);
                rows.add(row);
            }
            return rows;
        }

        QueryResult.ResultRow row = new QueryResult.ResultRow();
        row.setRowId("cypher#0");
        for (GraphEntity entity : entities) {
            if (entity != null && entity.getVariableName() != null && !entity.getVariableName().isEmpty()) {
                row.put(entity.getVariableName(), entity);
            }
        }
        if (!row.getEntitiesByVariable().isEmpty()) {
            rows.add(row);
        }
        return rows;
    }

    private Record createMockRecord(Map<String, GraphEntity> entitiesByVariable) {
        Record record = Mockito.mock(Record.class);
        List<String> keys = new ArrayList<>(entitiesByVariable.keySet());
        Mockito.when(record.keys()).thenReturn(keys);
        for (Map.Entry<String, GraphEntity> entry : entitiesByVariable.entrySet()) {
            Value value = createMockValue(entry.getValue());
            Mockito.when(record.get(entry.getKey())).thenReturn(value);
        }
        return record;
    }

    private Value createMockValue(GraphEntity entity) {
        Value value = Mockito.mock(Value.class);
        Mockito.when(value.isNull()).thenReturn(false);

        if (entity != null && entity.getType() == GraphEntity.EntityType.EDGE) {
            Relationship relationship = Mockito.mock(Relationship.class);
            Mockito.when(relationship.id()).thenReturn(toLongId(entity.getId()));
            Mockito.when(relationship.type()).thenReturn(entity.getLabel());
            Mockito.when(relationship.startNodeId()).thenReturn(toLongId(entity.getStartNodeId()));
            Mockito.when(relationship.endNodeId()).thenReturn(toLongId(entity.getEndNodeId()));
            Mockito.when(relationship.asMap()).thenReturn(copyProperties(entity));
            Mockito.when(value.asEntity()).thenReturn(relationship);
            Mockito.when(value.asRelationship()).thenReturn(relationship);
            return value;
        }

        Node node = Mockito.mock(Node.class);
        Mockito.when(node.id()).thenReturn(toLongId(entity != null ? entity.getId() : null));
        Mockito.when(node.labels()).thenReturn(entity != null && entity.getLabel() != null
                ? List.of(entity.getLabel())
                : List.of());
        Mockito.when(node.asMap()).thenReturn(copyProperties(entity));
        Mockito.when(value.asEntity()).thenReturn(node);
        Mockito.when(value.asNode()).thenReturn(node);
        return value;
    }

    private Map<String, Object> copyProperties(GraphEntity entity) {
        Map<String, Object> props = entity != null && entity.getProperties() != null
                ? new LinkedHashMap<>(entity.getProperties())
                : new LinkedHashMap<>();
        if (entity != null && entity.getType() == GraphEntity.EntityType.NODE
                && entity.getId() != null && !props.containsKey("id")) {
            props.put("id", entity.getId());
        }
        return props;
    }

    private long toLongId(String rawId) {
        if (rawId == null || rawId.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException ignored) {
            return Integer.toUnsignedLong(rawId.hashCode());
        }
    }
    
    public static class MockResponse {
        private List<GraphEntity> entities = new ArrayList<>();
        private List<QueryResult.ResultRow> rows = new ArrayList<>();
        private int delayMs = 0;
        private boolean error = false;
        private String errorMessage;
        private String warning;
        private ResponseGenerator generator;
        
        public MockResponse entities(List<GraphEntity> entities) {
            this.entities = entities;
            return this;
        }
        
        public MockResponse addEntity(GraphEntity entity) {
            this.entities.add(entity);
            return this;
        }

        public MockResponse addRowEntities(GraphEntity... rowEntities) {
            QueryResult.ResultRow row = new QueryResult.ResultRow();
            row.setRowId("mock-row-" + rows.size());
            for (GraphEntity entity : rowEntities) {
                this.entities.add(entity);
                if (entity.getVariableName() != null && !entity.getVariableName().isEmpty()) {
                    row.put(entity.getVariableName(), entity);
                }
            }
            if (!row.getEntitiesByVariable().isEmpty()) {
                this.rows.add(row);
            }
            return this;
        }
        
        public MockResponse delay(int delayMs) {
            this.delayMs = delayMs;
            return this;
        }
        
        public MockResponse error(String message) {
            this.error = true;
            this.errorMessage = message;
            return this;
        }
        
        public MockResponse warning(String warning) {
            this.warning = warning;
            return this;
        }
        
        public MockResponse generator(ResponseGenerator generator) {
            this.generator = generator;
            return this;
        }
        
        public List<GraphEntity> execute(ExternalQuery query) {
            if (generator != null) {
                return generator.generate(query);
            }
            return new ArrayList<>(entities);
        }
        
        public List<GraphEntity> generateFromParams(DataSourceQueryParams params) {
            if (generator != null) {
                return generator.generateFromParams(params);
            }
            return new ArrayList<>(entities);
        }
        
        public int getDelayMs() {
            return delayMs;
        }

        public List<QueryResult.ResultRow> getRows() {
            return rows;
        }

        public boolean hasGenerator() {
            return generator != null;
        }

        public List<QueryResult.ResultRow> copyRows(String idPrefix) {
            List<QueryResult.ResultRow> copiedRows = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                QueryResult.ResultRow source = rows.get(i);
                QueryResult.ResultRow copy = new QueryResult.ResultRow();
                copy.setRowId(idPrefix + "#row-" + i);
                copy.getEntitiesByVariable().putAll(source.getEntitiesByVariable());
                copiedRows.add(copy);
            }
            return copiedRows;
        }
        
        public boolean isError() {
            return error;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
        
        public String getWarning() {
            return warning;
        }
        
        public static MockResponse create() {
            return new MockResponse();
        }
        
        public static MockResponse withDelay(int delayMs) {
            return new MockResponse().delay(delayMs);
        }
        
        public static MockResponse withError(String message) {
            return new MockResponse().error(message);
        }
    }
    
    @FunctionalInterface
    public interface ResponseGenerator {
        List<GraphEntity> generate(ExternalQuery query);
        
        default List<GraphEntity> generateFromParams(DataSourceQueryParams params) {
            return new ArrayList<>();
        }
    }
}
