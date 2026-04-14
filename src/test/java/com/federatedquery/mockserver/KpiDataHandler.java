package com.federatedquery.mockserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.federatedquery.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class KpiDataHandler implements HttpHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(KpiDataHandler.class);
    
    private final MockDataRepository repository;

    public KpiDataHandler(MockDataRepository repository) {
        this.repository = repository;
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        
        if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
            sendResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
            return;
        }
        
        try {
            Map<String, String> params = parseQueryParams(exchange);
            
            String parentResId = params.get("parentResId");
            if (parentResId == null || parentResId.isEmpty()) {
                sendResponse(exchange, 400, "{\"error\": \"Missing required parameter: parentResId\"}");
                return;
            }
            
            Long timestamp = null;
            String strategy = "latest";
            
            if (params.containsKey("timestamp")) {
                try {
                    timestamp = Long.parseLong(params.get("timestamp"));
                } catch (NumberFormatException e) {
                    sendResponse(exchange, 400, "{\"error\": \"Invalid timestamp format\"}");
                    return;
                }
            }
            
            if (params.containsKey("strategy")) {
                strategy = params.get("strategy");
            }
            
            List<String> projectionFields = new ArrayList<>();
            if (params.containsKey("fields")) {
                String fieldsParam = params.get("fields");
                projectionFields = Arrays.asList(fieldsParam.split(","));
            }
            
            List<Map<String, Object>> kpis;
            if (timestamp != null) {
                kpis = repository.queryKpiByParentResIdAndTime(parentResId, timestamp, strategy);
            } else {
                kpis = repository.queryKpiByParentResId(parentResId);
            }
            
            if (!projectionFields.isEmpty()) {
                kpis = repository.projectFields(kpis, projectionFields);
            }
            
            String jsonResponse = JsonUtil.toJson(kpis);
            sendResponse(exchange, 200, jsonResponse);
            
            logger.info("KPI query: parentResId={}, timestamp={}, strategy={}, fields={}, resultCount={}", 
                    parentResId, timestamp, strategy, projectionFields, kpis.size());
            
        } catch (Exception e) {
            logger.error("Error handling KPI request", e);
            sendResponse(exchange, 500, "{\"error\": \"Internal server error: " + e.getMessage() + "\"}");
        }
    }
    
    private Map<String, String> parseQueryParams(HttpExchange exchange) throws IOException {
        Map<String, String> params = new HashMap<>();
        
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2) {
                    String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                    String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    params.put(key, value);
                }
            }
        }
        
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body != null && !body.isEmpty()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> bodyParams = JsonUtil.readValue(body, Map.class);
                    for (Map.Entry<String, Object> entry : bodyParams.entrySet()) {
                        params.putIfAbsent(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                } catch (Exception e) {
                    logger.warn("Failed to parse POST body as JSON", e);
                }
            }
        }
        
        return params;
    }
    
    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}
