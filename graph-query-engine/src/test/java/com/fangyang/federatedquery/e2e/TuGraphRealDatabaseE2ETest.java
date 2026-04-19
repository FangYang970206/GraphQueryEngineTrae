package com.fangyang.federatedquery.e2e;

import com.fangyang.federatedquery.adapter.MockExternalAdapter;
import com.fangyang.federatedquery.GraphEntity;
import com.fangyang.federatedquery.QueryResult;
import com.fangyang.federatedquery.aggregator.*;
import com.fangyang.datasource.*;
import com.fangyang.federatedquery.executor.DependencyResolver;
import com.fangyang.federatedquery.executor.FederatedExecutor;
import com.fangyang.federatedquery.executor.ResultEnricher;
import com.fangyang.metadata.*;
import com.fangyang.federatedquery.parser.CypherParserFacade;
import com.fangyang.federatedquery.parser.CypherASTVisitor;
import com.fangyang.federatedquery.rewriter.MixedPatternRewriter;
import com.fangyang.federatedquery.rewriter.PhysicalQueryBuilder;
import com.fangyang.federatedquery.rewriter.QueryRewriter;
import com.fangyang.federatedquery.rewriter.VirtualEdgeDetector;
import com.fangyang.federatedquery.reliability.WhereConditionPushdown;
import com.fangyang.federatedquery.testutil.GraphQueryMetaFactory;
import com.fangyang.federatedquery.sdk.GraphQuerySDK;
import com.fangyang.federatedquery.util.RecordConverter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIf;
import org.neo4j.driver.Record;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TuGraph真实数据库端到端测试")
class TuGraphRealDatabaseE2ETest {
    
    private static TuGraphConnector connector;
    private static boolean tuGraphAvailable = false;
    private GraphQuerySDK sdk;
    private MetadataQueryService metadataQueryService;
    private MockExternalAdapter kpiAdapter;
    private MockExternalAdapter alarmAdapter;
    
    @BeforeAll
    static void setUpClass() {
        TuGraphConfig config = TuGraphConfig.defaultConfig();
        try {
            connector = DataSourceFactory.createTuGraphConnector(config);
            tuGraphAvailable = connector.isConnected();
            
            if (tuGraphAvailable) {
                setupTestData();
            }
        } catch (Exception e) {
            tuGraphAvailable = false;
            System.out.println("TuGraph not available, skipping integration tests: " + e.getMessage());
        }
    }
    
    private static void setupTestData() {
        connector.executeQuery("MATCH (n) DETACH DELETE n");
        
        try {
            connector.executeQuery("CALL db.dropEdgeLabel('NEHasLtps')");
        } catch (Exception e) {
            System.out.println("Drop NEHasLtps edge label warning: " + e.getMessage());
        }
        
        try {
            connector.executeQuery("CALL db.dropVertexLabel('NetworkElement')");
        } catch (Exception e) {
            System.out.println("Drop NetworkElement label warning: " + e.getMessage());
        }
        
        try {
            connector.executeQuery("CALL db.dropVertexLabel('LTP')");
        } catch (Exception e) {
            System.out.println("Drop LTP label warning: " + e.getMessage());
        }
        
        try {
            connector.executeQuery(
                "CALL db.createVertexLabel('NetworkElement', 'resId', 'resId', 'STRING', false, 'name', 'STRING', false, 'DN', 'STRING', false)"
            );
            System.out.println("NetworkElement label created successfully");
        } catch (Exception e) {
            System.out.println("NetworkElement label creation: " + e.getMessage());
        }
        
        try {
            connector.executeQuery(
                "CALL db.createVertexLabel('LTP', 'resId', 'resId', 'STRING', false, 'name', 'STRING', false, 'parentResId', 'STRING', false)"
            );
            System.out.println("LTP label created successfully");
        } catch (Exception e) {
            System.out.println("LTP label creation: " + e.getMessage());
        }
        
        try {
            connector.executeQuery("CALL db.createEdgeLabel('NEHasLtps', '{}', 'id', 'INT64', true)");
            System.out.println("NEHasLtps edge label created successfully");
        } catch (Exception e) {
            System.out.println("NEHasLtps edge label creation: " + e.getMessage());
        }
        
        connector.executeQuery(
            "CREATE (ne1:NetworkElement {resId: 'ne1', name: 'NE001', DN: 'dn001'})"
        );
        connector.executeQuery(
            "CREATE (ne2:NetworkElement {resId: 'ne2', name: 'NE002', DN: 'dn002'})"
        );
        connector.executeQuery(
            "CREATE (ltp1:LTP {resId: 'ltp1', name: 'LTP1', parentResId: 'ne1'})"
        );
        connector.executeQuery(
            "CREATE (ltp2:LTP {resId: 'ltp2', name: 'LTP2', parentResId: 'ne1'})"
        );
        
        connector.executeQuery(
            "MATCH (ne1:NetworkElement {resId: 'ne1'}), (ltp1:LTP {resId: 'ltp1'}) CREATE (ne1)-[:NEHasLtps]->(ltp1)"
        );
        connector.executeQuery(
            "MATCH (ne1:NetworkElement {resId: 'ne1'}), (ltp2:LTP {resId: 'ltp2'}) CREATE (ne1)-[:NEHasLtps]->(ltp2)"
        );
        connector.executeQuery(
            "MATCH (ne2:NetworkElement {resId: 'ne2'}), (ltp1:LTP {resId: 'ltp1'}) CREATE (ne2)-[:NEHasLtps]->(ltp1)"
        );
    }
    
    @AfterAll
    static void tearDownClass() {
        if (connector != null) {
            connector.close();
        }
    }
    
    @BeforeEach
    void setUp() {
        GraphQueryMetaFactory metaFactory = GraphQueryMetaFactory.createStandard();
        metadataQueryService = metaFactory.metadataQueryService();
        
        kpiAdapter = metaFactory.createAdapter("kpi-service");
        alarmAdapter = metaFactory.createAdapter("alarm-service");
        MockExternalAdapter tugraphAdapter = metaFactory.createAdapter("tugraph");
        
        sdk = metaFactory.createSdk();
    }
    
    @Test
    @DisplayName("真实数据库 - 简单节点查询")
    @DisabledIf("isTuGraphNotAvailable")
    void testRealDatabaseSimpleQuery() throws Exception {
        String cypher = "MATCH (n:NetworkElement) RETURN n ORDER BY n.name LIMIT 10";
        
        List<Record> records = connector.executeQuery(cypher);
        
        assertNotNull(records, "结果不能为空");
        assertTrue(records.size() >= 2, "应该至少有2个NetworkElement节点");
        
        Record firstRecord = records.get(0);
        assertTrue(firstRecord.containsKey("n"), "记录应该包含n字段");
    }
    
    @Test
    @DisplayName("真实数据库 - 关系查询")
    @DisabledIf("isTuGraphNotAvailable")
    void testRealDatabaseRelationshipQuery() throws Exception {
        String cypher = "MATCH (ne:NetworkElement)-[:NEHasLtps]->(ltp:LTP) RETURN ne, ltp";
        
        List<Record> records = connector.executeQuery(cypher);
        
        assertNotNull(records, "结果不能为空");
        assertTrue(records.size() >= 1, "应该至少有1条关系记录");
        
        for (Record record : records) {
            assertTrue(record.containsKey("ne"), "记录应该包含ne字段");
            assertTrue(record.containsKey("ltp"), "记录应该包含ltp字段");
        }
    }
    
    @Test
    @DisplayName("真实数据库 - WHERE条件查询")
    @DisabledIf("isTuGraphNotAvailable")
    void testRealDatabaseWhereQuery() throws Exception {
        String cypher = "MATCH (n:NetworkElement) WHERE n.name = 'NE001' RETURN n";
        
        List<Record> records = connector.executeQuery(cypher);
        
        assertNotNull(records, "结果不能为空");
        assertEquals(1, records.size(), "应该只有1个NE001节点");
        
        Record record = records.get(0);
        assertTrue(record.containsKey("n"), "记录应该包含n字段");
    }
    
    @Test
    @DisplayName("真实数据库 - 路径查询")
    @DisabledIf("isTuGraphNotAvailable")
    void testRealDatabasePathQuery() throws Exception {
        String cypher = "MATCH p=(ne:NetworkElement)-[:NEHasLtps]->(ltp:LTP) RETURN p LIMIT 5";
        
        List<Record> records = connector.executeQuery(cypher);
        
        assertNotNull(records, "结果不能为空");
        assertTrue(records.size() >= 1, "应该至少有1条路径记录");
        
        Record record = records.get(0);
        assertTrue(record.containsKey("p"), "记录应该包含p字段");
    }
    
    @Test
    @DisplayName("真实数据库 - 聚合查询")
    @DisabledIf("isTuGraphNotAvailable")
    void testRealDatabaseAggregationQuery() throws Exception {
        String cypher = "MATCH (n:NetworkElement) RETURN count(n) AS total";
        
        List<Record> records = connector.executeQuery(cypher);
        
        assertNotNull(records, "结果不能为空");
        assertEquals(1, records.size(), "聚合查询应该返回1条记录");
        
        Record record = records.get(0);
        assertTrue(record.containsKey("total"), "记录应该包含total字段");
        assertTrue(record.get("total").asLong() >= 2, "应该至少有2个NetworkElement节点");
    }
    
    @Test
    @DisplayName("SDK executeRecords - 返回Map格式")
    @DisabledIf("isTuGraphNotAvailable")
    void testSdkExecuteRecords() throws Exception {
        GraphEntity neEntity = GraphEntity.node("ne1", "NetworkElement");
        neEntity.setProperty("name", "NE001");
        neEntity.setProperty("type", "Router");
        neEntity.setVariableName("n");
        
        MockExternalAdapter tugraphAdapter = new MockExternalAdapter();
        tugraphAdapter.setDataSourceName("tugraph");
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(neEntity));
        
        DependencyResolver dependencyResolver = new DependencyResolver();
        ResultEnricher resultEnricher = new ResultEnricher();
        FederatedExecutor executor = new FederatedExecutor(metadataQueryService, dependencyResolver, resultEnricher);
        executor.registerAdapter("tugraph", tugraphAdapter);
        executor.registerAdapter("kpi-service", kpiAdapter);
        executor.registerAdapter("alarm-service", alarmAdapter);
        
        CypherParserFacade parser = new CypherParserFacade(new CypherASTVisitor());
        VirtualEdgeDetector detector = new VirtualEdgeDetector(metadataQueryService);
        WhereConditionPushdown whereConditionPushdown = new WhereConditionPushdown(metadataQueryService);
        PhysicalQueryBuilder physicalQueryBuilder = new PhysicalQueryBuilder();
        MixedPatternRewriter mixedPatternRewriter = new MixedPatternRewriter(metadataQueryService, physicalQueryBuilder);
        QueryRewriter rewriter = new QueryRewriter(metadataQueryService, detector, whereConditionPushdown, physicalQueryBuilder, mixedPatternRewriter);
        UnionDeduplicator deduplicator = new UnionDeduplicator();
        
        sdk = new GraphQuerySDK(parser, rewriter, executor, deduplicator);
        
        String cypher = "MATCH (n:NetworkElement) RETURN n LIMIT 10";
        
        List<Map<String, Object>> records = sdk.executeRecords(cypher);
        
        assertNotNull(records, "结果不能为空");
        assertEquals(1, records.size(), "应该有1条记录");
        
        Map<String, Object> record = records.get(0);
        assertTrue(record.containsKey("n"), "记录应该包含n字段");
        
        @SuppressWarnings("unchecked")
        Map<String, Object> node = (Map<String, Object>) record.get("n");
        assertTrue(node.containsKey("_id"), "节点应该包含_id字段");
        assertTrue(node.containsKey("_labels"), "节点应该包含_labels字段");
    }
    
    @Test
    @DisplayName("SDK executeWithConnector - 直接连接TuGraph")
    @DisabledIf("isTuGraphNotAvailable")
    void testSdkExecuteWithConnector() throws Exception {
        CypherParserFacade parser = new CypherParserFacade(new CypherASTVisitor());
        VirtualEdgeDetector detector = new VirtualEdgeDetector(metadataQueryService);
        WhereConditionPushdown whereConditionPushdown = new WhereConditionPushdown(metadataQueryService);
        PhysicalQueryBuilder physicalQueryBuilder = new PhysicalQueryBuilder();
        MixedPatternRewriter mixedPatternRewriter = new MixedPatternRewriter(metadataQueryService, physicalQueryBuilder);
        QueryRewriter rewriter = new QueryRewriter(metadataQueryService, detector, whereConditionPushdown, physicalQueryBuilder, mixedPatternRewriter);
        DependencyResolver dependencyResolver = new DependencyResolver();
        ResultEnricher resultEnricher = new ResultEnricher();
        FederatedExecutor executor = new FederatedExecutor(metadataQueryService, dependencyResolver, resultEnricher);
        UnionDeduplicator deduplicator = new UnionDeduplicator();
        
        TuGraphConnector sdkConnector = DataSourceFactory.createTuGraphConnector(TuGraphConfig.defaultConfig());
        sdk = new GraphQuerySDK(parser, rewriter, executor, deduplicator, sdkConnector);
        
        String cypher = "MATCH (n:NetworkElement) RETURN n ORDER BY n.name LIMIT 5";
        
        List<Record> records = sdk.executeWithConnector(cypher);
        
        assertNotNull(records, "结果不能为空");
        assertTrue(records.size() >= 1, "应该至少有1条记录");
        
        sdkConnector.close();
    }
    
    @Test
    @DisplayName("SDK executeWithConnector - 参数化查询")
    @DisabledIf("isTuGraphNotAvailable")
    void testSdkExecuteWithConnectorParameterized() throws Exception {
        CypherParserFacade parser = new CypherParserFacade(new CypherASTVisitor());
        VirtualEdgeDetector detector = new VirtualEdgeDetector(metadataQueryService);
        WhereConditionPushdown whereConditionPushdown = new WhereConditionPushdown(metadataQueryService);
        PhysicalQueryBuilder physicalQueryBuilder = new PhysicalQueryBuilder();
        MixedPatternRewriter mixedPatternRewriter = new MixedPatternRewriter(metadataQueryService, physicalQueryBuilder);
        QueryRewriter rewriter = new QueryRewriter(metadataQueryService, detector, whereConditionPushdown, physicalQueryBuilder, mixedPatternRewriter);
        DependencyResolver dependencyResolver = new DependencyResolver();
        ResultEnricher resultEnricher = new ResultEnricher();
        FederatedExecutor executor = new FederatedExecutor(metadataQueryService, dependencyResolver, resultEnricher);
        UnionDeduplicator deduplicator = new UnionDeduplicator();
        
        TuGraphConnector sdkConnector = DataSourceFactory.createTuGraphConnector(TuGraphConfig.defaultConfig());
        sdk = new GraphQuerySDK(parser, rewriter, executor, deduplicator, sdkConnector);
        
        String cypher = "MATCH (n:NetworkElement) WHERE n.name = $name RETURN n";
        
        List<Record> records = sdk.executeWithConnector(cypher, "name", "NE001");
        
        assertNotNull(records, "结果不能为空");
        assertEquals(1, records.size(), "应该只有1个NE001节点");
        
        sdkConnector.close();
    }
    
    @Test
    @DisplayName("RecordConverter - 转换测试")
    void testRecordConverter() {
        List<Map<String, Object>> internalResults = new ArrayList<>();
        
        Map<String, Object> row = new LinkedHashMap<>();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "ne1");
        node.put("label", "NetworkElement");
        node.put("name", "NE001");
        node.put("type", "Router");
        row.put("n", node);
        internalResults.add(row);
        
        List<Map<String, Object>> converted = RecordConverter.convertToRecordMaps(internalResults);
        
        assertNotNull(converted, "转换结果不能为空");
        assertEquals(1, converted.size(), "应该有1条记录");
        
        Map<String, Object> convertedRow = converted.get(0);
        assertTrue(convertedRow.containsKey("n"), "记录应该包含n字段");
        
        @SuppressWarnings("unchecked")
        Map<String, Object> convertedNode = (Map<String, Object>) convertedRow.get("n");
        assertTrue(convertedNode.containsKey("_id"), "节点应该包含_id字段");
        assertTrue(convertedNode.containsKey("_labels"), "节点应该包含_labels字段");
        assertTrue(convertedNode.containsKey("_properties"), "节点应该包含_properties字段");
    }
    
    static boolean isTuGraphNotAvailable() {
        return !tuGraphAvailable;
    }
}
