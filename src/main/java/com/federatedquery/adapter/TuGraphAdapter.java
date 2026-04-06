package com.federatedquery.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.federatedquery.metadata.DataSourceMetadata;
import com.federatedquery.plan.ExternalQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class TuGraphAdapter implements DataSourceAdapter {
    private static final Logger log = LoggerFactory.getLogger(TuGraphAdapter.class);
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private String endpoint;
    private String authToken;
    private int timeoutMs = 5000;
    
    public TuGraphAdapter() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    public TuGraphAdapter(String endpoint, String authToken) {
        this();
        this.endpoint = endpoint;
        this.authToken = authToken;
    }
    
    public void configure(DataSourceMetadata metadata) {
        this.endpoint = metadata.getEndpoint();
        this.timeoutMs = metadata.getTimeoutMs();
        Object token = metadata.getConfig().get("authToken");
        if (token != null) {
            this.authToken = token.toString();
        }
    }
    
    @Override
    public String getDataSourceType() {
        return "TUGRAPH";
    }
    
    @Override
    public String getDataSourceName() {
        return "tugraph";
    }
    
    @Override
    public CompletableFuture<QueryResult> execute(ExternalQuery query) {
        return CompletableFuture.supplyAsync(() -> executeSync(query));
    }
    
    @Override
    public QueryResult executeSync(ExternalQuery query) {
        long startTime = System.currentTimeMillis();
        QueryResult result = new QueryResult();
        result.setDataSource(getDataSourceName());
        
        try {
            String cypher = buildCypherQuery(query);
            log.debug("Executing Cypher on TuGraph: {}", cypher);
            
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("query", cypher);
            if (!query.getParameters().isEmpty()) {
                requestBody.put("parameters", query.getParameters());
            }
            
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/cypher"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            
            if (authToken != null) {
                requestBuilder.header("Authorization", "Bearer " + authToken);
            }
            
            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
            
            if (response.statusCode() == 200) {
                JsonNode responseJson = objectMapper.readTree(response.body());
                parseResponse(responseJson, result);
            } else {
                result.setError("TuGraph returned status " + response.statusCode() + ": " + response.body());
            }
            
        } catch (Exception e) {
            log.error("Failed to execute query on TuGraph", e);
            result.setError("Failed to execute query: " + e.getMessage());
        }
        
        return result;
    }
    
    private String buildCypherQuery(ExternalQuery query) {
        return "MATCH (n) RETURN n LIMIT 10";
    }
    
    private void parseResponse(JsonNode responseJson, QueryResult result) {
        JsonNode data = responseJson.get("data");
        if (data == null || !data.isArray()) {
            return;
        }
        
        for (JsonNode row : data) {
            parseRow(row, result);
        }
    }
    
    private void parseRow(JsonNode row, QueryResult result) {
        if (row.isObject()) {
            row.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value.has("_id")) {
                    GraphEntity entity = parseEntity(value);
                    result.addEntity(entity);
                }
            });
        }
    }
    
    private GraphEntity parseEntity(JsonNode node) {
        GraphEntity entity = new GraphEntity();
        
        if (node.has("_id")) {
            entity.setId(node.get("_id").asText());
        }
        
        if (node.has("_label")) {
            entity.setLabel(node.get("_label").asText());
        }
        
        String type = node.has("_type") ? node.get("_type").asText() : "node";
        entity.setType("edge".equalsIgnoreCase(type) ? 
                GraphEntity.EntityType.EDGE : GraphEntity.EntityType.NODE);
        
        if (entity.getType() == GraphEntity.EntityType.EDGE) {
            if (node.has("_from")) {
                entity.setStartNodeId(node.get("_from").asText());
            }
            if (node.has("_to")) {
                entity.setEndNodeId(node.get("_to").asText());
            }
        }
        
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (!key.startsWith("_")) {
                JsonNode value = entry.getValue();
                entity.setProperty(key, parseValue(value));
            }
        });
        
        return entity;
    }
    
    private Object parseValue(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        } else if (node.isInt()) {
            return node.asInt();
        } else if (node.isLong()) {
            return node.asLong();
        } else if (node.isDouble()) {
            return node.asDouble();
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else if (node.isNull()) {
            return null;
        }
        return node.toString();
    }
    
    @Override
    public boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
