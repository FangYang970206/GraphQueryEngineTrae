package com.federatedquery.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.federatedquery.adapter.*;
import com.federatedquery.aggregator.*;
import com.federatedquery.executor.FederatedExecutor;
import com.federatedquery.metadata.*;
import com.federatedquery.parser.CypherParserFacade;
import com.federatedquery.reliability.OOMProtectionInterceptor;
import com.federatedquery.rewriter.QueryRewriter;
import com.federatedquery.rewriter.VirtualEdgeDetector;
import com.federatedquery.sdk.GraphQuerySDK;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class E2ETest {
    
    private MetadataRegistry registry;
    private MockExternalAdapter kpiAdapter;
    private MockExternalAdapter alarmAdapter;
    private MockExternalAdapter cardAdapter;
    private MockExternalAdapter tugraphAdapter;
    private GraphQuerySDK sdk;
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() {
        registry = new MetadataRegistryImpl();
        
        DataSourceMetadata tugraph = new DataSourceMetadata();
        tugraph.setName("tugraph");
        tugraph.setType(DataSourceType.TUGRAH_BOLT);
        tugraph.setEndpoint("bolt://localhost:7687");
        registry.registerDataSource(tugraph);
        
        DataSourceMetadata kpiService = new DataSourceMetadata();
        kpiService.setName("kpi-service");
        kpiService.setType(DataSourceType.REST_API);
        kpiService.setEndpoint("http://kpi-service:8080");
        registry.registerDataSource(kpiService);
        
        DataSourceMetadata alarmService = new DataSourceMetadata();
        alarmService.setName("alarm-service");
        alarmService.setType(DataSourceType.REST_API);
        alarmService.setEndpoint("http://alarm-service:8080");
        registry.registerDataSource(alarmService);
        
        DataSourceMetadata cardService = new DataSourceMetadata();
        cardService.setName("card-service");
        cardService.setType(DataSourceType.REST_API);
        cardService.setEndpoint("http://card-service:8080");
        registry.registerDataSource(cardService);
        
        VirtualEdgeBinding neHasKpi = new VirtualEdgeBinding();
        neHasKpi.setEdgeType("NEHasKPI");
        neHasKpi.setTargetDataSource("kpi-service");
        neHasKpi.setOperatorName("getKPIByNeIds");
        neHasKpi.setLastHopOnly(true);
        registry.registerVirtualEdge(neHasKpi);
        
        VirtualEdgeBinding neHasAlarms = new VirtualEdgeBinding();
        neHasAlarms.setEdgeType("NEHasAlarms");
        neHasAlarms.setTargetDataSource("alarm-service");
        neHasAlarms.setOperatorName("getAlarmsByNeIds");
        neHasAlarms.setLastHopOnly(true);
        registry.registerVirtualEdge(neHasAlarms);
        
        VirtualEdgeBinding ltpHasKpi2 = new VirtualEdgeBinding();
        ltpHasKpi2.setEdgeType("LTPHasKPI2");
        ltpHasKpi2.setTargetDataSource("kpi-service");
        ltpHasKpi2.setOperatorName("getKPI2ByLtpIds");
        ltpHasKpi2.setLastHopOnly(true);
        registry.registerVirtualEdge(ltpHasKpi2);
        
        VirtualEdgeBinding bornIn = new VirtualEdgeBinding();
        bornIn.setEdgeType("BORN_IN");
        bornIn.setTargetDataSource("card-service");
        bornIn.setOperatorName("getCardsByPersonIds");
        bornIn.setLastHopOnly(true);
        registry.registerVirtualEdge(bornIn);
        
        VirtualEdgeBinding cardLocateInNe = new VirtualEdgeBinding();
        cardLocateInNe.setEdgeType("CardLocateInNe");
        cardLocateInNe.setTargetDataSource("card-service");
        cardLocateInNe.setOperatorName("getCardByCardId");
        cardLocateInNe.setFirstHopOnly(true);
        registry.registerVirtualEdge(cardLocateInNe);
        
        LabelMetadata neLabel = new LabelMetadata();
        neLabel.setLabel("NetworkElement");
        neLabel.setVirtual(false);
        neLabel.setDataSource("tugraph");
        registry.registerLabel(neLabel);
        
        LabelMetadata ltpLabel = new LabelMetadata();
        ltpLabel.setLabel("LTP");
        ltpLabel.setVirtual(false);
        ltpLabel.setDataSource("tugraph");
        registry.registerLabel(ltpLabel);
        
        LabelMetadata personLabel = new LabelMetadata();
        personLabel.setLabel("Person");
        personLabel.setVirtual(false);
        personLabel.setDataSource("tugraph");
        registry.registerLabel(personLabel);
        
        LabelMetadata cardLabel = new LabelMetadata();
        cardLabel.setLabel("Card");
        cardLabel.setVirtual(true);
        cardLabel.setDataSource("card-service");
        registry.registerLabel(cardLabel);
        
        kpiAdapter = new MockExternalAdapter();
        kpiAdapter.setDataSourceName("kpi-service");
        
        alarmAdapter = new MockExternalAdapter();
        alarmAdapter.setDataSourceName("alarm-service");
        
        cardAdapter = new MockExternalAdapter();
        cardAdapter.setDataSourceName("card-service");
        
        tugraphAdapter = new MockExternalAdapter();
        tugraphAdapter.setDataSourceName("tugraph");
        
        CypherParserFacade parser = new CypherParserFacade();
        VirtualEdgeDetector detector = new VirtualEdgeDetector(registry);
        QueryRewriter rewriter = new QueryRewriter(registry, detector);
        FederatedExecutor executor = new FederatedExecutor(registry);
        executor.registerAdapter("kpi-service", kpiAdapter);
        executor.registerAdapter("alarm-service", alarmAdapter);
        executor.registerAdapter("card-service", cardAdapter);
        executor.registerAdapter("tugraph", tugraphAdapter);
        ResultStitcher stitcher = new ResultStitcher();
        GlobalSorter sorter = new GlobalSorter();
        UnionDeduplicator deduplicator = new UnionDeduplicator();
        OOMProtectionInterceptor oomProtection = new OOMProtectionInterceptor();
        
        sdk = new GraphQuerySDK(parser, rewriter, executor, stitcher, sorter, deduplicator, oomProtection);
        objectMapper = new ObjectMapper();
    }
    
    @Test
    @DisplayName("示例1: 多关系类型混合查询 - 返回TuGraph格式")
    void example1_MultipleRelTypesMixedQuery() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("ne");
        
        GraphEntity ltpEntity = createLTPEntity("ltp1", "LTP1", "Port");
        ltpEntity.setVariableName("target");
        
        GraphEntity kpiEntity = createKPIEntity("kpi1", "cpu_usage", 85.5);
        kpiEntity.setVariableName("target");
        
        GraphEntity alarmEntity = createAlarmEntity("alarm1", "critical", "High CPU");
        alarmEntity.setVariableName("target");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity)
                .addEntity(ltpEntity));
        
        kpiAdapter.registerResponse("getKPIByNeIds", MockExternalAdapter.MockResponse.create()
                .addEntity(kpiEntity));
        
        alarmAdapter.registerResponse("getAlarmsByNeIds", MockExternalAdapter.MockResponse.create()
                .addEntity(alarmEntity));
        
        String cypher = "MATCH (ne:NetworkElement {name: 'NE001'})-[r:NEHasLtps|NEHasAlarms|NEHasKPI]->(target) RETURN ne, target";
        
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertTrue(json.size() > 0, "结果数组不能为空");
        
        Set<String> targetTypes = new HashSet<>();
        for (int i = 0; i < json.size(); i++) {
            JsonNode row = json.get(i);
            assertTrue(row.isObject(), "每行必须是对象");
            assertTrue(row.has("ne"), "每行必须有ne字段");
            assertTrue(row.has("target"), "每行必须有target字段");
            
            JsonNode neNode = row.get("ne");
            assertTrue(neNode.has("label"), "ne必须有label字段");
            assertEquals("NetworkElement", neNode.get("label").asText(), "ne的label必须是NetworkElement");
            
            JsonNode targetNode = row.get("target");
            assertTrue(targetNode.has("label"), "target必须有label字段");
            String targetLabel = targetNode.get("label").asText();
            targetTypes.add(targetLabel);
        }
        
        assertTrue(targetTypes.contains("LTP") || targetTypes.contains("KPI") || targetTypes.contains("Alarm"), 
                "target必须包含LTP、KPI或Alarm类型");
    }
    
    @Test
    @DisplayName("示例2: UNION + Path 返回查询 - 返回TuGraph格式")
    void example2_UnionPathQuery() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("ne");
        
        GraphEntity ltpEntity = createLTPEntity("ltp1", "LTP1", "Port");
        ltpEntity.setVariableName("ltp");
        
        GraphEntity targetEntity = createKPIEntity("kpi1", "bandwidth", 1000.0);
        targetEntity.setVariableName("target");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity)
                .addEntity(ltpEntity)
                .addEntity(targetEntity));
        
        kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                .addEntity(targetEntity));
        
        kpiAdapter.registerResponse("getKPIByNeIds", MockExternalAdapter.MockResponse.create()
                .addEntity(targetEntity));
        
        String cypher = "MATCH p=(ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2]->(target) RETURN p " +
                       "UNION " +
                       "MATCH p=(ne:NetworkElement {name: 'NE001'})-[:NEHasLtps|NEHasKPI]->(target) RETURN p";
        
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertTrue(json.size() > 0, "结果数组不能为空");
        
        for (int i = 0; i < json.size(); i++) {
            JsonNode row = json.get(i);
            assertTrue(row.isObject(), "每行必须是对象");
            assertTrue(row.has("p"), "每行必须有p字段");
            
            JsonNode pathNode = row.get("p");
            assertTrue(pathNode.has("nodes"), "path必须有nodes字段");
            assertTrue(pathNode.has("relationships"), "path必须有relationships字段");
            
            JsonNode nodes = pathNode.get("nodes");
            assertTrue(nodes.isArray(), "nodes必须是数组");
            
            JsonNode rels = pathNode.get("relationships");
            assertTrue(rels.isArray(), "relationships必须是数组");
        }
    }
    
    @Test
    @DisplayName("示例3: 多跳路径查询与虚拟边")
    void example3_MultiHopPathQuery() throws Exception {
        GraphEntity personEntity = createPersonEntity("p1", "Alice");
        personEntity.setVariableName("p");
        
        GraphEntity person1Entity = createPersonEntity("p2", "Bob");
        person1Entity.setVariableName("p1");
        
        GraphEntity person2Entity = createPersonEntity("p3", "Charlie");
        person2Entity.setVariableName("p2");
        
        GraphEntity cardEntity = createCardEntity("card1", "Card", "card001");
        cardEntity.setVariableName("c");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(personEntity)
                .addEntity(person1Entity)
                .addEntity(person2Entity));
        
        cardAdapter.registerResponse("getCardsByPersonIds", MockExternalAdapter.MockResponse.create()
                .addEntity(cardEntity));
        
        String cypher = "MATCH path = (p:Person)-[r1:HAS_CHILD]->(p1)-[r2:HAS_CHILD]->(p2)-[r3:BORN_IN]->(c:Card) " +
                       "RETURN path";
        
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertTrue(json.size() > 0, "结果数组不能为空");
        
        for (int i = 0; i < json.size(); i++) {
            JsonNode row = json.get(i);
            assertTrue(row.isObject(), "每行必须是对象");
            assertTrue(row.has("path"), "每行必须有path字段");
            
            JsonNode pathNode = row.get("path");
            assertTrue(pathNode.has("nodes"), "path必须有nodes字段");
            assertTrue(pathNode.has("relationships"), "path必须有relationships字段");
            
            JsonNode nodes = pathNode.get("nodes");
            assertTrue(nodes.isArray(), "nodes必须是数组");
            assertTrue(nodes.size() > 0, "nodes数组不能为空");
        }
    }
    
    @Test
    @DisplayName("示例4: 纯外部数据源查询 - 返回TuGraph格式")
    void example4_PureExternalSourceQuery() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity));
        
        String cypher = "MATCH (n:NetworkElement) RETURN n";
        
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertTrue(json.size() > 0, "结果数组不能为空");
        
        JsonNode firstRow = json.get(0);
        assertTrue(firstRow.isObject(), "第一行必须是对象");
        assertTrue(firstRow.has("n"), "第一行必须有n字段");
        
        JsonNode nNode = firstRow.get("n");
        assertTrue(nNode.has("label"), "n必须有label字段");
        assertEquals("NetworkElement", nNode.get("label").asText(), "n的label必须是NetworkElement");
    }
    
    @Test
    @DisplayName("示例5: 外部到内部关联查询")
    void example5_ExternalToInternalQuery() throws Exception {
        GraphEntity card = createCardEntity("card1", "Card", "card001");
        card.setProperty("neId", "ne001");
        card.setVariableName("card");
        
        GraphEntity neEntity = createNEEntity("ne001", "NE001", "Router");
        neEntity.setVariableName("ne");
        
        cardAdapter.registerResponse("getCardByCardId", MockExternalAdapter.MockResponse.create()
                .addEntity(card));
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity));
        
        String cypher = "MATCH (card:Card {name: 'card001'})-[r1:CardLocateInNe]->(ne) WHERE card.resId='2131221' RETURN ne";
        
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertTrue(json.size() > 0, "结果数组不能为空");
        
        for (int i = 0; i < json.size(); i++) {
            JsonNode row = json.get(i);
            assertTrue(row.isObject(), "每行必须是对象");
        }
    }
    
    @Test
    @DisplayName("简单MATCH查询解析成功")
    void simpleMatchQuery() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity));
        
        String cypher = "MATCH (n:NetworkElement) RETURN n LIMIT 10";
        
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertTrue(json.size() > 0, "结果数组不能为空");
        
        for (int i = 0; i < json.size(); i++) {
            JsonNode row = json.get(i);
            assertTrue(row.isObject(), "每行必须是对象");
            assertTrue(row.has("n"), "每行必须有n字段");
            
            JsonNode nNode = row.get("n");
            assertTrue(nNode.has("label"), "n必须有label字段");
            assertEquals("NetworkElement", nNode.get("label").asText(), "n的label必须是NetworkElement");
        }
    }
    
    @Test
    @DisplayName("WHERE条件查询")
    void whereClauseQuery() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity));
        
        String cypher = "MATCH (n:NetworkElement) WHERE n.name = 'NE001' RETURN n";
        
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertTrue(json.size() > 0, "结果数组不能为空");
        
        JsonNode firstRow = json.get(0);
        assertTrue(firstRow.has("n"), "第一行必须有n字段");
        
        JsonNode nNode = firstRow.get("n");
        assertTrue(nNode.has("label"), "n必须有label字段");
        assertEquals("NetworkElement", nNode.get("label").asText());
    }
    
    @Test
    @DisplayName("ORDER BY + LIMIT查询")
    void orderByLimitQuery() throws Exception {
        GraphEntity neEntity1 = createNEEntity("ne1", "NE001", "Router");
        neEntity1.setVariableName("n");
        
        GraphEntity neEntity2 = createNEEntity("ne2", "NE002", "Switch");
        neEntity2.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity1)
                .addEntity(neEntity2));
        
        String cypher = "MATCH (n:NetworkElement) RETURN n ORDER BY n.name DESC LIMIT 5";
        
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertTrue(json.size() > 0, "结果数组不能为空");
        assertTrue(json.size() <= 5, "结果数量不能超过LIMIT值");
        
        for (int i = 0; i < json.size(); i++) {
            JsonNode row = json.get(i);
            assertTrue(row.has("n"), "每行必须有n字段");
        }
    }
    
    @Test
    @DisplayName("虚拟边检测正确")
    void virtualEdgeDetection() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("ne");
        
        GraphEntity kpiEntity = createKPIEntity("kpi1", "cpu_usage", 85.5);
        kpiEntity.setVariableName("kpi");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity));
        
        kpiAdapter.registerResponse("getKPIByNeIds", MockExternalAdapter.MockResponse.create()
                .addEntity(kpiEntity));
        
        String cypher = "MATCH (ne:NetworkElement)-[:NEHasKPI]->(kpi) RETURN ne, kpi";
        
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertTrue(json.size() > 0, "结果数组不能为空");
        
        for (int i = 0; i < json.size(); i++) {
            JsonNode row = json.get(i);
            assertTrue(row.isObject(), "每行必须是对象");
            assertTrue(row.size() > 0, "每行必须有字段");
        }
    }
    
    @Test
    @DisplayName("多跳路径查询")
    void multiHopPathQuery() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("ne");
        
        GraphEntity ltpEntity = createLTPEntity("ltp1", "LTP1", "Port");
        ltpEntity.setVariableName("ltp");
        
        GraphEntity kpiEntity = createKPIEntity("kpi1", "bandwidth", 1000.0);
        kpiEntity.setVariableName("target");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity)
                .addEntity(ltpEntity));
        
        kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                .addEntity(kpiEntity));
        
        String cypher = "MATCH (ne:NetworkElement)-[:NEHasLtps]->(ltp)-[:LTPHasKPI2]->(target) RETURN ne, ltp, target";
        
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertTrue(json.size() > 0, "结果数组不能为空");
        
        for (int i = 0; i < json.size(); i++) {
            JsonNode row = json.get(i);
            assertTrue(row.isObject(), "每行必须是对象");
        }
    }
    
    private GraphEntity createNEEntity(String id, String name, String type) {
        GraphEntity entity = GraphEntity.node(id, "NetworkElement");
        entity.setProperty("name", name);
        entity.setProperty("type", type);
        return entity;
    }
    
    private GraphEntity createLTPEntity(String id, String name, String type) {
        GraphEntity entity = GraphEntity.node(id, "LTP");
        entity.setProperty("name", name);
        entity.setProperty("type", type);
        return entity;
    }
    
    private GraphEntity createPersonEntity(String id, String name) {
        GraphEntity entity = GraphEntity.node(id, "Person");
        entity.setProperty("name", name);
        return entity;
    }
    
    private GraphEntity createCardEntity(String id, String label, String name) {
        GraphEntity entity = GraphEntity.node(id, label);
        entity.setProperty("name", name);
        return entity;
    }
    
    private GraphEntity createKPIEntity(String id, String name, double value) {
        GraphEntity entity = GraphEntity.node(id, "KPI");
        entity.setProperty("name", name);
        entity.setProperty("value", value);
        return entity;
    }
    
    private GraphEntity createAlarmEntity(String id, String severity, String message) {
        GraphEntity entity = GraphEntity.node(id, "Alarm");
        entity.setProperty("severity", severity);
        entity.setProperty("message", message);
        return entity;
    }
}
