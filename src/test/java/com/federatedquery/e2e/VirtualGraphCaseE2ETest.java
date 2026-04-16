package com.federatedquery.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.federatedquery.adapter.*;
import com.federatedquery.aggregator.*;
import com.federatedquery.connector.*;
import com.federatedquery.executor.DependencyResolver;
import com.federatedquery.executor.FederatedExecutor;
import com.federatedquery.executor.ResultEnricher;
import com.federatedquery.metadata.*;
import com.federatedquery.mockserver.MockHttpServer;
import com.federatedquery.parser.CypherParserFacade;
import com.federatedquery.parser.CypherASTVisitor;
import com.federatedquery.rewriter.MixedPatternRewriter;
import com.federatedquery.rewriter.PhysicalQueryBuilder;
import com.federatedquery.rewriter.QueryRewriter;
import com.federatedquery.rewriter.VirtualEdgeDetector;
import com.federatedquery.reliability.WhereConditionPushdown;
import com.federatedquery.sdk.GraphQuerySDK;
import com.federatedquery.testutil.GraphQueryMetaFactory;
import com.federatedquery.util.JsonUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("虚拟图测试用例端到端测试")
class VirtualGraphCaseE2ETest {
    
    private static final Logger logger = LoggerFactory.getLogger(VirtualGraphCaseE2ETest.class);
    
    private static TuGraphConnector connector;
    private static MockHttpServer mockHttpServer;
    private static boolean tuGraphAvailable = false;
    
    private GraphQuerySDK sdk;
    private MetadataRegistry registry;
    
    @BeforeAll
    static void setUpClass() {
        TuGraphConfig config = new TuGraphConfig();
        config.setUri("bolt://127.0.0.1:7687");
        config.setUsername("admin");
        config.setPassword("73@TuGraph");
        config.setGraphName("default");
        
        try {
            connector = new TuGraphConnectorImpl(config);
            tuGraphAvailable = connector.isConnected();
            
            if (tuGraphAvailable) {
                logger.info("TuGraph连接成功，开始构造测试数据...");
                setupSchemaAndData();
                
                mockHttpServer = new MockHttpServer(18080);
                mockHttpServer.start();
                logger.info("Mock HTTP Server启动成功: {}", mockHttpServer.getAlarmEndpoint());
            }
        } catch (Exception e) {
            logger.warn("TuGraph不可用，跳过集成测试: {}", e.getMessage());
        }
    }
    
    private static void setupSchemaAndData() {
        try {
            connector.executeQuery("MATCH (n) DETACH DELETE n");
            logger.info("清空数据库完成");
            
            try {
                connector.executeQuery("CALL db.dropVertexLabel('NetworkElement')");
                logger.info("删除NetworkElement label完成");
            } catch (Exception e) {
                logger.debug("删除NetworkElement label时出错（可能不存在）: {}", e.getMessage());
            }
            
            try {
                connector.executeQuery("CALL db.dropVertexLabel('LTP')");
                logger.info("删除LTP label完成");
            } catch (Exception e) {
                logger.debug("删除LTP label时出错（可能不存在）: {}", e.getMessage());
            }
            
            try {
                connector.executeQuery(
                    "CALL db.createVertexLabel('NetworkElement', 'resId', " +
                    "'resId', 'STRING', false, 'name', 'STRING', false, 'DN', 'STRING', false)"
                );
                logger.info("创建NetworkElement vertex label完成");
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                    logger.info("NetworkElement vertex label已存在，跳过创建");
                } else {
                    throw e;
                }
            }
            
            try {
                connector.executeQuery(
                    "CALL db.createVertexLabel('LTP', 'resId', " +
                    "'resId', 'STRING', false, 'name', 'STRING', false, 'parentResId', 'STRING', false)"
                );
                logger.info("创建LTP vertex label完成");
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                    logger.info("LTP vertex label已存在，跳过创建");
                } else {
                    throw e;
                }
            }
            
            try {
                connector.executeQuery("CALL db.dropEdgeLabel('NEHasLtps')");
                logger.info("删除NEHasLtps edge label完成");
            } catch (Exception e) {
                logger.debug("删除edge label时出错（可能不存在）: {}", e.getMessage());
            }
            
            try {
                connector.executeQuery(
                    "CALL db.createEdgeLabel('NEHasLtps', '{}', 'id', 'INT64', true)"
                );
                logger.info("创建NEHasLtps edge label完成");
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                    logger.info("NEHasLtps edge label已存在，跳过创建");
                } else {
                    logger.warn("创建edge label失败，尝试直接创建边: {}", e.getMessage());
                }
            }
            
            connector.executeQuery(
                "CREATE (ne1:NetworkElement {resId: 'eccc2c94-6a31-45ea-a16e-0c709939cbe5', name: 'NE001', DN: '388581df-50a3-4d9f-96d4-15faf36f9caf'}) " +
                "CREATE (ne2:NetworkElement {resId: '552dddad-84c4-4d5e-9014-481443ec23b5', name: 'NE002', DN: 'a158b427-4a65-4adf-9096-3c8225519cca'})"
            );
            logger.info("创建NetworkElement节点完成");
            
            connector.executeQuery(
                "CREATE (ltp1:LTP {resId: 'db82ad76-8bdc-4f4b-96a0-a5e91c6861fb', name: 'LTP001', parentResId: 'eccc2c94-6a31-45ea-a16e-0c709939cbe5'}) " +
                "CREATE (ltp2:LTP {resId: 'c889973b-8bd7-4eb9-899b-b1cc9bf18a2e', name: 'LTP002', parentResId: 'eccc2c94-6a31-45ea-a16e-0c709939cbe5'}) " +
                "CREATE (ltp3:LTP {resId: '537ae2db-80d6-4b13-9cee-140a9af811ca', name: 'LTP003', parentResId: '552dddad-84c4-4d5e-9014-481443ec23b5'}) " +
                "CREATE (ltp4:LTP {resId: '7c013137-db19-4f21-bbbd-6908cd2033d8', name: 'LTP004', parentResId: '552dddad-84c4-4d5e-9014-481443ec23b5'})"
            );
            logger.info("创建LTP节点完成");
            
            connector.executeQuery(
                "MATCH (ne1:NetworkElement {name: 'NE001'}), (ltp1:LTP {name: 'LTP001'}) " +
                "CREATE (ne1)-[:NEHasLtps]->(ltp1)"
            );
            connector.executeQuery(
                "MATCH (ne1:NetworkElement {name: 'NE001'}), (ltp2:LTP {name: 'LTP002'}) " +
                "CREATE (ne1)-[:NEHasLtps]->(ltp2)"
            );
            connector.executeQuery(
                "MATCH (ne2:NetworkElement {name: 'NE002'}), (ltp3:LTP {name: 'LTP003'}) " +
                "CREATE (ne2)-[:NEHasLtps]->(ltp3)"
            );
            connector.executeQuery(
                "MATCH (ne2:NetworkElement {name: 'NE002'}), (ltp4:LTP {name: 'LTP004'}) " +
                "CREATE (ne2)-[:NEHasLtps]->(ltp4)"
            );
            logger.info("创建NEHasLtps关系完成");
            
        } catch (Exception e) {
            logger.error("构造测试数据失败", e);
            tuGraphAvailable = false;
        }
    }
    
    @AfterAll
    static void tearDownClass() {
        if (mockHttpServer != null) {
            mockHttpServer.stop();
            logger.info("Mock HTTP Server已停止");
        }
        if (connector != null) {
            connector.close();
            logger.info("TuGraph连接已关闭");
        }
    }
    
    @BeforeEach
    void setUp() {
        GraphQueryMetaFactory metaFactory = GraphQueryMetaFactory.createWithDruidZenith();
        registry = metaFactory.registry();
        
        TuGraphAdapterImpl tugraphAdapter = new TuGraphAdapterImpl(connector, "tugraph");
        
        MockExternalAdapter druidAdapter = createDruidAdapter();
        MockExternalAdapter zenithAdapter = createZenithAdapter();
        
        CypherParserFacade parser = new CypherParserFacade(new CypherASTVisitor());
        VirtualEdgeDetector detector = new VirtualEdgeDetector(registry);
        WhereConditionPushdown whereConditionPushdown = new WhereConditionPushdown(registry);
        PhysicalQueryBuilder physicalQueryBuilder = new PhysicalQueryBuilder();
        MixedPatternRewriter mixedPatternRewriter = new MixedPatternRewriter(registry, physicalQueryBuilder);
        QueryRewriter rewriter = new QueryRewriter(registry, detector, whereConditionPushdown, physicalQueryBuilder, mixedPatternRewriter);
        DependencyResolver dependencyResolver = new DependencyResolver();
        ResultEnricher resultEnricher = new ResultEnricher();
        FederatedExecutor executor = new FederatedExecutor(registry, dependencyResolver, resultEnricher);
        executor.registerAdapter("tugraph", tugraphAdapter);
        executor.registerAdapter("druid-service", druidAdapter);
        executor.registerAdapter("zenith-service", zenithAdapter);
        UnionDeduplicator deduplicator = new UnionDeduplicator();
        
        sdk = new GraphQuerySDK(parser, rewriter, executor, deduplicator, connector);
    }
    
    @Test
    @DisplayName("Case1: MATCH (ne:NetworkElement {name: 'NE001'})-[r:NEHasLtps|NEHasAlarms|NEHasKPI]->(target) return ne,target")
    @DisabledIf("isTuGraphNotAvailable")
    void testCase1() throws Exception {
        String cypher = "MATCH (ne:NetworkElement {name: 'NE001'})-[r:NEHasLtps|NEHasAlarms|NEHasKPI]->(target) return ne,target";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        logger.info("Case1 SDK返回结果: {}", result);
        
        JsonNode jsonNode = extractResultsArray(result);
        assertEquals(5, jsonNode.size(), "Case1 应该精确返回 5 条 ne-target 记录");

        Set<String> expectedRows = Set.of(
            "ne={DN=388581df-50a3-4d9f-96d4-15faf36f9caf,id=eccc2c94-6a31-45ea-a16e-0c709939cbe5,label=NetworkElement,name=NE001,resId=eccc2c94-6a31-45ea-a16e-0c709939cbe5} | target={id=db82ad76-8bdc-4f4b-96a0-a5e91c6861fb,label=LTP,name=LTP001,parentResId=eccc2c94-6a31-45ea-a16e-0c709939cbe5,resId=db82ad76-8bdc-4f4b-96a0-a5e91c6861fb}",
            "ne={DN=388581df-50a3-4d9f-96d4-15faf36f9caf,id=eccc2c94-6a31-45ea-a16e-0c709939cbe5,label=NetworkElement,name=NE001,resId=eccc2c94-6a31-45ea-a16e-0c709939cbe5} | target={id=c889973b-8bd7-4eb9-899b-b1cc9bf18a2e,label=LTP,name=LTP002,parentResId=eccc2c94-6a31-45ea-a16e-0c709939cbe5,resId=c889973b-8bd7-4eb9-899b-b1cc9bf18a2e}",
            "ne={DN=388581df-50a3-4d9f-96d4-15faf36f9caf,id=eccc2c94-6a31-45ea-a16e-0c709939cbe5,label=NetworkElement,name=NE001,resId=eccc2c94-6a31-45ea-a16e-0c709939cbe5} | target={CLEARTIME=1775825951299,CSN=0079bb0f-0e0a-44de-b595-cab5c22324ef,MEDN=388581df-50a3-4d9f-96d4-15faf36f9caf,MENAME=ALARM001,OCCURTIME=1775825151299,id=0079bb0f-0e0a-44de-b595-cab5c22324ef,label=ALARM}",
            "ne={DN=388581df-50a3-4d9f-96d4-15faf36f9caf,id=eccc2c94-6a31-45ea-a16e-0c709939cbe5,label=NetworkElement,name=NE001,resId=eccc2c94-6a31-45ea-a16e-0c709939cbe5} | target={CLEARTIME=1775825551299,CSN=afa87ad3-0834-467c-ba2e-1315fb9ba0cb,MEDN=388581df-50a3-4d9f-96d4-15faf36f9caf,MENAME=ALARM002,OCCURTIME=1775825251299,id=afa87ad3-0834-467c-ba2e-1315fb9ba0cb,label=ALARM}",
            "ne={DN=388581df-50a3-4d9f-96d4-15faf36f9caf,id=eccc2c94-6a31-45ea-a16e-0c709939cbe5,label=NetworkElement,name=NE001,resId=eccc2c94-6a31-45ea-a16e-0c709939cbe5} | target={id=0079bb0f-0e0a-44de-b595-cab5c22324ef,label=KPI,name=KPI001,parentResId=eccc2c94-6a31-45ea-a16e-0c709939cbe5,resId=0079bb0f-0e0a-44de-b595-cab5c22324ef,time=1775825156755}"
        );

        Set<String> actualRows = new LinkedHashSet<>();
        for (JsonNode row : jsonNode) {
            actualRows.add(buildRowSignature(row, "ne", "target"));
        }

        assertEquals(expectedRows, actualRows, "Case1 返回的 ne-target 集合必须与整张图构造完全一致");
    }
    
    @Test
    @DisplayName("Case2: UNION查询路径")
    @DisabledIf("isTuGraphNotAvailable")
    void testCase2() throws Exception {
        String cypher = "match p=(ne:NetworkElement {name: 'NE002'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2]->(target) return p union MATCH p = (ne:NetworkElement {name: 'NE002'})-[:NEHasLtps|NEHasKPI]->(target) return p";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        logger.info("Case2 SDK返回结果: {}", result);
        
        JsonNode jsonNode = JsonUtil.readTree(result);
        
        if (jsonNode.has("results")) {
            jsonNode = jsonNode.get("results");
        }
        
        assertTrue(jsonNode.isArray(), "结果应该是数组");
        assertEquals(1, jsonNode.size(), "RETURN p 的当前结果格式应该只返回1行");

        JsonNode firstRow = jsonNode.get(0);
        assertTrue(firstRow.has("p"), "结果行必须包含 p 字段");

        JsonNode paths = firstRow.get("p");
        assertTrue(paths.isArray(), "p 字段必须是路径数组");
        assertEquals(5, paths.size(), "Case2 应该精确返回 5 条路径");

        Set<String> expectedPaths = Set.of(
            "NetworkElement:NE002->NEHasLtps->LTP:LTP003->LTPHasKPI2->KPI2:KPI2003",
            "NetworkElement:NE002->NEHasLtps->LTP:LTP004->LTPHasKPI2->KPI2:KPI2004",
            "NetworkElement:NE002->NEHasLtps->LTP:LTP003",
            "NetworkElement:NE002->NEHasLtps->LTP:LTP004",
            "NetworkElement:NE002->NEHasKPI->KPI:KPI001"
        );

        Set<String> actualPaths = new LinkedHashSet<>();
        for (JsonNode path : paths) {
            actualPaths.add(buildPathSignature(path));
        }

        assertEquals(expectedPaths, actualPaths, "Case2 返回的路径集合必须与预期完全一致");

        logger.info("Case2测试通过: 返回{}条唯一路径", paths.size());
    }
    
    @Test
    @DisplayName("Case3: USING SNAPSHOT和PROJECT BY查询")
    @DisabledIf("isTuGraphNotAvailable")
    void testCase3() throws Exception {
        String cypher = "USING SNAPSHOT('latest', 1762940006544) ON [ALARM,KPI] MATCH (ne:NetworkElement {name: 'NE001'})-[:NEHasLtps|NEHasAlarms|NEHasKPI]->(target) RETURN ne,target PROJECT BY {ALARM:[MENAME],KPI:[name]}";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        logger.info("Case3 SDK返回结果: {}", result);
        
        JsonNode jsonNode = extractResultsArray(result);
        assertEquals(5, jsonNode.size(), "Case3 应该精确返回 5 条投影后的 ne-target 记录");

        Set<String> expectedRows = Set.of(
            "ne={DN=388581df-50a3-4d9f-96d4-15faf36f9caf,id=eccc2c94-6a31-45ea-a16e-0c709939cbe5,label=NetworkElement,name=NE001,resId=eccc2c94-6a31-45ea-a16e-0c709939cbe5} | target={id=db82ad76-8bdc-4f4b-96a0-a5e91c6861fb,label=LTP,name=LTP001,parentResId=eccc2c94-6a31-45ea-a16e-0c709939cbe5,resId=db82ad76-8bdc-4f4b-96a0-a5e91c6861fb}",
            "ne={DN=388581df-50a3-4d9f-96d4-15faf36f9caf,id=eccc2c94-6a31-45ea-a16e-0c709939cbe5,label=NetworkElement,name=NE001,resId=eccc2c94-6a31-45ea-a16e-0c709939cbe5} | target={id=c889973b-8bd7-4eb9-899b-b1cc9bf18a2e,label=LTP,name=LTP002,parentResId=eccc2c94-6a31-45ea-a16e-0c709939cbe5,resId=c889973b-8bd7-4eb9-899b-b1cc9bf18a2e}",
            "ne={DN=388581df-50a3-4d9f-96d4-15faf36f9caf,id=eccc2c94-6a31-45ea-a16e-0c709939cbe5,label=NetworkElement,name=NE001,resId=eccc2c94-6a31-45ea-a16e-0c709939cbe5} | target={MENAME=ALARM001,label=ALARM}",
            "ne={DN=388581df-50a3-4d9f-96d4-15faf36f9caf,id=eccc2c94-6a31-45ea-a16e-0c709939cbe5,label=NetworkElement,name=NE001,resId=eccc2c94-6a31-45ea-a16e-0c709939cbe5} | target={MENAME=ALARM002,label=ALARM}",
            "ne={DN=388581df-50a3-4d9f-96d4-15faf36f9caf,id=eccc2c94-6a31-45ea-a16e-0c709939cbe5,label=NetworkElement,name=NE001,resId=eccc2c94-6a31-45ea-a16e-0c709939cbe5} | target={label=KPI,name=KPI001}"
        );

        Set<String> actualRows = new LinkedHashSet<>();
        for (JsonNode row : jsonNode) {
            actualRows.add(buildRowSignature(row, "ne", "target"));
        }

        assertEquals(expectedRows, actualRows, "Case3 返回结果必须同时满足 Snapshot 查询与 Project By 投影后的精确输出");
    }
    
    @Test
    @DisplayName("Case4: match (alarm:ALARM {name: 'ALARM001'}) where alarm.CSN='0079bb0f-0e0a-44de-b595-cab5c22324ef'")
    @DisabledIf("isTuGraphNotAvailable")
    void testCase4() throws Exception {
        String cypher = "match (alarm:ALARM {name: 'ALARM001'}) where alarm.CSN='0079bb0f-0e0a-44de-b595-cab5c22324ef' return alarm";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        logger.info("Case4 SDK返回结果: {}", result);
        
        JsonNode jsonNode = extractResultsArray(result);
        assertEquals(1, jsonNode.size(), "应该有1条ALARM记录");
        
        JsonNode firstRow = jsonNode.get(0);
        assertTrue(firstRow.has("alarm"), "应该包含alarm字段");
        
        JsonNode alarm = firstRow.get("alarm");
        assertEquals(
            "{CLEARTIME=1775825951299,CSN=0079bb0f-0e0a-44de-b595-cab5c22324ef,MEDN=388581df-50a3-4d9f-96d4-15faf36f9caf,MENAME=ALARM001,OCCURTIME=1775825151299,id=0079bb0f-0e0a-44de-b595-cab5c22324ef,label=ALARM}",
            buildEntitySignature(alarm),
            "Case4 返回的 ALARM 实体字段和值必须与构造数据完全一致"
        );
        
        logger.info("Case4测试通过: 成功查询到ALARM001");
    }
    
    @Test
    @DisplayName("Case5: 通过ALARM反向查询NetworkElement")
    @DisabledIf("isTuGraphNotAvailable")
    void testCase5() throws Exception {
        String cypher = "match (ne:NetworkElement)-[:NEHasAlarms]->(alarm:ALARM {name: 'ALARM002'}) where alarm.CSN='afa87ad3-0834-467c-ba2e-1315fb9ba0cb' return ne";
        
        String result = sdk.execute(cypher);
        
        assertNotNull(result, "结果不能为空");
        logger.info("Case5 SDK返回结果: {}", result);
        
        JsonNode jsonNode = extractResultsArray(result);
        assertEquals(1, jsonNode.size(), "Case5 应该只返回与 ALARM002 精确关联的 1 条 NetworkElement");

        JsonNode firstRow = jsonNode.get(0);
        assertTrue(firstRow.has("ne"), "结果必须包含 ne 字段");
        assertEquals(
            "{DN=388581df-50a3-4d9f-96d4-15faf36f9caf,id=eccc2c94-6a31-45ea-a16e-0c709939cbe5,label=NetworkElement,name=NE001,resId=eccc2c94-6a31-45ea-a16e-0c709939cbe5}",
            buildEntitySignature(firstRow.get("ne")),
            "Case5 必须精确返回与 ALARM002 关联的 NE001"
        );
    }
    
    @Test
    @DisplayName("验证所有基础数据完整性")
    @DisabledIf("isTuGraphNotAvailable")
    void testDataIntegrity() throws Exception {
        String cypher = "MATCH (ne:NetworkElement) RETURN ne";
        String result = sdk.execute(cypher);
        logger.info("数据完整性 - NetworkElement查询结果: {}", result);
        JsonNode jsonNode = extractResultsArray(result);
        assertEquals(2, jsonNode.size(), "应该有2个NetworkElement");
        Set<String> expectedNetworkElements = Set.of(
            "{DN=388581df-50a3-4d9f-96d4-15faf36f9caf,id=eccc2c94-6a31-45ea-a16e-0c709939cbe5,label=NetworkElement,name=NE001,resId=eccc2c94-6a31-45ea-a16e-0c709939cbe5}",
            "{DN=a158b427-4a65-4adf-9096-3c8225519cca,id=552dddad-84c4-4d5e-9014-481443ec23b5,label=NetworkElement,name=NE002,resId=552dddad-84c4-4d5e-9014-481443ec23b5}"
        );
        Set<String> actualNetworkElements = new LinkedHashSet<>();
        for (JsonNode row : jsonNode) {
            assertTrue(row.has("ne"), "NetworkElement 结果必须包含 ne 字段");
            actualNetworkElements.add(buildEntitySignature(row.get("ne")));
        }
        assertEquals(expectedNetworkElements, actualNetworkElements, "NetworkElement 基础数据必须与构造数据完全一致");
        
        cypher = "MATCH (ltp:LTP) RETURN ltp";
        result = sdk.execute(cypher);
        logger.info("数据完整性 - LTP查询结果: {}", result);
        jsonNode = extractResultsArray(result);
        assertEquals(4, jsonNode.size(), "应该有4个LTP");
        Set<String> expectedLtps = Set.of(
            "{id=db82ad76-8bdc-4f4b-96a0-a5e91c6861fb,label=LTP,name=LTP001,parentResId=eccc2c94-6a31-45ea-a16e-0c709939cbe5,resId=db82ad76-8bdc-4f4b-96a0-a5e91c6861fb}",
            "{id=c889973b-8bd7-4eb9-899b-b1cc9bf18a2e,label=LTP,name=LTP002,parentResId=eccc2c94-6a31-45ea-a16e-0c709939cbe5,resId=c889973b-8bd7-4eb9-899b-b1cc9bf18a2e}",
            "{id=537ae2db-80d6-4b13-9cee-140a9af811ca,label=LTP,name=LTP003,parentResId=552dddad-84c4-4d5e-9014-481443ec23b5,resId=537ae2db-80d6-4b13-9cee-140a9af811ca}",
            "{id=7c013137-db19-4f21-bbbd-6908cd2033d8,label=LTP,name=LTP004,parentResId=552dddad-84c4-4d5e-9014-481443ec23b5,resId=7c013137-db19-4f21-bbbd-6908cd2033d8}"
        );
        Set<String> actualLtps = new LinkedHashSet<>();
        for (JsonNode row : jsonNode) {
            assertTrue(row.has("ltp"), "LTP 结果必须包含 ltp 字段");
            actualLtps.add(buildEntitySignature(row.get("ltp")));
        }
        assertEquals(expectedLtps, actualLtps, "LTP 基础数据必须与构造数据完全一致");
        
        cypher = "MATCH (ne:NetworkElement)-[:NEHasLtps]->(ltp:LTP) RETURN ne, ltp";
        result = sdk.execute(cypher);
        logger.info("数据完整性 - 关系查询结果: {}", result);
        jsonNode = extractResultsArray(result);
        assertEquals(4, jsonNode.size(), "应该有4条NEHasLtps关系");
        Set<String> expectedRelations = Set.of(
            "ne={DN=388581df-50a3-4d9f-96d4-15faf36f9caf,id=eccc2c94-6a31-45ea-a16e-0c709939cbe5,label=NetworkElement,name=NE001,resId=eccc2c94-6a31-45ea-a16e-0c709939cbe5} | ltp={id=db82ad76-8bdc-4f4b-96a0-a5e91c6861fb,label=LTP,name=LTP001,parentResId=eccc2c94-6a31-45ea-a16e-0c709939cbe5,resId=db82ad76-8bdc-4f4b-96a0-a5e91c6861fb}",
            "ne={DN=388581df-50a3-4d9f-96d4-15faf36f9caf,id=eccc2c94-6a31-45ea-a16e-0c709939cbe5,label=NetworkElement,name=NE001,resId=eccc2c94-6a31-45ea-a16e-0c709939cbe5} | ltp={id=c889973b-8bd7-4eb9-899b-b1cc9bf18a2e,label=LTP,name=LTP002,parentResId=eccc2c94-6a31-45ea-a16e-0c709939cbe5,resId=c889973b-8bd7-4eb9-899b-b1cc9bf18a2e}",
            "ne={DN=a158b427-4a65-4adf-9096-3c8225519cca,id=552dddad-84c4-4d5e-9014-481443ec23b5,label=NetworkElement,name=NE002,resId=552dddad-84c4-4d5e-9014-481443ec23b5} | ltp={id=537ae2db-80d6-4b13-9cee-140a9af811ca,label=LTP,name=LTP003,parentResId=552dddad-84c4-4d5e-9014-481443ec23b5,resId=537ae2db-80d6-4b13-9cee-140a9af811ca}",
            "ne={DN=a158b427-4a65-4adf-9096-3c8225519cca,id=552dddad-84c4-4d5e-9014-481443ec23b5,label=NetworkElement,name=NE002,resId=552dddad-84c4-4d5e-9014-481443ec23b5} | ltp={id=7c013137-db19-4f21-bbbd-6908cd2033d8,label=LTP,name=LTP004,parentResId=552dddad-84c4-4d5e-9014-481443ec23b5,resId=7c013137-db19-4f21-bbbd-6908cd2033d8}"
        );
        Set<String> actualRelations = new LinkedHashSet<>();
        for (JsonNode row : jsonNode) {
            actualRelations.add(buildRowSignature(row, "ne", "ltp"));
        }
        assertEquals(expectedRelations, actualRelations, "NEHasLtps 关系必须与整张图构造完全一致");
        
        logger.info("数据完整性验证通过: 2个NetworkElement, 4个LTP, 4条关系");
    }
    
    private MockExternalAdapter createDruidAdapter() {
        MockExternalAdapter adapter = new MockExternalAdapter();
        adapter.setDataSourceName("druid-service");
        
        adapter.registerResponse("queryKpiByParentResId", MockExternalAdapter.MockResponse.create()
            .generator(query -> {
                List<GraphEntity> entities = new ArrayList<>();
                List<String> inputIds = query.getInputIds();
                
                if (inputIds == null || inputIds.isEmpty()) {
                    return entities;
                }
                
                for (String parentId : inputIds) {
                    if ("eccc2c94-6a31-45ea-a16e-0c709939cbe5".equals(parentId)) {
                        GraphEntity kpi = new GraphEntity();
                        kpi.setId("0079bb0f-0e0a-44de-b595-cab5c22324ef");
                        kpi.setLabel("KPI");
                        kpi.setType(GraphEntity.EntityType.NODE);
                        kpi.setVariableName("target");
                        Map<String, Object> props = new HashMap<>();
                        props.put("resId", "0079bb0f-0e0a-44de-b595-cab5c22324ef");
                        props.put("name", "KPI001");
                        props.put("parentResId", "eccc2c94-6a31-45ea-a16e-0c709939cbe5");
                        props.put("time", 1775825156755L);
                        kpi.setProperties(props);
                        entities.add(kpi);
                    } else if ("552dddad-84c4-4d5e-9014-481443ec23b5".equals(parentId)) {
                        GraphEntity kpi = new GraphEntity();
                        kpi.setId("5af68cc1-3fad-486e-8073-cb0d1181a3e4");
                        kpi.setLabel("KPI");
                        kpi.setType(GraphEntity.EntityType.NODE);
                        kpi.setVariableName("target");
                        Map<String, Object> props = new HashMap<>();
                        props.put("resId", "5af68cc1-3fad-486e-8073-cb0d1181a3e4");
                        props.put("name", "KPI001");
                        props.put("parentResId", "552dddad-84c4-4d5e-9014-481443ec23b5");
                        props.put("time", 1775825856755L);
                        kpi.setProperties(props);
                        entities.add(kpi);
                    }
                }
                
                return entities;
            }));
        
        adapter.registerResponse("queryKpi2ByParentResId", MockExternalAdapter.MockResponse.create()
            .generator(query -> {
                List<GraphEntity> entities = new ArrayList<>();
                List<String> inputIds = query.getInputIds();
                
                if (inputIds == null || inputIds.isEmpty()) {
                    return entities;
                }
                
                for (String parentId : inputIds) {
                    if ("db82ad76-8bdc-4f4b-96a0-a5e91c6861fb".equals(parentId)) {
                        GraphEntity kpi2 = new GraphEntity();
                        kpi2.setId("90d7f2d4-4822-438e-a0cd-b2e7011e9786");
                        kpi2.setLabel("KPI2");
                        kpi2.setType(GraphEntity.EntityType.NODE);
                        kpi2.setVariableName("target");
                        Map<String, Object> props = new HashMap<>();
                        props.put("resId", "90d7f2d4-4822-438e-a0cd-b2e7011e9786");
                        props.put("name", "KPI2001");
                        props.put("parentResId", "db82ad76-8bdc-4f4b-96a0-a5e91c6861fb");
                        props.put("time", 1775825256755L);
                        kpi2.setProperties(props);
                        entities.add(kpi2);
                    } else if ("c889973b-8bd7-4eb9-899b-b1cc9bf18a2e".equals(parentId)) {
                        GraphEntity kpi2 = new GraphEntity();
                        kpi2.setId("5ff8f0f4-77a3-403d-889a-d615b4fd586a");
                        kpi2.setLabel("KPI2");
                        kpi2.setType(GraphEntity.EntityType.NODE);
                        kpi2.setVariableName("target");
                        Map<String, Object> props = new HashMap<>();
                        props.put("resId", "5ff8f0f4-77a3-403d-889a-d615b4fd586a");
                        props.put("name", "KPI2002");
                        props.put("parentResId", "c889973b-8bd7-4eb9-899b-b1cc9bf18a2e");
                        props.put("time", 1775825556755L);
                        kpi2.setProperties(props);
                        entities.add(kpi2);
                    } else if ("537ae2db-80d6-4b13-9cee-140a9af811ca".equals(parentId)) {
                        GraphEntity kpi2 = new GraphEntity();
                        kpi2.setId("0036d5a5-ae90-4c76-98d8-113c616d9e8d");
                        kpi2.setLabel("KPI2");
                        kpi2.setType(GraphEntity.EntityType.NODE);
                        kpi2.setVariableName("target");
                        Map<String, Object> props = new HashMap<>();
                        props.put("resId", "0036d5a5-ae90-4c76-98d8-113c616d9e8d");
                        props.put("name", "KPI2003");
                        props.put("parentResId", "537ae2db-80d6-4b13-9cee-140a9af811ca");
                        props.put("time", 1775822856755L);
                        kpi2.setProperties(props);
                        entities.add(kpi2);
                    } else if ("7c013137-db19-4f21-bbbd-6908cd2033d8".equals(parentId)) {
                        GraphEntity kpi2 = new GraphEntity();
                        kpi2.setId("d53fcfab-d0cb-4fe5-84a9-73de0f0b2850");
                        kpi2.setLabel("KPI2");
                        kpi2.setType(GraphEntity.EntityType.NODE);
                        kpi2.setVariableName("target");
                        Map<String, Object> props = new HashMap<>();
                        props.put("resId", "d53fcfab-d0cb-4fe5-84a9-73de0f0b2850");
                        props.put("name", "KPI2004");
                        props.put("parentResId", "7c013137-db19-4f21-bbbd-6908cd2033d8");
                        props.put("time", 1775825951299L);
                        kpi2.setProperties(props);
                        entities.add(kpi2);
                    }
                }
                
                return entities;
            }));
        
        return adapter;
    }
    
    private MockExternalAdapter createZenithAdapter() {
        MockExternalAdapter adapter = new MockExternalAdapter();
        adapter.setDataSourceName("zenith-service");
        
        adapter.registerResponse("queryAlarmsByMedn", MockExternalAdapter.MockResponse.create()
            .generator(query -> {
                List<GraphEntity> entities = new ArrayList<>();
                List<String> inputIds = query.getInputIds();
                
                if (inputIds == null || inputIds.isEmpty()) {
                    return entities;
                }
                
                for (String medn : inputIds) {
                    if ("388581df-50a3-4d9f-96d4-15faf36f9caf".equals(medn)) {
                        GraphEntity alarm1 = new GraphEntity();
                        alarm1.setId("0079bb0f-0e0a-44de-b595-cab5c22324ef");
                        alarm1.setLabel("ALARM");
                        alarm1.setType(GraphEntity.EntityType.NODE);
                        alarm1.setVariableName("target");
                        Map<String, Object> props1 = new HashMap<>();
                        props1.put("CSN", "0079bb0f-0e0a-44de-b595-cab5c22324ef");
                        props1.put("MENAME", "ALARM001");
                        props1.put("MEDN", "388581df-50a3-4d9f-96d4-15faf36f9caf");
                        props1.put("OCCURTIME", 1775825151299L);
                        props1.put("CLEARTIME", 1775825951299L);
                        alarm1.setProperties(props1);
                        entities.add(alarm1);
                        
                        GraphEntity alarm2 = new GraphEntity();
                        alarm2.setId("afa87ad3-0834-467c-ba2e-1315fb9ba0cb");
                        alarm2.setLabel("ALARM");
                        alarm2.setType(GraphEntity.EntityType.NODE);
                        alarm2.setVariableName("target");
                        Map<String, Object> props2 = new HashMap<>();
                        props2.put("CSN", "afa87ad3-0834-467c-ba2e-1315fb9ba0cb");
                        props2.put("MENAME", "ALARM002");
                        props2.put("MEDN", "388581df-50a3-4d9f-96d4-15faf36f9caf");
                        props2.put("OCCURTIME", 1775825251299L);
                        props2.put("CLEARTIME", 1775825551299L);
                        alarm2.setProperties(props2);
                        entities.add(alarm2);
                    } else if ("a158b427-4a65-4adf-9096-3c8225519cca".equals(medn)) {
                        GraphEntity alarm3 = new GraphEntity();
                        alarm3.setId("0148b488-f857-40a3-8e85-7b5ecf16f6ac");
                        alarm3.setLabel("ALARM");
                        alarm3.setType(GraphEntity.EntityType.NODE);
                        alarm3.setVariableName("target");
                        Map<String, Object> props3 = new HashMap<>();
                        props3.put("CSN", "0148b488-f857-40a3-8e85-7b5ecf16f6ac");
                        props3.put("MENAME", "ALARM003");
                        props3.put("MEDN", "a158b427-4a65-4adf-9096-3c8225519cca");
                        props3.put("OCCURTIME", 1775825151299L);
                        props3.put("CLEARTIME", 1775825951299L);
                        alarm3.setProperties(props3);
                        entities.add(alarm3);
                    }
                }
                
                return entities;
            }));
        
        adapter.registerResponse("getByLabel", MockExternalAdapter.MockResponse.create()
            .generator(query -> {
                List<GraphEntity> entities = new ArrayList<>();
                Map<String, Object> filters = query.getFilters();
                
                String name = filters != null ? (String) filters.get("name") : null;
                String mename = filters != null ? (String) filters.get("MENAME") : null;
                String csn = filters != null ? (String) filters.get("CSN") : null;
                String label = filters != null ? (String) filters.get("_label") : null;
                
                String effectiveName = mename != null ? mename : name;
                
                if ("ALARM001".equals(effectiveName) && "0079bb0f-0e0a-44de-b595-cab5c22324ef".equals(csn)) {
                    GraphEntity alarm = new GraphEntity();
                    alarm.setId("0079bb0f-0e0a-44de-b595-cab5c22324ef");
                    alarm.setLabel("ALARM");
                    alarm.setType(GraphEntity.EntityType.NODE);
                    alarm.setVariableName("alarm");
                    Map<String, Object> props = new HashMap<>();
                    props.put("CSN", "0079bb0f-0e0a-44de-b595-cab5c22324ef");
                    props.put("MENAME", "ALARM001");
                    props.put("MEDN", "388581df-50a3-4d9f-96d4-15faf36f9caf");
                    props.put("OCCURTIME", 1775825151299L);
                    props.put("CLEARTIME", 1775825951299L);
                    alarm.setProperties(props);
                    entities.add(alarm);
                } else if ("ALARM002".equals(effectiveName) && "afa87ad3-0834-467c-ba2e-1315fb9ba0cb".equals(csn)) {
                    GraphEntity alarm = new GraphEntity();
                    alarm.setId("afa87ad3-0834-467c-ba2e-1315fb9ba0cb");
                    alarm.setLabel("ALARM");
                    alarm.setType(GraphEntity.EntityType.NODE);
                    alarm.setVariableName("alarm");
                    Map<String, Object> props = new HashMap<>();
                    props.put("CSN", "afa87ad3-0834-467c-ba2e-1315fb9ba0cb");
                    props.put("MENAME", "ALARM002");
                    props.put("MEDN", "388581df-50a3-4d9f-96d4-15faf36f9caf");
                    props.put("OCCURTIME", 1775825251299L);
                    props.put("CLEARTIME", 1775825551299L);
                    alarm.setProperties(props);
                    entities.add(alarm);
                }
                
                return entities;
            }));
        
        return adapter;
    }
    
    static boolean isTuGraphNotAvailable() {
        return !tuGraphAvailable;
    }

    private JsonNode extractResultsArray(String result) throws Exception {
        JsonNode jsonNode = JsonUtil.readTree(result);
        if (jsonNode.has("results")) {
            jsonNode = jsonNode.get("results");
        }
        assertTrue(jsonNode.isArray(), "结果应该是数组");
        return jsonNode;
    }

    private String buildRowSignature(JsonNode row, String... variables) {
        assertTrue(row.isObject(), "结果行必须是对象");
        List<String> parts = new ArrayList<>();
        for (String variable : variables) {
            assertTrue(row.has(variable), "结果行必须包含字段: " + variable);
            parts.add(variable + "=" + buildEntitySignature(row.get(variable)));
        }
        return String.join(" | ", parts);
    }

    private String buildEntitySignature(JsonNode entity) {
        assertTrue(entity.isObject(), "实体必须是对象");
        TreeMap<String, String> fields = new TreeMap<>();
        entity.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue().asText()));

        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            parts.add(entry.getKey() + "=" + entry.getValue());
        }
        return "{" + String.join(",", parts) + "}";
    }

    private String buildPathSignature(JsonNode path) {
        assertTrue(path.isArray(), "每条路径必须是数组");
        assertTrue(path.size() == 3 || path.size() == 5, "路径长度必须是3或5");

        List<String> segments = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            JsonNode element = path.get(i);
            assertTrue(element.has("type"), "路径元素必须包含 type 字段");
            assertTrue(element.has("label"), "路径元素必须包含 label 字段");

            String type = element.get("type").asText();
            String label = element.get("label").asText();

            if ("node".equals(type)) {
                assertTrue(element.has("props"), "节点元素必须包含 props 字段");
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
}
