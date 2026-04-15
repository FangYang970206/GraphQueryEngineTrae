package com.federatedquery.adapter;

import com.federatedquery.exception.ErrorCode;
import com.federatedquery.exception.GraphQueryException;
import com.federatedquery.plan.ExternalQuery;

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
    
    @Override
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
    
    @Override
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
    }
}
