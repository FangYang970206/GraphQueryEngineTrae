package com.fangyang.federatedquery.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fangyang.federatedquery.adapter.MockExternalAdapter;
import com.fangyang.federatedquery.model.GraphEntity;
import com.fangyang.federatedquery.executor.DependencyResolver;
import com.fangyang.federatedquery.executor.FederatedExecutor;
import com.fangyang.federatedquery.executor.ResultEnricher;
import com.fangyang.metadata.*;
import com.fangyang.federatedquery.sdk.GraphQuerySDK;
import com.fangyang.federatedquery.testutil.GraphQueryMetaFactory;
import com.fangyang.common.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.fangyang.federatedquery.e2e.E2EGraphEntityFactory.*;
import static org.junit.jupiter.api.Assertions.*;

class E2ETest {
    
    private MetadataQueryService metadataQueryService;
    private MockExternalAdapter kpiAdapter;
    private MockExternalAdapter alarmAdapter;
    private MockExternalAdapter cardAdapter;
    private MockExternalAdapter tugraphAdapter;
    private GraphQuerySDK sdk;
    
    @BeforeEach
    void setUp() {
        GraphQueryMetaFactory metaFactory = new GraphQueryMetaFactory()
                .registerDataSource("tugraph", DataSourceType.TUGRAPH_BOLT, "bolt://localhost:7687")
                .registerDataSource("kpi-service", DataSourceType.REST_API, "http://kpi-service:8080")
                .registerDataSource("alarm-service", DataSourceType.REST_API, "http://alarm-service:8080")
                .registerDataSource("card-service", DataSourceType.REST_API, "http://card-service:8080")
                .registerVirtualEdge("NEHasKPI", "kpi-service", "getKPIByNeIds", binding -> {
                    binding.setTargetLabel("KPI");
                    binding.getIdMapping().put("_id", "parentResId");
                    binding.setLastHopOnly(true);
                })
                .registerVirtualEdge("NEHasAlarms", "alarm-service", "getAlarmsByNeIds", binding -> {
                    binding.setTargetLabel("Alarm");
                    binding.getIdMapping().put("_id", "parentResId");
                    binding.setLastHopOnly(true);
                })
                .registerVirtualEdge("LTPHasKPI2", "kpi-service", "getKPI2ByLtpIds", binding -> {
                    binding.setTargetLabel("KPI");
                    binding.getIdMapping().put("_id", "parentResId");
                    binding.setLastHopOnly(true);
                })
                .registerVirtualEdge("BORN_IN", "card-service", "getCardsByPersonIds", binding -> binding.setLastHopOnly(true))
                .registerVirtualEdge("CardLocateInNe", "card-service", "getCardByCardId", binding -> binding.setFirstHopOnly(true))
                .registerLabel("NetworkElement", false, "tugraph")
                .registerLabel("LTP", false, "tugraph")
                .registerLabel("Person", false, "tugraph")
                .registerLabel("Card", true, "card-service");

        metadataQueryService = metaFactory.metadataQueryService();
        kpiAdapter = metaFactory.createAdapter("kpi-service");
        alarmAdapter = metaFactory.createAdapter("alarm-service");
        cardAdapter = metaFactory.createAdapter("card-service");
        tugraphAdapter = metaFactory.createAdapter("tugraph");
        sdk = metaFactory.createSdk();
    }
    
    @Test
    @DisplayName("示例1: 多关系类型混合查询 - 返回TuGraph格式")
    void example1_MultipleRelTypesMixedQuery() throws Exception {
        String cypher = "MATCH (ne:NetworkElement {name: 'NE001'})-[r:NEHasLtps|NEHasAlarms|NEHasKPI]->(target) RETURN ne, target";

        assertThrows(Exception.class, () -> sdk.execute(cypher), "包含单跳虚拟边的混合关系应直接拒绝");
    }
    
    @Test
    @DisplayName("示例2: UNION + Path 返回查询 - 返回TuGraph格式")
    void example2_UnionPathQuery() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("ne");
        
        GraphEntity ltpEntity = createLTPEntity("ltp1", "LTP1", "Port");
        ltpEntity.setVariableName("ltp");
        
        GraphEntity kpiEntity = createKPIEntity("kpi1", "cpu_usage", 85.5);
        kpiEntity.setVariableName("target");
        kpiEntity.setProperty("parentResId", "ltp1");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity)
                .addEntity(ltpEntity)
                .addEntity(kpiEntity));
        
        kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                .addEntity(kpiEntity));
        
        kpiAdapter.registerResponse("getKPIByNeIds", MockExternalAdapter.MockResponse.create()
                .addEntity(kpiEntity));
        
        String cypher = "MATCH p=(ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2]->(target) RETURN p";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertEquals(1, json.size(), "结果数组应该有1条记录");
        
        JsonNode firstRow = json.get(0);
        assertTrue(firstRow.has("p"), "每行必须有p字段");
        
        JsonNode paths = firstRow.get("p");
        assertTrue(paths.isArray(), "p必须是数组");
        assertEquals(1, paths.size(), "p数组应该有1条路径");
        
        JsonNode firstPath = paths.get(0);
        assertTrue(firstPath.isArray(), "每个path必须是数组");
        assertTrue(firstPath.size() >= 5, "路径至少应该有5个元素(3个节点+2个边)");
        
        JsonNode firstNode = firstPath.get(0);
        assertEquals("node", firstNode.get("type").asText(), "第一个元素必须是node");
        assertEquals("NetworkElement", firstNode.get("label").asText(), "第一个节点label必须是NetworkElement");
        JsonNode firstNodeProps = firstNode.get("props");
        assertTrue(firstNodeProps.has("name"), "第一个节点必须有name属性");
        assertEquals("NE001", firstNodeProps.get("name").asText(), "name必须是NE001");
        
        JsonNode firstEdge = firstPath.get(1);
        assertEquals("edge", firstEdge.get("type").asText(), "第二个元素必须是edge");
        assertEquals("NEHasLtps", firstEdge.get("label").asText(), "第一条边label必须是NEHasLtps");
        
        JsonNode secondNode = firstPath.get(2);
        assertEquals("node", secondNode.get("type").asText(), "第三个元素必须是node");
        assertEquals("LTP", secondNode.get("label").asText(), "第二个节点label必须是LTP");
        
        JsonNode secondEdge = firstPath.get(3);
        assertEquals("edge", secondEdge.get("type").asText(), "第四个元素必须是edge");
        assertEquals("LTPHasKPI2", secondEdge.get("label").asText(), "第二条边label必须是LTPHasKPI2");
        
        JsonNode thirdNode = firstPath.get(4);
        assertEquals("node", thirdNode.get("type").asText(), "第五个元素必须是node");
        assertEquals("KPI", thirdNode.get("label").asText(), "第三个节点label必须是KPI");
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
                .addEntity(person2Entity)
                .addEntity(cardEntity));
        
        cardAdapter.registerResponse("getCardsByPersonIds", MockExternalAdapter.MockResponse.create()
                .addEntity(cardEntity));
        cardAdapter.registerResponse("getByLabel", MockExternalAdapter.MockResponse.create()
                .addEntity(cardEntity));
        
        String cypher = "MATCH path = (p:Person)-[r1:HAS_CHILD]->(p1)-[r2:HAS_CHILD]->(p2)-[r3:BORN_IN]->(c:Card) " +
                       "RETURN path";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertEquals(1, json.size(), "结果数组应该有1条记录");
        
        JsonNode firstRow = json.get(0);
        assertTrue(firstRow.has("path"), "每行必须有path字段");
        
        JsonNode paths = firstRow.get("path");
        assertTrue(paths.isArray(), "path必须是数组");
        assertEquals(1, paths.size(), "path数组应该有1条路径");
        
        JsonNode firstPath = paths.get(0);
        assertTrue(firstPath.isArray(), "每个path必须是数组");
        assertEquals(7, firstPath.size(), "路径应该有7个元素(4个节点+3个边)");
        
        JsonNode firstNode = firstPath.get(0);
        assertEquals("node", firstNode.get("type").asText(), "第一个元素必须是node");
        assertEquals("Person", firstNode.get("label").asText(), "第一个节点label必须是Person");
        JsonNode firstNodeProps = firstNode.get("props");
        assertTrue(firstNodeProps.has("name"), "第一个节点必须有name属性");
        assertEquals("Alice", firstNodeProps.get("name").asText(), "name必须是Alice");
        
        JsonNode firstEdge = firstPath.get(1);
        assertEquals("edge", firstEdge.get("type").asText(), "第二个元素必须是edge");
        assertEquals("HAS_CHILD", firstEdge.get("label").asText(), "第一条边label必须是HAS_CHILD");
        
        JsonNode secondNode = firstPath.get(2);
        assertEquals("node", secondNode.get("type").asText(), "第三个元素必须是node");
        assertEquals("Person", secondNode.get("label").asText(), "第二个节点label必须是Person");
        
        JsonNode secondEdge = firstPath.get(3);
        assertEquals("edge", secondEdge.get("type").asText(), "第四个元素必须是edge");
        assertEquals("HAS_CHILD", secondEdge.get("label").asText(), "第二条边label必须是HAS_CHILD");
        
        JsonNode thirdNode = firstPath.get(4);
        assertEquals("node", thirdNode.get("type").asText(), "第五个元素必须是node");
        assertEquals("Person", thirdNode.get("label").asText(), "第三个节点label必须是Person");
        
        JsonNode thirdEdge = firstPath.get(5);
        assertEquals("edge", thirdEdge.get("type").asText(), "第六个元素必须是edge");
        assertEquals("BORN_IN", thirdEdge.get("label").asText(), "第三条边label必须是BORN_IN");
        
        JsonNode fourthNode = firstPath.get(6);
        assertEquals("node", fourthNode.get("type").asText(), "第七个元素必须是node");
        assertEquals("Card", fourthNode.get("label").asText(), "第四个节点label必须是Card");
    }

    @Test
    @DisplayName("示例3b: mixed path 优先按行归属构造路径")
    void example3b_MixedPathUsesRowProvenance() throws Exception {
        GraphEntity neRow1 = createNEEntity("ne1", "NE001", "Router");
        neRow1.setVariableName("ne");

        GraphEntity ltpRow1 = createLTPEntity("ltp1", "LTP001", "Port");
        ltpRow1.setVariableName("ltp");
        ltpRow1.setProperty("resId", "ltp1");

        GraphEntity neRow2 = createNEEntity("ne1", "NE001", "Router");
        neRow2.setVariableName("ne");

        GraphEntity ltpRow2 = createLTPEntity("ltp2", "LTP002", "Port");
        ltpRow2.setVariableName("ltp");
        ltpRow2.setProperty("resId", "ltp2");

        GraphEntity kpiRow1 = createKPIEntity("kpi1", "KPI001", 10.0);
        kpiRow1.setVariableName("target");
        kpiRow1.setProperty("parentResId", "ltp1");

        GraphEntity kpiRow2 = createKPIEntity("kpi2", "KPI002", 20.0);
        kpiRow2.setVariableName("target");
        kpiRow2.setProperty("parentResId", "ltp2");

        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addRowEntities(neRow1, ltpRow1)
                .addRowEntities(neRow2, ltpRow2));

        kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                .addRowEntities(kpiRow1)
                .addRowEntities(kpiRow2));

        String cypher = "MATCH p=(ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2]->(target) RETURN p";

        String result = sdk.execute(cypher);

        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertEquals(1, json.size(), "结果数组应该有1条记录");

        JsonNode firstRow = json.get(0);
        assertTrue(firstRow.has("p"), "每行必须有p字段");
        JsonNode paths = firstRow.get("p");
        assertTrue(paths.isArray(), "p必须是数组");
        assertEquals(2, paths.size(), "应该精确返回2条路径");

        Set<String> expectedPaths = Set.of(
                "NetworkElement:NE001->NEHasLtps->LTP:LTP001->LTPHasKPI2->KPI:KPI001",
                "NetworkElement:NE001->NEHasLtps->LTP:LTP002->LTPHasKPI2->KPI:KPI002"
        );

        Set<String> actualPaths = new LinkedHashSet<>();
        for (JsonNode path : paths) {
            actualPaths.add(buildPathSignature(path));
        }

        assertEquals(expectedPaths, actualPaths, "路径必须按行归属精确构造，不能产生交叉拼接");
    }
    
    @Test
    @DisplayName("示例4: 纯外部数据源查询 - 返回TuGraph格式")
    void example4_PureExternalSourceQuery() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity));
        
        String cypher = "MATCH (n:NetworkElement) RETURN n";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertEquals(1, json.size(), "结果数组应该有1条记录");
        
        JsonNode firstRow = json.get(0);
        assertTrue(firstRow.isObject(), "第一行必须是对象");
        assertTrue(firstRow.has("n"), "第一行必须有n字段");
        
        JsonNode nNode = firstRow.get("n");
        assertTrue(nNode.has("label"), "n必须有label字段");
        assertEquals("NetworkElement", nNode.get("label").asText(), "n的label必须是NetworkElement");
        assertTrue(nNode.has("name"), "n必须有name属性");
        assertEquals("NE001", nNode.get("name").asText(), "n的name必须是NE001");
    }
    
    @Test
    @DisplayName("示例5: 外部到内部关联查询")
    void example5_ExternalToInternalQuery() throws Exception {
        String cypher = "MATCH (card:Card {name: 'card001'})-[r1:CardLocateInNe]->(ne) WHERE card.resId='2131221' RETURN ne";

        assertThrows(Exception.class, () -> sdk.execute(cypher), "单跳虚拟边应直接拒绝");
    }
    
    @Test
    @DisplayName("简单MATCH查询解析成功")
    void simpleMatchQuery() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity));
        
        String cypher = "MATCH (n:NetworkElement) RETURN n LIMIT 10";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertEquals(1, json.size(), "结果数组应该有1条记录");
        
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
    @DisplayName("主入口 execute 查询返回正确")
    void simpleMatchQueryWithExecute() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("n");
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create().addEntity(neEntity));
        String result = sdk.execute("MATCH (n:NetworkElement) RETURN n");
        assertNotNull(result, "结果不能为空");
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertEquals(1, json.size(), "结果数组应该有1条记录");
        JsonNode row = json.get(0);
        assertTrue(row.has("n"), "结果必须包含n字段");
        JsonNode nNode = row.get("n");
        assertTrue(nNode.has("label"), "n必须有label字段");
        assertEquals("NetworkElement", nNode.get("label").asText(), "n.label必须是NetworkElement");
    }
    
    @Test
    @DisplayName("WHERE条件查询")
    void whereClauseQuery() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity));
        
        String cypher = "MATCH (n:NetworkElement) WHERE n.name = 'NE001' RETURN n";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertEquals(1, json.size(), "结果数组应该有1条记录");
        
        JsonNode firstRow = json.get(0);
        assertTrue(firstRow.has("n"), "第一行必须有n字段");
        
        JsonNode nNode = firstRow.get("n");
        assertTrue(nNode.has("label"), "n必须有label字段");
        assertEquals("NetworkElement", nNode.get("label").asText());
        assertTrue(nNode.has("name"), "n必须有name属性");
        assertEquals("NE001", nNode.get("name").asText(), "n的name必须是NE001");
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
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertEquals(2, json.size(), "结果数组应该有2条记录");
        assertTrue(json.size() <= 5, "结果数量不能超过LIMIT值");
        
        JsonNode firstRow = json.get(0);
        assertTrue(firstRow.has("n"), "第一行必须有n字段");
        assertEquals("Switch", firstRow.get("n").get("type").asText(), "第一条记录type应该是Switch(DESC排序)");
        
        JsonNode secondRow = json.get(1);
        assertTrue(secondRow.has("n"), "第二行必须有n字段");
    }
    
    @Test
    @DisplayName("虚拟边检测正确")
    void virtualEdgeDetection() throws Exception {
        String cypher = "MATCH (ne:NetworkElement)-[:NEHasKPI]->(kpi) RETURN ne, kpi";

        assertThrows(Exception.class, () -> sdk.execute(cypher), "单跳虚拟边应直接拒绝");
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
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertEquals(1, json.size(), "结果数组应该有1条记录");
        
        for (int i = 0; i < json.size(); i++) {
            JsonNode row = json.get(i);
            assertTrue(row.isObject(), "每行必须是对象");
        }
    }
    
    private String buildPathSignature(JsonNode path) {
        assertTrue(path.isArray(), "路径必须是数组");

        List<String> segments = new ArrayList<>();
        for (JsonNode element : path) {
            assertTrue(element.has("type"), "路径元素必须包含 type 字段");
            assertTrue(element.has("label"), "路径元素必须包含 label 字段");

            String type = element.get("type").asText();
            String label = element.get("label").asText();

            if ("node".equals(type)) {
                JsonNode props = element.get("props");
                assertTrue(props.has("name"), "节点必须包含 name 字段");
                segments.add(label + ":" + props.get("name").asText());
            } else if ("edge".equals(type)) {
                segments.add(label);
            } else {
                fail("未知路径元素类型: " + type);
            }
        }

        return String.join("->", segments);
    }
    
    @Test
    @DisplayName("参数化查询测试")
    void parameterizedQueryTest() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity));
        
        String cypher = "MATCH (n:NetworkElement {name: $name}) RETURN n";
        Map<String, Object> params = new HashMap<>();
        params.put("name", "NE001");
        
        String result = sdk.execute(cypher, params);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertEquals(1, json.size(), "结果数组应该有1条记录");
        
        JsonNode firstRow = json.get(0);
        assertTrue(firstRow.has("n"), "第一行必须有n字段");
        
        JsonNode nNode = firstRow.get("n");
        assertEquals("NetworkElement", nNode.get("label").asText(), "n的label必须是NetworkElement");
        assertEquals("NE001", nNode.get("name").asText(), "n的name必须是NE001");
    }
    
    @Test
    @DisplayName("全局排序测试 - ORDER BY DESC")
    void globalSortDescTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "AAA", "Router");
        ne1.setVariableName("n");
        GraphEntity ne2 = createNEEntity("ne2", "BBB", "Switch");
        ne2.setVariableName("n");
        GraphEntity ne3 = createNEEntity("ne3", "CCC", "Router");
        ne3.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1)
                .addEntity(ne2)
                .addEntity(ne3));
        
        String cypher = "MATCH (n:NetworkElement) RETURN n ORDER BY n.name DESC";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertTrue(json.size() >= 2, "结果至少有2条记录");
        
        JsonNode firstName = json.get(0).get("n").get("name");
        JsonNode lastName = json.get(json.size() - 1).get("n").get("name");
        
        assertTrue(firstName.asText().compareTo(lastName.asText()) >= 0, 
                "DESC排序: 第一条记录name应该大于等于最后一条");
    }
    
    @Test
    @DisplayName("全局分页测试 - LIMIT和SKIP")
    void globalPaginationTest() throws Exception {
        String cypher = "MATCH (n:NetworkElement) RETURN n SKIP 1 LIMIT 3";

        assertThrows(Exception.class, () -> sdk.execute(cypher), "SKIP 不在当前范围内");
    }
    
    @Test
    @DisplayName("WHERE条件下推测试 - 物理节点条件")
    void whereConditionPushdownPhysicalTest() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("ne");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity));
        
        String cypher = "MATCH (ne:NetworkElement) WHERE ne.name = 'NE001' RETURN ne";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertEquals(1, json.size(), "结果数组应该有1条记录");
    }
    
    @Test
    @DisplayName("WHERE条件下推测试 - 虚拟节点条件")
    void whereConditionPushdownVirtualTest() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("ne");

        GraphEntity ltpEntity = createLTPEntity("ltp1", "LTP001", "Port");
        ltpEntity.setVariableName("ltp");
        ltpEntity.setProperty("resId", "ltp1");

        GraphEntity kpiEntity = createKPIEntity("kpi1", "cpu_usage", 95.0);
        kpiEntity.setVariableName("kpi");
        kpiEntity.setProperty("parentResId", "ltp1");

        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addRowEntities(neEntity, ltpEntity));

        kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                .addEntity(kpiEntity));

        String cypher = "MATCH (ne:NetworkElement)-[:NEHasLtps]->(ltp)-[:LTPHasKPI2]->(kpi) WHERE kpi.value > 90 RETURN ne, ltp, kpi";

        String result = sdk.execute(cypher);

        assertNotNull(result, "结果不能为空");

        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertEquals(1, json.size(), "结果数组应该有1条记录");
    }
    
    @Test
    @DisplayName("纯虚拟标签查询测试 - 示例4真实场景")
    void pureVirtualLabelQueryTest() throws Exception {
        String cypher = "MATCH (card:Card {name: 'card001'}) RETURN card";

        assertThrows(Exception.class, () -> sdk.execute(cypher), "纯虚拟点模式应直接拒绝");
    }
    
    @Test
    @DisplayName("外部服务超时配置测试")
    void externalServiceTimeoutTest() throws Exception {
        DependencyResolver dependencyResolver = new DependencyResolver();
        ResultEnricher resultEnricher = new ResultEnricher();
        FederatedExecutor executorWithTimeout = new FederatedExecutor(metadataQueryService, dependencyResolver, resultEnricher);
        executorWithTimeout.setTimeoutMs(5000);
        executorWithTimeout.registerAdapter("tugraph", tugraphAdapter);
        
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity));
        
        assertEquals(5000, executorWithTimeout.getTimeoutMs(), "超时时间应该设置为5000ms");
        
        executorWithTimeout.shutdown();
    }
    
    @Test
    @DisplayName("USING SNAPSHOT Unix时间戳测试")
    void usingSnapshotTimestampTest() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("n");

        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity));

        String cypher = "USING SNAPSHOT('latest', 1704067200) ON [NetworkElement] MATCH (n:NetworkElement) RETURN n";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        assertFalse(result.isEmpty(), "结果不能为空字符串");
        
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组, 实际结果: " + result);
        assertEquals(1, json.size(), "结果数量应该是1条");
        
        JsonNode firstRow = json.get(0);
        assertTrue(firstRow.has("n"), "第一行必须有n字段");

        JsonNode neNode = firstRow.get("n");
        assertEquals("NetworkElement", neNode.get("label").asText(), "n的label必须是NetworkElement");
        assertEquals("NE001", neNode.get("name").asText(), "n的name必须是NE001");
    }
    
    @Test
    @DisplayName("USING SNAPSHOT 带WHERE条件测试")
    void usingSnapshotWithWhereTest() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("n");

        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity));

        String cypher = "USING SNAPSHOT('snapshot1', 1704067200) ON [NetworkElement] MATCH (n:NetworkElement {name: 'NE001'}) RETURN n";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组, 实际结果: " + result);
        assertEquals(1, json.size(), "结果数量应该是1条");
    }
    
    @Test
    @DisplayName("WITH 子句基本测试 - 过滤中间结果")
    void withClauseBasicTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
        ne1.setVariableName("n");
        GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
        ne2.setVariableName("n");
        GraphEntity ne3 = createNEEntity("ne3", "NE003", "Router");
        ne3.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1)
                .addEntity(ne2)
                .addEntity(ne3));
        
        String cypher = "MATCH (n:NetworkElement) WITH n ORDER BY n.name LIMIT 2 RETURN n";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertTrue(json.size() <= 2, "WITH LIMIT 2 应该限制结果数量");
    }
    
    @Test
    @DisplayName("WITH 子句别名测试")
    void withClauseAliasTest() throws Exception {
        GraphEntity neEntity = createNEEntity("ne1", "NE001", "Router");
        neEntity.setVariableName("ne");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity));
        
        String cypher = "MATCH (ne:NetworkElement) WITH ne RETURN ne";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertEquals(1, json.size(), "结果数组应该有1条记录");
        
        JsonNode firstRow = json.get(0);
        assertTrue(firstRow.has("ne"), "第一行必须有ne字段");
        
        JsonNode neNode = firstRow.get("ne");
        assertEquals("NetworkElement", neNode.get("label").asText(), "ne的label必须是NetworkElement");
    }
    
    @Test
    @DisplayName("WITH 子句 WHERE 过滤测试")
    void withClauseWhereTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
        ne1.setVariableName("n");
        GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
        ne2.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1)
                .addEntity(ne2));
        
        String cypher = "MATCH (n:NetworkElement) WITH n WHERE n.type = 'Router' RETURN n";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
    }
    
    @Test
    @DisplayName("WITH 子句排序分页测试")
    void withClauseSortPaginateTest() throws Exception {
        String cypher = "MATCH (n:NetworkElement) WITH n ORDER BY n.name DESC SKIP 1 LIMIT 2 RETURN n";

        assertThrows(Exception.class, () -> sdk.execute(cypher), "WITH 中的 SKIP 也应直接拒绝");
    }
    
    @Test
    @DisplayName("UNWIND 子句解析测试")
    void unwindClauseTest() throws Exception {
        String cypher = "UNWIND [1, 2, 3] AS x RETURN x";

        assertThrows(Exception.class, () -> sdk.execute(cypher), "UNWIND 不在当前范围内");
    }
    
    @Test
    @DisplayName("OPTIONAL MATCH 解析测试")
    void optionalMatchTest() throws Exception {
        String cypher = "OPTIONAL MATCH (n:NetworkElement) RETURN n";

        assertThrows(Exception.class, () -> sdk.execute(cypher), "OPTIONAL MATCH 不在当前范围内");
    }
    
    @Test
    @DisplayName("IN 操作符测试")
    void inOperatorTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
        ne1.setVariableName("n");
        GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
        ne2.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1)
                .addEntity(ne2));
        
        String cypher = "MATCH (n:NetworkElement) WHERE n.name IN ['NE001', 'NE003'] RETURN n";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
    }
    
    @Test
    @DisplayName("IS NULL 判断测试")
    void isNullTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "MATCH (n:NetworkElement) WHERE n.name IS NOT NULL RETURN n";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertEquals(1, json.size(), "结果数组应该有1条记录");
        
        JsonNode firstRow = json.get(0);
        assertTrue(firstRow.has("n"), "第一行必须有n字段");
        
        JsonNode nNode = firstRow.get("n");
        assertEquals("NetworkElement", nNode.get("label").asText(), "n的label必须是NetworkElement");
        assertEquals("NE001", nNode.get("name").asText(), "n的name必须是NE001");
    }
    
    @Test
    @DisplayName("STARTS WITH 字符串匹配测试")
    void startsWithTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
        ne1.setVariableName("n");
        GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
        ne2.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1)
                .addEntity(ne2));
        
        String cypher = "MATCH (n:NetworkElement) WHERE n.name STARTS WITH 'NE' RETURN n";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertTrue(json.size() >= 1, "结果数组应该至少有1条记录");
        
        for (int i = 0; i < json.size(); i++) {
            JsonNode row = json.get(i);
            assertTrue(row.has("n"), "每行必须有n字段");
            JsonNode nNode = row.get("n");
            assertTrue(nNode.get("name").asText().startsWith("NE"), "name必须以NE开头");
        }
    }
    
    @Test
    @DisplayName("ENDS WITH 字符串匹配测试")
    void endsWithTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
        ne1.setVariableName("n");
        GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
        ne2.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1)
                .addEntity(ne2));
        
        String cypher = "MATCH (n:NetworkElement) WHERE n.name ENDS WITH '01' RETURN n";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        assertTrue(json.size() >= 1, "结果数组应该至少有1条记录");
    }
    
    @Test
    @DisplayName("CONTAINS 字符串匹配测试")
    void containsTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
        ne1.setVariableName("n");
        GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
        ne2.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1)
                .addEntity(ne2));
        
        String cypher = "MATCH (n:NetworkElement) WHERE n.name CONTAINS 'E00' RETURN n";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
        
        for (int i = 0; i < json.size(); i++) {
            JsonNode row = json.get(i);
            assertTrue(row.has("n"), "每行必须有n字段");
            JsonNode nNode = row.get("n");
            assertTrue(nNode.get("name").asText().contains("E00"), "name必须包含E00");
        }
    }
    
    @Test
    @DisplayName("CASE 表达式测试")
    void caseExpressionTest() throws Exception {
        String cypher = "MATCH (n:NetworkElement) RETURN n.name, CASE WHEN n.type = 'Router' THEN 'R' ELSE 'S' END AS typeCode";

        assertThrows(Exception.class, () -> sdk.execute(cypher), "CASE 表达式不在当前 scope 内");
    }
    
    @Test
    @DisplayName("count 聚合函数测试")
    void countFunctionTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
        ne1.setVariableName("n");
        GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
        ne2.setVariableName("n");
        GraphEntity ne3 = createNEEntity("ne3", "NE003", "Router");
        ne3.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1)
                .addEntity(ne2)
                .addEntity(ne3));
        
        String cypher = "MATCH (n:NetworkElement) RETURN count(n) AS total";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        JsonNode json = JsonUtil.readTree(result);
        assertTrue(json.isArray(), "结果必须是数组");
    }
}
