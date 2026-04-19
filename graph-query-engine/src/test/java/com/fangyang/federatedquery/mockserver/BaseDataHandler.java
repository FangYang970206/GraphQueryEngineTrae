package com.fangyang.federatedquery.mockserver;

import com.fangyang.common.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public abstract class BaseDataHandler implements HttpHandler {

    protected final MockDataRepository repository;

    protected BaseDataHandler(MockDataRepository repository) {
        this.repository = repository;
    }

    protected abstract Logger getLogger();
    protected abstract String getRequiredParamName();
    protected abstract String getLogPrefix();
    protected abstract List<Map<String, Object>> queryData(String keyParam, Long timestamp, String strategy);

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
            sendResponse(exchange, 405, "{\"error\": \"Method not allowed\"}");
            return;
        }

        try {
            Map<String, String> params = parseQueryParams(exchange);

            String keyParam = params.get(getRequiredParamName());
            if (keyParam == null || keyParam.isEmpty()) {
                sendResponse(exchange, 400, "{\"error\": \"Missing required parameter: " + getRequiredParamName() + "\"}");
                return;
            }

            Long timestamp = null;
            if (params.containsKey("timestamp")) {
                try {
                    timestamp = Long.parseLong(params.get("timestamp"));
                } catch (NumberFormatException e) {
                    sendResponse(exchange, 400, "{\"error\": \"Invalid timestamp format\"}");
                    return;
                }
            }

            String strategy = params.getOrDefault("strategy", "latest");

            List<String> projectionFields = Collections.emptyList();
            if (params.containsKey("fields")) {
                projectionFields = Arrays.asList(params.get("fields").split(","));
            }

            List<Map<String, Object>> results = queryData(keyParam, timestamp, strategy);

            if (!projectionFields.isEmpty()) {
                results = repository.projectFields(results, projectionFields);
            }

            String jsonResponse = JsonUtil.toJson(results);
            sendResponse(exchange, 200, jsonResponse);

            getLogger().info("{} query: {}={}, timestamp={}, strategy={}, fields={}, resultCount={}",
                    getLogPrefix(), getRequiredParamName(), keyParam, timestamp, strategy, projectionFields, results.size());

        } catch (Exception e) {
            getLogger().error("Error handling {} request", getLogPrefix(), e);
            sendResponse(exchange, 500, "{\"error\": \"Internal server error: " + e.getMessage() + "\"}");
        }
    }

    protected Map<String, String> parseQueryParams(HttpExchange exchange) throws IOException {
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
                    getLogger().warn("Failed to parse POST body as JSON", e);
                }
            }
        }

        return params;
    }

    protected void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}
