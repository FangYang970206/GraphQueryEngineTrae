package com.federatedquery.adapter;

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
            result.setError("No mock response registered for operator: " + query.getOperator());
            result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
            return result;
        }
        
        if (mock.isError()) {
            result.setError(mock.getErrorMessage());
            result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
            return result;
        }
        
        List<GraphEntity> entities = mock.execute(query);
        result.setEntities(entities);
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
        result.setSuccess(true);
        result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
        
        if (mock.getWarning() != null) {
            result.addWarning("MOCK_WARNING", mock.getWarning());
        }
        
        return result;
    }
    
    @Override
    public boolean isHealthy() {
        return true;
    }
    
    public static class MockResponse {
        private List<GraphEntity> entities = new ArrayList<>();
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
