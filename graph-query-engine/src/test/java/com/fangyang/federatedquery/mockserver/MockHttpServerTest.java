package com.fangyang.federatedquery.mockserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fangyang.common.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MockHttpServerTest {
    
    private MockHttpServer server;
    private HttpClient httpClient;
    private int port;
    
    @BeforeEach
    void setUp() throws IOException {
        port = 18080;
        server = new MockHttpServer(port);
        server.start();
        
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }
    
    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }
    
    @Test
    void testQueryAlarmsByMedn() throws Exception {
        String medn = "388581df-50a3-4d9f-96d4-15faf36f9caf";
        String url = server.getAlarmEndpoint() + "?medn=" + medn;
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(200, response.statusCode(), "Response status should be 200");
        
        JsonNode jsonNode = JsonUtil.readTree(response.body());
        assertTrue(jsonNode.isArray(), "Response should be an array");
        assertEquals(2, jsonNode.size(), "Should return 2 alarms for NE001");
        
        JsonNode firstAlarm = jsonNode.get(0);
        assertEquals("ALARM001", firstAlarm.get("MENAME").asText(), "First alarm MENAME should be ALARM001");
        assertEquals(medn, firstAlarm.get("MEDN").asText(), "First alarm MEDN should match");
    }
    
    @Test
    void testQueryAlarmsByMednWithProjection() throws Exception {
        String medn = "388581df-50a3-4d9f-96d4-15faf36f9caf";
        String url = server.getAlarmEndpoint() + "?medn=" + medn + "&fields=MENAME,OCCURTIME";
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(200, response.statusCode(), "Response status should be 200");
        
        JsonNode jsonNode = JsonUtil.readTree(response.body());
        assertTrue(jsonNode.isArray(), "Response should be an array");
        assertEquals(2, jsonNode.size(), "Should return 2 alarms");
        
        JsonNode firstAlarm = jsonNode.get(0);
        assertEquals(2, firstAlarm.size(), "Should only have 2 fields after projection");
        assertTrue(firstAlarm.has("MENAME"), "Should have MENAME field");
        assertTrue(firstAlarm.has("OCCURTIME"), "Should have OCCURTIME field");
        assertFalse(firstAlarm.has("CSN"), "Should not have CSN field after projection");
    }
    
    @Test
    void testQueryAlarmsWithTimestamp() throws Exception {
        String medn = "388581df-50a3-4d9f-96d4-15faf36f9caf";
        long timestamp = 1775825400000L;
        String url = server.getAlarmEndpoint() + "?medn=" + medn + "&timestamp=" + timestamp + "&strategy=latest";
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(200, response.statusCode(), "Response status should be 200");
        
        JsonNode jsonNode = JsonUtil.readTree(response.body());
        assertTrue(jsonNode.isArray(), "Response should be an array");
    }
    
    @Test
    void testQueryAlarmsMissingMedn() throws Exception {
        String url = server.getAlarmEndpoint();
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(400, response.statusCode(), "Response status should be 400 for missing medn");
    }
    
    @Test
    void testQueryKpiByParentResId() throws Exception {
        String parentResId = "eccc2c94-6a31-45ea-a16e-0c709939cbe5";
        String url = server.getKpiEndpoint() + "?parentResId=" + parentResId;
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(200, response.statusCode(), "Response status should be 200");
        
        JsonNode jsonNode = JsonUtil.readTree(response.body());
        assertTrue(jsonNode.isArray(), "Response should be an array");
        assertEquals(1, jsonNode.size(), "Should return 1 KPI for NE001");
        
        JsonNode firstKpi = jsonNode.get(0);
        assertEquals("KPI001", firstKpi.get("name").asText(), "First KPI name should be KPI001");
        assertEquals(parentResId, firstKpi.get("parentResId").asText(), "First KPI parentResId should match");
    }
    
    @Test
    void testQueryKpi2ByParentResId() throws Exception {
        String parentResId = "db82ad76-8bdc-4f4b-96a0-a5e91c6861fb";
        String url = server.getKpiEndpoint() + "?parentResId=" + parentResId;
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(200, response.statusCode(), "Response status should be 200");
        
        JsonNode jsonNode = JsonUtil.readTree(response.body());
        assertTrue(jsonNode.isArray(), "Response should be an array");
        assertEquals(1, jsonNode.size(), "Should return 1 KPI2 for LTP001");
        
        JsonNode firstKpi = jsonNode.get(0);
        assertEquals("KPI2001", firstKpi.get("name").asText(), "First KPI2 name should be KPI2001");
        assertEquals(parentResId, firstKpi.get("parentResId").asText(), "First KPI2 parentResId should match");
    }
    
    @Test
    void testQueryKpiWithProjection() throws Exception {
        String parentResId = "eccc2c94-6a31-45ea-a16e-0c709939cbe5";
        String url = server.getKpiEndpoint() + "?parentResId=" + parentResId + "&fields=name,time";
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(200, response.statusCode(), "Response status should be 200");
        
        JsonNode jsonNode = JsonUtil.readTree(response.body());
        assertTrue(jsonNode.isArray(), "Response should be an array");
        assertEquals(1, jsonNode.size(), "Should return 1 KPI");
        
        JsonNode firstKpi = jsonNode.get(0);
        assertEquals(2, firstKpi.size(), "Should only have 2 fields after projection");
        assertTrue(firstKpi.has("name"), "Should have name field");
        assertTrue(firstKpi.has("time"), "Should have time field");
        assertFalse(firstKpi.has("resId"), "Should not have resId field after projection");
    }
    
    @Test
    void testQueryKpiWithTimestamp() throws Exception {
        String parentResId = "eccc2c94-6a31-45ea-a16e-0c709939cbe5";
        long timestamp = 1775825300000L;
        String url = server.getKpiEndpoint() + "?parentResId=" + parentResId + "&timestamp=" + timestamp + "&strategy=latest";
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(200, response.statusCode(), "Response status should be 200");
        
        JsonNode jsonNode = JsonUtil.readTree(response.body());
        assertTrue(jsonNode.isArray(), "Response should be an array");
    }
    
    @Test
    void testQueryKpiMissingParentResId() throws Exception {
        String url = server.getKpiEndpoint();
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(400, response.statusCode(), "Response status should be 400 for missing parentResId");
    }
    
    @Test
    void testPostRequestForAlarm() throws Exception {
        String medn = "a158b427-4a65-4adf-9096-3c8225519cca";
        String requestBody = "{\"medn\":\"" + medn + "\"}";
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.getAlarmEndpoint()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(200, response.statusCode(), "Response status should be 200");
        
        JsonNode jsonNode = JsonUtil.readTree(response.body());
        assertTrue(jsonNode.isArray(), "Response should be an array");
        assertEquals(1, jsonNode.size(), "Should return 1 alarm for NE002");
        
        JsonNode firstAlarm = jsonNode.get(0);
        assertEquals("ALARM003", firstAlarm.get("MENAME").asText(), "First alarm MENAME should be ALARM003");
        assertEquals(medn, firstAlarm.get("MEDN").asText(), "First alarm MEDN should match");
    }
}
