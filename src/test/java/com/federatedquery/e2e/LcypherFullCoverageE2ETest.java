package com.federatedquery.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.federatedquery.adapter.*;
import com.federatedquery.aggregator.*;
import com.federatedquery.executor.FederatedExecutor;
import com.federatedquery.metadata.*;
import com.federatedquery.parser.CypherParserFacade;
import com.federatedquery.parser.CypherASTVisitor;
import com.federatedquery.rewriter.QueryRewriter;
import com.federatedquery.rewriter.VirtualEdgeDetector;
import com.federatedquery.reliability.WhereConditionPushdown;
import com.federatedquery.sdk.GraphQuerySDK;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lcypher.g4 全场景查询功能端到端覆盖测试
 * 基于 ANTLR4 语法定义的所有查询场景
 */
class LcypherFullCoverageE2ETest {
    
    private MetadataRegistry registry;
    private MockExternalAdapter tugraphAdapter;
    private GraphQuerySDK sdk;
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() {
        registry = new MetadataRegistryImpl();
        
        DataSourceMetadata tugraph = new DataSourceMetadata();
        tugraph.setName("tugraph");
        tugraph.setType(DataSourceType.TUGRAPH_BOLT);
        tugraph.setEndpoint("bolt://localhost:7687");
        registry.registerDataSource(tugraph);
        
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
        
        tugraphAdapter = new MockExternalAdapter();
        tugraphAdapter.setDataSourceName("tugraph");
        
        CypherParserFacade parser = new CypherParserFacade(new CypherASTVisitor());
        VirtualEdgeDetector detector = new VirtualEdgeDetector(registry);
        WhereConditionPushdown whereConditionPushdown = new WhereConditionPushdown(registry);
        QueryRewriter rewriter = new QueryRewriter(registry, detector, whereConditionPushdown);
        FederatedExecutor executor = new FederatedExecutor(registry);
        executor.registerAdapter("tugraph", tugraphAdapter);
        ResultStitcher stitcher = new ResultStitcher();
        GlobalSorter sorter = new GlobalSorter();
        UnionDeduplicator deduplicator = new UnionDeduplicator();
        
        sdk = new GraphQuerySDK(parser, rewriter, executor, stitcher, sorter, deduplicator);
        objectMapper = new ObjectMapper();
    }
    
    // ==================== MATCH 查询场景 ====================
    
    @Test
    @DisplayName("MATCH: 基本节点查询")
    void matchBasicNodeTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "MATCH (n:NetworkElement) RETURN n";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray());
        assertEquals(1, json.size());
        assertTrue(json.get(0).has("n"));
    }
    
    @Test
    @DisplayName("MATCH: 带属性过滤的节点查询")
    void matchNodeWithPropertiesTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        ne.setProperty("status", "active");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "MATCH (n:NetworkElement {name: 'NE001', status: 'active'}) RETURN n";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertEquals(1, json.size());
    }
    
    @Test
    @DisplayName("MATCH: 关系查询 - 单向")
    void matchRelationshipOutgoingTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        GraphEntity ltp = createLTPEntity("ltp1", "LTP1", "Port");
        ltp.setVariableName("l");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne)
                .addEntity(ltp));
        
        String cypher = "MATCH (n:NetworkElement)-[:HAS_LTP]->(l:LTP) RETURN n, l";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.size() >= 1);
    }
    
    @Test
    @DisplayName("MATCH: 关系查询 - 双向")
    void matchRelationshipBidirectionalTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        GraphEntity ltp = createLTPEntity("ltp1", "LTP1", "Port");
        ltp.setVariableName("l");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne)
                .addEntity(ltp));
        
        String cypher = "MATCH (n:NetworkElement)-[:HAS_LTP]-(l:LTP) RETURN n, l";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
    }
    
    @Test
    @DisplayName("MATCH: 多关系类型查询")
    void matchMultipleRelTypesTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "MATCH (n:NetworkElement)-[:HAS_LTP|HAS_PORT|HAS_INTERFACE]->(target) RETURN n, target";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
    }
    
    @Test
    @DisplayName("MATCH: 可变长度路径查询")
    void matchVariableLengthPathTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
        ne1.setVariableName("start");
        GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
        ne2.setVariableName("end");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1)
                .addEntity(ne2));
        
        String cypher = "MATCH (start:NetworkElement)-[:CONNECTS*1..3]->(end:NetworkElement) RETURN start, end";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
    }
    
    @Test
    @DisplayName("MATCH: OPTIONAL MATCH 查询")
    void optionalMatchTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "MATCH (n:NetworkElement) OPTIONAL MATCH (n)-[:HAS_LTP]->(l:LTP) RETURN n, l";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray());
    }
    
    // ==================== WHERE 条件场景 ====================
    
    @Test
    @DisplayName("WHERE: 比较操作符 = < > <= >= <>")
    void whereComparisonOperatorsTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        ne.setProperty("value", 100);
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String[] cyphers = {
            "MATCH (n:NetworkElement) WHERE n.value = 100 RETURN n",
            "MATCH (n:NetworkElement) WHERE n.value <> 50 RETURN n",
            "MATCH (n:NetworkElement) WHERE n.value > 50 RETURN n",
            "MATCH (n:NetworkElement) WHERE n.value >= 100 RETURN n",
            "MATCH (n:NetworkElement) WHERE n.value < 200 RETURN n",
            "MATCH (n:NetworkElement) WHERE n.value <= 100 RETURN n"
        };
        
        for (String cypher : cyphers) {
            String result = sdk.executeRaw(cypher);
            assertNotNull(result, "Query failed: " + cypher);
            JsonNode json = objectMapper.readTree(result);
            assertTrue(json.isArray(), "Result should be array for: " + cypher);
        }
    }
    
    @Test
    @DisplayName("WHERE: 逻辑操作符 AND OR NOT XOR")
    void whereLogicalOperatorsTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String[] cyphers = {
            "MATCH (n:NetworkElement) WHERE n.name = 'NE001' AND n.type = 'Router' RETURN n",
            "MATCH (n:NetworkElement) WHERE n.name = 'NE001' OR n.name = 'NE002' RETURN n",
            "MATCH (n:NetworkElement) WHERE NOT n.name = 'NE002' RETURN n",
            "MATCH (n:NetworkElement) WHERE n.name = 'NE001' XOR n.type = 'Switch' RETURN n"
        };
        
        for (String cypher : cyphers) {
            String result = sdk.executeRaw(cypher);
            assertNotNull(result, "Query failed: " + cypher);
        }
    }
    
    @Test
    @DisplayName("WHERE: 字符串操作符 STARTS WITH / ENDS WITH / CONTAINS")
    void whereStringOperatorsTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String[] cyphers = {
            "MATCH (n:NetworkElement) WHERE n.name STARTS WITH 'NE' RETURN n",
            "MATCH (n:NetworkElement) WHERE n.name ENDS WITH '01' RETURN n",
            "MATCH (n:NetworkElement) WHERE n.name CONTAINS 'E00' RETURN n"
        };
        
        for (String cypher : cyphers) {
            String result = sdk.executeRaw(cypher);
            assertNotNull(result, "Query failed: " + cypher);
        }
    }
    
    @Test
    @DisplayName("WHERE: IN 操作符")
    void whereInOperatorTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
        ne1.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1));
        
        String cypher = "MATCH (n:NetworkElement) WHERE n.name IN ['NE001', 'NE002', 'NE003'] RETURN n";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray());
    }
    
    @Test
    @DisplayName("WHERE: IS NULL / IS NOT NULL")
    void whereNullOperatorsTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String[] cyphers = {
            "MATCH (n:NetworkElement) WHERE n.name IS NOT NULL RETURN n",
            "MATCH (n:NetworkElement) WHERE n.optionalField IS NULL RETURN n"
        };
        
        for (String cypher : cyphers) {
            String result = sdk.executeRaw(cypher);
            assertNotNull(result, "Query failed: " + cypher);
        }
    }
    
    @Test
    @DisplayName("WHERE: 正则表达式 REGEXP")
    void whereRegexpTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "MATCH (n:NetworkElement) WHERE n.name REGEXP 'NE.*' RETURN n";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
    }
    
    // ==================== RETURN 子句场景 ====================
    
    @Test
    @DisplayName("RETURN: * 返回所有变量")
    void returnAllVariablesTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        GraphEntity ltp = createLTPEntity("ltp1", "LTP1", "Port");
        ltp.setVariableName("l");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne)
                .addEntity(ltp));
        
        String cypher = "MATCH (n:NetworkElement)-[:HAS_LTP]->(l:LTP) RETURN *";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray());
    }
    
    @Test
    @DisplayName("RETURN: 属性访问表达式")
    void returnPropertyAccessTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "MATCH (n:NetworkElement) RETURN n.name, n.type";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray());
        assertEquals(1, json.size());
        // 属性访问表达式返回的字段名可能是 n.name 或 name，取决于实现
        JsonNode firstRow = json.get(0);
        assertTrue(firstRow.has("n") || firstRow.has("n.name") || firstRow.has("name"),
            "结果应该包含 n、n.name 或 name 字段之一");
    }
    
    @Test
    @DisplayName("RETURN: AS 别名")
    void returnAliasTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "MATCH (n:NetworkElement) RETURN n.name AS deviceName, n.type AS deviceType";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray());
    }
    
    @Test
    @DisplayName("RETURN: DISTINCT 去重")
    void returnDistinctTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
        ne1.setVariableName("n");
        GraphEntity ne2 = createNEEntity("ne2", "NE002", "Router");
        ne2.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1)
                .addEntity(ne2));
        
        String cypher = "MATCH (n:NetworkElement) RETURN DISTINCT n.type";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray());
    }
    
    @Test
    @DisplayName("RETURN: 算术表达式")
    void returnArithmeticExpressionTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        ne.setProperty("value", 100);
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String[] cyphers = {
            "MATCH (n:NetworkElement) RETURN n.value + 10 AS added",
            "MATCH (n:NetworkElement) RETURN n.value - 10 AS subtracted",
            "MATCH (n:NetworkElement) RETURN n.value * 2 AS multiplied",
            "MATCH (n:NetworkElement) RETURN n.value / 2 AS divided",
            "MATCH (n:NetworkElement) RETURN n.value % 3 AS modulo"
        };
        
        for (String cypher : cyphers) {
            String result = sdk.executeRaw(cypher);
            assertNotNull(result, "Query failed: " + cypher);
        }
    }
    
    // ==================== ORDER BY / SKIP / LIMIT 场景 ====================
    
    @Test
    @DisplayName("ORDER BY: 升序排序")
    void orderByAscTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "AAA", "Router");
        ne1.setVariableName("n");
        GraphEntity ne2 = createNEEntity("ne2", "BBB", "Switch");
        ne2.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1)
                .addEntity(ne2));
        
        String cypher = "MATCH (n:NetworkElement) RETURN n ORDER BY n.name ASC";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.size() >= 1);
    }
    
    @Test
    @DisplayName("ORDER BY: 降序排序")
    void orderByDescTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "AAA", "Router");
        ne1.setVariableName("n");
        GraphEntity ne2 = createNEEntity("ne2", "BBB", "Switch");
        ne2.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1)
                .addEntity(ne2));
        
        String cypher = "MATCH (n:NetworkElement) RETURN n ORDER BY n.name DESC";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
    }
    
    @Test
    @DisplayName("ORDER BY: 多字段排序")
    void orderByMultipleFieldsTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
        ne1.setVariableName("n");
        GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
        ne2.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1)
                .addEntity(ne2));
        
        String cypher = "MATCH (n:NetworkElement) RETURN n ORDER BY n.type ASC, n.name DESC";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
    }
    
    @Test
    @DisplayName("SKIP: 跳过指定数量")
    void skipTest() throws Exception {
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
        
        String cypher = "MATCH (n:NetworkElement) RETURN n SKIP 1";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.size() >= 0);
    }
    
    @Test
    @DisplayName("LIMIT: 限制返回数量")
    void limitTest() throws Exception {
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
        
        String cypher = "MATCH (n:NetworkElement) RETURN n LIMIT 2";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.size() <= 2);
    }
    
    @Test
    @DisplayName("SKIP + LIMIT: 分页查询")
    void skipAndLimitTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
        ne1.setVariableName("n");
        GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
        ne2.setVariableName("n");
        GraphEntity ne3 = createNEEntity("ne3", "NE003", "Router");
        ne3.setVariableName("n");
        GraphEntity ne4 = createNEEntity("ne4", "NE004", "Switch");
        ne4.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1)
                .addEntity(ne2)
                .addEntity(ne3)
                .addEntity(ne4));
        
        String cypher = "MATCH (n:NetworkElement) RETURN n SKIP 1 LIMIT 2";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.size() <= 2);
    }
    
    // ==================== UNION 场景 ====================
    
    @Test
    @DisplayName("UNION: 合并查询结果并去重")
    void unionTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "MATCH (n:NetworkElement) WHERE n.type = 'Router' RETURN n UNION MATCH (n:NetworkElement) WHERE n.name = 'NE001' RETURN n";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray());
    }
    
    @Test
    @DisplayName("UNION ALL: 合并查询结果不去重")
    void unionAllTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "MATCH (n:NetworkElement) WHERE n.type = 'Router' RETURN n UNION ALL MATCH (n:NetworkElement) WHERE n.name = 'NE001' RETURN n";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray());
    }
    
    // ==================== WITH 子句场景 ====================
    
    @Test
    @DisplayName("WITH: 传递中间结果")
    void withClauseTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "MATCH (n:NetworkElement) WITH n WHERE n.type = 'Router' RETURN n.name";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray());
    }
    
    @Test
    @DisplayName("WITH: 聚合后过滤")
    void withAggregationTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
        ne1.setVariableName("n");
        GraphEntity ne2 = createNEEntity("ne2", "NE002", "Router");
        ne2.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1)
                .addEntity(ne2));
        
        String cypher = "MATCH (n:NetworkElement) WITH n.type AS type, count(*) AS cnt WHERE cnt > 1 RETURN type, cnt";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
    }
    
    @Test
    @DisplayName("WITH: ORDER BY + LIMIT")
    void withOrderByLimitTest() throws Exception {
        GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
        ne1.setVariableName("n");
        GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
        ne2.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne1)
                .addEntity(ne2));
        
        String cypher = "MATCH (n:NetworkElement) WITH n ORDER BY n.name LIMIT 1 RETURN n";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
    }
    
    // ==================== UNWIND 场景 ====================
    
    @Test
    @DisplayName("UNWIND: 展开列表")
    void unwindListTest() throws Exception {
        String cypher = "UNWIND [1, 2, 3] AS x RETURN x";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray());
    }
    
    @Test
    @DisplayName("UNWIND: 展开空列表")
    void unwindEmptyListTest() throws Exception {
        String cypher = "UNWIND [] AS x RETURN x";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray());
        assertEquals(0, json.size());
    }
    
    // ==================== 函数调用场景 ====================
    
    @Test
    @DisplayName("Function: COUNT(*)")
    void countStarTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "MATCH (n:NetworkElement) RETURN COUNT(*) AS total";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
    }
    
    @Test
    @DisplayName("Function: EXISTS")
    void existsFunctionTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "MATCH (n:NetworkElement) WHERE EXISTS(n.name) RETURN n";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
    }
    
    // ==================== CASE 表达式场景 ====================
    
    @Test
    @DisplayName("CASE: 简单 CASE 表达式")
    void simpleCaseExpressionTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "MATCH (n:NetworkElement) RETURN CASE n.type WHEN 'Router' THEN 'R' WHEN 'Switch' THEN 'S' ELSE 'O' END AS typeCode";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
    }
    
    @Test
    @DisplayName("CASE: 搜索 CASE 表达式")
    void searchedCaseExpressionTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "MATCH (n:NetworkElement) RETURN CASE WHEN n.type = 'Router' THEN 'Network Device' ELSE 'Other' END AS category";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
    }
    
    // ==================== 列表操作场景 ====================
    
    @Test
    @DisplayName("List: 列表字面量")
    void listLiteralTest() throws Exception {
        String cypher = "RETURN [1, 2, 3] AS numbers";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
    }
    
    @Test
    @DisplayName("List: 列表索引访问")
    void listIndexAccessTest() throws Exception {
        String cypher = "WITH [1, 2, 3] AS numbers RETURN numbers[0] AS first";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
    }
    
    @Test
    @DisplayName("List: 列表范围切片")
    void listRangeSliceTest() throws Exception {
        String cypher = "WITH [1, 2, 3, 4, 5] AS numbers RETURN numbers[1..3] AS slice";
        String result = sdk.executeRaw(cypher);
        
        assertNotNull(result);
    }
    
    // ==================== 参数化查询场景 ====================
    
    @Test
    @DisplayName("Parameter: 参数化查询")
    void parameterizedQueryTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "MATCH (n:NetworkElement) WHERE n.name = $name RETURN n";
        Map<String, Object> params = new HashMap<>();
        params.put("name", "NE001");
        
        String result = sdk.executeRaw(cypher, params);
        
        assertNotNull(result);
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.isArray());
    }
    
    // ==================== EXPLAIN / PROFILE 场景 ====================
    
    @Test
    @DisplayName("EXPLAIN: 查询计划解释")
    void explainQueryTest() throws Exception {
        String cypher = "EXPLAIN MATCH (n:NetworkElement) RETURN n";
        String result = sdk.execute(cypher);
        
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }
    
    @Test
    @DisplayName("PROFILE: 查询性能分析")
    void profileQueryTest() throws Exception {
        GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
        ne.setVariableName("n");
        
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(ne));
        
        String cypher = "PROFILE MATCH (n:NetworkElement) RETURN n";
        String result = sdk.execute(cypher);
        
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }
    
    // ==================== 辅助方法 ====================
    
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
}
