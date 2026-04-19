package com.fangyang.federatedquery.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fangyang.federatedquery.GraphEntity;
import com.fangyang.federatedquery.adapter.MockExternalAdapter;
import com.fangyang.metadata.*;
import com.fangyang.federatedquery.sdk.GraphQuerySDK;
import com.fangyang.federatedquery.testutil.GraphQueryMetaFactory;
import com.fangyang.common.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.fangyang.federatedquery.e2e.E2EGraphEntityFactory.*;
import static org.junit.jupiter.api.Assertions.*;

class MissingCoverageE2ETest {

    private MetadataQueryService metadataQueryService;
    private MockExternalAdapter tugraphAdapter;
    private MockExternalAdapter kpiAdapter;
    private MockExternalAdapter alarmAdapter;
    private GraphQuerySDK sdk;

    @BeforeEach
    void setUp() {
        GraphQueryMetaFactory metaFactory = new GraphQueryMetaFactory()
                .registerDataSource("tugraph", DataSourceType.TUGRAPH_BOLT)
                .registerDataSource("kpi-service", DataSourceType.REST_API)
                .registerDataSource("alarm-service", DataSourceType.REST_API)
                .registerLabel("NetworkElement", false, "tugraph")
                .registerLabel("LTP", false, "tugraph")
                .registerLabel("Person", false, "tugraph")
                .registerLabel("KPI", true, "kpi-service")
                .registerLabel("Alarm", true, "alarm-service")
                .registerVirtualEdge("NEHasKPI", "kpi-service", "getKPIByNeIds", binding -> binding.setLastHopOnly(true))
                .registerVirtualEdge("NEHasAlarms", "alarm-service", "getAlarmsByNeIds", binding -> binding.setLastHopOnly(true));

        metadataQueryService = metaFactory.metadataQueryService();
        tugraphAdapter = metaFactory.createAdapter("tugraph");
        kpiAdapter = metaFactory.createAdapter("kpi-service");
        alarmAdapter = metaFactory.createAdapter("alarm-service");
        sdk = metaFactory.createSdk();
    }

    @Nested
    @DisplayName("High Priority: OPTIONAL MATCH Semantics")
    class OptionalMatchTests {

        @Test
        @DisplayName("OPTIONAL MATCH: 解析并执行成功")
        void optionalMatchParsesAndExecutes() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            kpiAdapter.registerResponse("getKPIByNeIds", MockExternalAdapter.MockResponse.create());

            String cypher = "MATCH (n:NetworkElement) OPTIONAL MATCH (n)-[:NEHasKPI]->(kpi) RETURN n, kpi";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
            assertEquals(1, json.size(), "Result must contain 1 row");

            JsonNode row = json.get(0);
            assertTrue(row.has("n"), "Row must have 'n' field");
            assertEquals("NetworkElement", row.get("n").get("label").asText(), "n.label must be NetworkElement");
        }

        @Test
        @DisplayName("OPTIONAL MATCH: 有匹配时返回正确结果")
        void optionalMatchWithMatchReturnsData() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("n");

            GraphEntity kpi = createKPIEntity("kpi1", "cpu_usage", 85.5);
            kpi.setVariableName("kpi");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            kpiAdapter.registerResponse("getKPIByNeIds", MockExternalAdapter.MockResponse.create()
                    .addEntity(kpi));

            String cypher = "MATCH (n:NetworkElement) OPTIONAL MATCH (n)-[:NEHasKPI]->(kpi) RETURN n, kpi";

            JsonNode json = JsonUtil.readTree(sdk.execute(cypher));
            assertTrue(json.isArray(), "Result must be an array");
            assertEquals(1, json.size(), "Result must contain 1 row");

            JsonNode row = json.get(0);
            assertTrue(row.has("n"), "Row must have 'n' field");
            assertEquals("NetworkElement", row.get("n").get("label").asText(), "n.label must be NetworkElement");
        }

        @Test
        @DisplayName("OPTIONAL MATCH: 多个OPTIONAL MATCH组合解析成功")
        void multipleOptionalMatchesParse() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            kpiAdapter.registerResponse("getKPIByNeIds", MockExternalAdapter.MockResponse.create());
            alarmAdapter.registerResponse("getAlarmsByNeIds", MockExternalAdapter.MockResponse.create());

            String cypher = "MATCH (n:NetworkElement) " +
                    "OPTIONAL MATCH (n)-[:NEHasKPI]->(kpi) " +
                    "OPTIONAL MATCH (n)-[:NEHasAlarms]->(alarm) " +
                    "RETURN n, kpi, alarm";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }
    }

    @Nested
    @DisplayName("High Priority: DISTINCT Deduplication")
    class DistinctTests {

        @Test
        @DisplayName("DISTINCT: 解析并执行成功")
        void distinctParsesAndExecutes() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
            ne1.setVariableName("n");
            GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
            ne2.setVariableName("n");
            GraphEntity ne3 = createNEEntity("ne3", "NE001", "Router");
            ne3.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1).addEntity(ne2).addEntity(ne3));

            String cypher = "MATCH (n:NetworkElement) RETURN DISTINCT n.name AS name";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
            assertTrue(json.size() >= 1, "Result should have at least 1 row");
        }

        @Test
        @DisplayName("DISTINCT: 多字段组合去重解析成功")
        void distinctMultipleFieldsParses() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
            ne1.setVariableName("n");
            GraphEntity ne2 = createNEEntity("ne2", "NE002", "Router");
            ne2.setVariableName("n");
            GraphEntity ne3 = createNEEntity("ne3", "NE001", "Router");
            ne3.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1).addEntity(ne2).addEntity(ne3));

            String cypher = "MATCH (n:NetworkElement) RETURN DISTINCT n.name, n.type";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("DISTINCT: 节点对象去重解析成功")
        void distinctNodeObjectsParses() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
            ne1.setVariableName("n");
            GraphEntity ne2 = createNEEntity("ne1", "NE001", "Router");
            ne2.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1).addEntity(ne2));

            String cypher = "MATCH (n:NetworkElement) RETURN DISTINCT n";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }
    }

    @Nested
    @DisplayName("High Priority: Aggregation Functions")
    class AggregationFunctionTests {

        @Test
        @DisplayName("sum(): 解析并执行成功")
        void sumFunctionParses() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
            ne1.setProperty("value", 10);
            ne1.setVariableName("n");
            GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
            ne2.setProperty("value", 20);
            ne2.setVariableName("n");
            GraphEntity ne3 = createNEEntity("ne3", "NE003", "Router");
            ne3.setProperty("value", 30);
            ne3.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1).addEntity(ne2).addEntity(ne3));

            String cypher = "MATCH (n:NetworkElement) RETURN sum(n.value) AS total";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("avg(): 解析并执行成功")
        void avgFunctionParses() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
            ne1.setProperty("value", 10);
            ne1.setVariableName("n");
            GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
            ne2.setProperty("value", 20);
            ne2.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1).addEntity(ne2));

            String cypher = "MATCH (n:NetworkElement) RETURN avg(n.value) AS average";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("max(): 解析并执行成功")
        void maxFunctionParses() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
            ne1.setProperty("value", 10);
            ne1.setVariableName("n");
            GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
            ne2.setProperty("value", 50);
            ne2.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1).addEntity(ne2));

            String cypher = "MATCH (n:NetworkElement) RETURN max(n.value) AS maximum";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("min(): 解析并执行成功")
        void minFunctionParses() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
            ne1.setProperty("value", 10);
            ne1.setVariableName("n");
            GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
            ne2.setProperty("value", 50);
            ne2.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1).addEntity(ne2));

            String cypher = "MATCH (n:NetworkElement) RETURN min(n.value) AS minimum";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("collect(): 解析并执行成功")
        void collectFunctionParses() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
            ne1.setVariableName("n");
            GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
            ne2.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1).addEntity(ne2));

            String cypher = "MATCH (n:NetworkElement) RETURN collect(n.name) AS names";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("count(*): 解析并执行成功")
        void countStarParses() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
            ne1.setVariableName("n");
            GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
            ne2.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1).addEntity(ne2));

            String cypher = "MATCH (n:NetworkElement) RETURN count(*) AS totalRows";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }
    }

    @Nested
    @DisplayName("High Priority: ORDER BY Multi-field")
    class OrderByMultiFieldTests {

        @Test
        @DisplayName("ORDER BY: 多字段组合排序")
        void orderByMultipleFields() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "Alice", "Router");
            ne1.setVariableName("n");
            GraphEntity ne2 = createNEEntity("ne2", "Bob", "Switch");
            ne2.setVariableName("n");
            GraphEntity ne3 = createNEEntity("ne3", "Alice", "Switch");
            ne3.setVariableName("n");
            GraphEntity ne4 = createNEEntity("ne4", "Bob", "Router");
            ne4.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1).addEntity(ne2).addEntity(ne3).addEntity(ne4));

            String cypher = "MATCH (n:NetworkElement) RETURN n ORDER BY n.name ASC, n.type DESC";

            JsonNode json = JsonUtil.readTree(sdk.execute(cypher));
            assertTrue(json.isArray(), "Result must be an array");
            assertEquals(4, json.size(), "Result should have 4 rows");

            JsonNode first = json.get(0).get("n");
            assertEquals("Alice", first.get("name").asText(), "First row name should be Alice");

            JsonNode second = json.get(1).get("n");
            assertEquals("Alice", second.get("name").asText(), "Second row name should be Alice");
        }

        @Test
        @DisplayName("ORDER BY: 三字段排序")
        void orderByThreeFields() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "A", "X");
            ne1.setProperty("seq", 1);
            ne1.setVariableName("n");
            GraphEntity ne2 = createNEEntity("ne2", "A", "Y");
            ne2.setProperty("seq", 2);
            ne2.setVariableName("n");
            GraphEntity ne3 = createNEEntity("ne3", "B", "X");
            ne3.setProperty("seq", 1);
            ne3.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1).addEntity(ne2).addEntity(ne3));

            String cypher = "MATCH (n:NetworkElement) RETURN n ORDER BY n.name ASC, n.type ASC, n.seq DESC";

            JsonNode json = JsonUtil.readTree(sdk.execute(cypher));
            assertTrue(json.isArray(), "Result must be an array");
            assertEquals(3, json.size(), "Result should have 3 rows");

            assertEquals("A", json.get(0).get("n").get("name").asText(), "First should be A");
        }
    }

    @Nested
    @DisplayName("Medium Priority: Mathematical Operators")
    class MathematicalOperatorTests {

        @Test
        @DisplayName("幂运算 (^): 解析成功")
        void powerOperatorParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("base", 2);
            ne.setProperty("exp", 3);
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN n.base ^ n.exp AS result";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("取模运算 (%): 解析成功")
        void moduloOperatorParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("a", 17);
            ne.setProperty("b", 5);
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN n.a % n.b AS result";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("一元负号: 解析成功")
        void unaryMinusOperatorParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("value", 5);
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN -n.value AS result";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("算术运算组合: 加减乘除")
        void arithmeticExpressionCombination() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("a", 10);
            ne.setProperty("b", 3);
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN (n.a + n.b) * 2 - n.a / n.b AS result";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }
    }

    @Nested
    @DisplayName("Medium Priority: String Operators and Functions")
    class StringOperatorTests {

        @Test
        @DisplayName("字符串拼接 (+): 解析成功")
        void stringConcatenationParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN 'Hello' + ' ' + 'World' AS greeting";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("concat(): 解析成功")
        void concatFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN concat(n.name, '-', n.type) AS combined";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("substring(): 解析成功")
        void substringFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001-Router", "Router");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN substring(n.name, 0, 5) AS prefix";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("length(): 解析成功")
        void lengthFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001-Router", "Router");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN length(n.name) AS nameLen";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }
    }

    @Nested
    @DisplayName("Medium Priority: List Operators and Functions")
    class ListOperatorTests {

        @Test
        @DisplayName("列表拼接 (+): 解析成功")
        void listConcatenationParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN [1, 2, 3] + [4, 5] AS combined";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("列表索引访问: 解析成功")
        void listIndexAccessParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("values", Arrays.asList(10, 20, 30));
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN n.values[1] AS second";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("head(): 解析成功")
        void headFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("values", Arrays.asList(10, 20, 30));
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN head(n.values) AS first";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("last(): 解析成功")
        void lastFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("values", Arrays.asList(10, 20, 30));
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN last(n.values) AS lastElement";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("size(): 解析成功")
        void sizeFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("values", Arrays.asList(10, 20, 30, 40));
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN size(n.values) AS listSize";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("range(): 解析成功")
        void rangeFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN range(0, 5) AS numbers";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }
    }

    @Nested
    @DisplayName("Medium Priority: Boolean Operators")
    class BooleanOperatorTests {

        @Test
        @DisplayName("XOR: 解析成功")
        void xorOperatorParses() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
            ne1.setProperty("flagA", true);
            ne1.setProperty("flagB", false);
            ne1.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1));

            String cypher = "MATCH (n:NetworkElement) WHERE n.flagA XOR n.flagB RETURN n.name AS name";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("NOT: 否定运算组合")
        void notOperatorCombination() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
            ne1.setVariableName("n");
            GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switches");
            ne2.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1).addEntity(ne2));

            String cypher = "MATCH (n:NetworkElement) WHERE NOT n.name = 'NE001' RETURN n.name AS name";

            JsonNode json = JsonUtil.readTree(sdk.execute(cypher));
            assertTrue(json.isArray(), "Result must be an array");
        }
    }

    @Nested
    @DisplayName("Medium Priority: Type Conversion Functions")
    class TypeConversionTests {

        @Test
        @DisplayName("toBoolean(): 解析成功")
        void toBooleanFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("strTrue", "true");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN toBoolean(n.strTrue) AS boolVal";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("toFloat(): 解析成功")
        void toFloatFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("strFloat", "123.456");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN toFloat(n.strFloat) AS floatValue";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("toInteger(): 解析成功")
        void toIntegerFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("strInt", "42");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN toInteger(n.strInt) AS intVal";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("toString(): 解析成功")
        void toStringFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("numVal", 123);
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN toString(n.numVal) AS strVal";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }
    }

    @Nested
    @DisplayName("Medium Priority: Scalar Functions")
    class ScalarFunctionTests {

        @Test
        @DisplayName("id(): 解析成功")
        void idFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("entity-123", "NE001", "Router");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN id(n) AS nodeId, n.name AS name";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("properties(): 解析成功")
        void propertiesFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("vendor", "Huawei");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN properties(n) AS props";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("keys(): 解析成功")
        void keysFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("vendor", "Huawei");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN keys(n) AS propKeys";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("labels(): 解析成功")
        void labelsFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN labels(n) AS nodeLabels";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("coalesce(): 解析成功")
        void coalesceFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("optionalB", "ValueB");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN coalesce(n.optionalA, n.optionalB, 'default') AS result";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }
    }

    @Nested
    @DisplayName("Medium Priority: Mathematical Functions")
    class MathematicalFunctionTests {

        @Test
        @DisplayName("abs(): 解析成功")
        void absFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("value", -42);
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN abs(n.value) AS absolute";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("ceil(): 解析成功")
        void ceilFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("value", 3.2);
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN ceil(n.value) AS ceiling";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("floor(): 解析成功")
        void floorFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("value", 3.8);
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN floor(n.value) AS floorVal";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("round(): 解析成功")
        void roundFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("value", 3.5);
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN round(n.value) AS rounded";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("sign(): 解析成功")
        void signFunctionParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setProperty("value", 42);
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN sign(n.value) AS signVal";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }
    }

    @Nested
    @DisplayName("Query Clause Enhancements")
    class QueryClauseTests {

        @Test
        @DisplayName("RETURN 关系: 返回边对象")
        void returnRelationship() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("a");
            GraphEntity ltp = createLTPEntity("ltp1", "LTP1", "Port");
            ltp.setVariableName("b");
            GraphEntity edge = GraphEntity.edge("edge1", "NEHasLtps", "ne1", "ltp1");
            edge.setVariableName("r");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne).addEntity(ltp).addEntity(edge));

            String cypher = "MATCH (a:NetworkElement)-[r:NEHasLtps]->(b:LTP) RETURN r";

            JsonNode json = JsonUtil.readTree(sdk.execute(cypher));
            assertTrue(json.isArray(), "Result must be an array");
            assertTrue(json.size() >= 1, "Result should have at least 1 row");

            JsonNode row = json.get(0);
            assertTrue(row.has("r"), "Row must have 'r' field");
        }

        @Test
        @DisplayName("WHERE 标签过滤: n:Label 语法解析成功")
        void whereLabelFilterParses() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
            ne1.setVariableName("n");
            GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
            ne2.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1).addEntity(ne2));

            String cypher = "MATCH (n) WHERE n:NetworkElement RETURN n.name AS name";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("WITH 聚合后过滤: HAVING语义解析成功")
        void withAggregationFilterParses() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
            ne1.setVariableName("n");
            GraphEntity ne2 = createNEEntity("ne2", "NE002", "Router");
            ne2.setVariableName("n");
            GraphEntity ne3 = createNEEntity("ne3", "NE003", "Switch");
            ne3.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1).addEntity(ne2).addEntity(ne3));

            String cypher = "MATCH (n:NetworkElement) WITH n.type AS type, count(*) AS cnt WHERE cnt > 1 RETURN type, cnt";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("UNWIND: 解析成功")
        void unwindClauseParses() throws Exception {
            String cypher = "UNWIND [1, 2, 3] AS x RETURN x";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }
    }

    @Nested
    @DisplayName("Edge Cases and Error Handling")
    class EdgeCaseTests {

        @Test
        @DisplayName("空结果集处理")
        void emptyResultSet() throws Exception {
            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create());

            String cypher = "MATCH (n:NetworkElement) RETURN n";

            JsonNode json = JsonUtil.readTree(sdk.execute(cypher));
            assertTrue(json.isArray(), "Result must be an array");
            assertEquals(0, json.size(), "Empty result should have 0 rows");
        }

        @Test
        @DisplayName("NULL属性访问处理")
        void nullPropertyAccess() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) RETURN n.nonExistent AS value";

            JsonNode json = JsonUtil.readTree(sdk.execute(cypher));
            assertTrue(json.isArray(), "Result must be an array");
            assertEquals(1, json.size(), "Result should have 1 row");
        }

        @Test
        @DisplayName("范围查询: 数值范围")
        void rangeQuery() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
            ne1.setProperty("value", 10);
            ne1.setVariableName("n");
            GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
            ne2.setProperty("value", 50);
            ne2.setVariableName("n");
            GraphEntity ne3 = createNEEntity("ne3", "NE003", "Router");
            ne3.setProperty("value", 100);
            ne3.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1).addEntity(ne2).addEntity(ne3));

            String cypher = "MATCH (n:NetworkElement) WHERE n.value >= 20 AND n.value <= 80 RETURN n.name AS name ORDER BY n.value";

            JsonNode json = JsonUtil.readTree(sdk.execute(cypher));
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("复杂WHERE条件: 多条件组合")
        void complexWhereConditions() throws Exception {
            GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
            ne1.setProperty("priority", 1);
            ne1.setVariableName("n");
            GraphEntity ne2 = createNEEntity("ne2", "NE002", "Switch");
            ne2.setProperty("priority", 2);
            ne2.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne1).addEntity(ne2));

            String cypher = "MATCH (n:NetworkElement) WHERE n.name STARTS WITH 'NE' AND n.priority > 0 AND n.type = 'Router' RETURN n";

            JsonNode json = JsonUtil.readTree(sdk.execute(cypher));
            assertTrue(json.isArray(), "Result must be an array");
        }
    }

    @Nested
    @DisplayName("Variable Length Paths")
    class VariableLengthPathTests {

        @Test
        @DisplayName("可变长度关系: *1..3 解析成功")
        void variableLengthPathParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("a");
            GraphEntity ltp = createLTPEntity("ltp1", "LTP1", "Port");
            ltp.setVariableName("b");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne).addEntity(ltp));

            String cypher = "MATCH (a:NetworkElement)-[:NEHasLtps*1..3]->(b) RETURN a, b";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("可变长度关系: * 解析成功")
        void variableLengthPathUnboundedParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("a");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (a:NetworkElement)-[:NEHasLtps*]->(b) RETURN a, b";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
        }
    }

    @Nested
    @DisplayName("REGEXP Matching")
    class RegexpTests {

        @Test
        @DisplayName("REGEXP: 正则匹配解析成功")
        void regexpParses() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("n");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            String cypher = "MATCH (n:NetworkElement) WHERE n.name =~ 'NE.*' RETURN n";

            String result = sdk.execute(cypher);
            assertNotNull(result, "Result must not be null");
            JsonNode json = JsonUtil.readTree(result);
            assertTrue(json.isArray(), "Result must be an array");
        }
    }

}
