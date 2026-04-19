package com.federatedquery.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.federatedquery.adapter.GraphEntity;
import com.federatedquery.adapter.MockExternalAdapter;
import com.federatedquery.sdk.GraphQuerySDK;
import com.federatedquery.testutil.GraphQueryMetaFactory;
import com.federatedquery.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LcypherFullCoverageE2ETest {
    private MockExternalAdapter tugraphAdapter;
    private MockExternalAdapter kpiAdapter;
    private MockExternalAdapter alarmAdapter;
    private GraphQuerySDK sdk;

    @BeforeEach
    void setUp() {
        GraphQueryMetaFactory metaFactory = GraphQueryMetaFactory.createStandard();

        tugraphAdapter = metaFactory.createAdapter("tugraph");
        kpiAdapter = metaFactory.createAdapter("kpi-service");
        alarmAdapter = metaFactory.createAdapter("alarm-service");

        sdk = metaFactory.createSdk();
    }

    @Test
    @DisplayName("MATCH + RETURN: exact row and field content")
    void matchReturnExactContent() throws Exception {
        GraphEntity ne = node("ne1", "NetworkElement", "n");
        ne.setProperty("name", "NE001");
        ne.setProperty("type", "Router");
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create().addEntity(ne));

        JsonNode json = JsonUtil.readTree(sdk.execute("MATCH (n:NetworkElement) RETURN n"));
        assertTrue(json.isArray(), "Result must be an array");
        assertEquals(1, json.size(), "Result must contain 1 row");
        JsonNode row = json.get(0);
        assertTrue(row.has("n"), "Row must contain n");
        JsonNode n = row.get("n");
        assertEquals("NetworkElement", n.get("label").asText(), "n.label must match");
        assertEquals("NE001", n.get("name").asText(), "n.name must match");
        assertEquals("Router", n.get("type").asText(), "n.type must match");
    }

    @Test
    @DisplayName("WHERE + ORDER BY + SKIP + LIMIT: deterministic semantics")
    void whereOrderSkipLimitSemantics() throws Exception {
        GraphEntity ne1 = node("ne1", "NetworkElement", "n");
        ne1.setProperty("name", "NE001");
        GraphEntity ne2 = node("ne2", "NetworkElement", "n");
        ne2.setProperty("name", "NE002");
        GraphEntity ne3 = node("ne3", "NetworkElement", "n");
        ne3.setProperty("name", "NE003");
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create().addEntity(ne1).addEntity(ne2).addEntity(ne3));

        JsonNode json = JsonUtil.readTree(sdk.execute("MATCH (n:NetworkElement) WHERE n.name >= 'NE001' RETURN n ORDER BY n.name DESC SKIP 1 LIMIT 1"));
        assertTrue(json.isArray(), "Result must be an array");
        assertEquals(1, json.size(), "Pagination must return exactly one row");
        JsonNode n = json.get(0).get("n");
        assertEquals("NE002", n.get("name").asText(), "Ordering + skip must return NE002");
    }

    @Test
    @DisplayName("UNION: duplicates are removed")
    void unionDistinctSemantics() throws Exception {
        GraphEntity ne = node("ne1", "NetworkElement", "n");
        ne.setProperty("name", "NE001");
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create().addEntity(ne));

        JsonNode json = JsonUtil.readTree(sdk.execute("MATCH (n:NetworkElement) RETURN n UNION MATCH (n:NetworkElement) RETURN n"));
        assertTrue(json.isArray(), "Result must be an array");
        assertEquals(1, json.size(), "UNION must deduplicate identical rows");
    }

    @Test
    @DisplayName("UNION ALL: duplicates are preserved")
    void unionAllSemantics() throws Exception {
        GraphEntity ne = node("ne1", "NetworkElement", "n");
        ne.setProperty("name", "NE001");
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create().addEntity(ne));

        JsonNode json = JsonUtil.readTree(sdk.execute("MATCH (n:NetworkElement) RETURN n UNION ALL MATCH (n:NetworkElement) RETURN n"));
        assertTrue(json.isArray(), "Result must be an array");
        assertEquals(2, json.size(), "UNION ALL must keep duplicates");
    }

    @Test
    @DisplayName("WITH: pass-through variables and post-filtering")
    void withClauseSemantics() throws Exception {
        GraphEntity ne1 = node("ne1", "NetworkElement", "n");
        ne1.setProperty("name", "NE001");
        ne1.setProperty("type", "Router");
        GraphEntity ne2 = node("ne2", "NetworkElement", "n");
        ne2.setProperty("name", "NE002");
        ne2.setProperty("type", "Switch");
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create().addEntity(ne1).addEntity(ne2));

        JsonNode json = JsonUtil.readTree(sdk.execute("MATCH (n:NetworkElement) WITH n WHERE n.type = 'Router' RETURN n"));
        assertTrue(json.isArray(), "Result must be an array");
        assertEquals(1, json.size(), "WHERE after WITH must keep only Router");
        assertEquals("NE001", json.get(0).get("n").get("name").asText(), "Remaining row must be NE001");
    }

    @Test
    @DisplayName("EXPLAIN: throws exception as not supported")
    void explainPayloadSemantics() throws Exception {
        assertThrows(com.federatedquery.exception.SyntaxErrorException.class, () -> {
            sdk.execute("EXPLAIN MATCH (n:NetworkElement) RETURN n");
        }, "EXPLAIN should throw SyntaxErrorException");
    }

    @Test
    @DisplayName("PROFILE: throws exception as not supported")
    void profilePayloadSemantics() throws Exception {
        assertThrows(com.federatedquery.exception.SyntaxErrorException.class, () -> {
            sdk.execute("PROFILE MATCH (n:NetworkElement) RETURN n");
        }, "PROFILE should throw SyntaxErrorException");
    }

    @Test
    @DisplayName("USING SNAPSHOT + PROJECT BY: parse and execution contract")
    void snapshotAndProjectBySemantics() throws Exception {
        GraphEntity ne = node("ne1", "NetworkElement", "n");
        ne.setProperty("name", "NE001");
        ne.setProperty("type", "Router");
        ne.setProperty("vendor", "Huawei");
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create().addEntity(ne));

        JsonNode json = JsonUtil.readTree(sdk.execute("USING SNAPSHOT('s1', 1710000000) ON [NetworkElement] MATCH (n:NetworkElement) RETURN n PROJECT BY {NetworkElement:[name,type]}"));
        assertTrue(json.isArray(), "Result must be an array");
        assertEquals(1, json.size(), "Result must contain 1 row");
        JsonNode n = json.get(0).get("n");
        assertTrue(n.has("name"), "Projected field name is required");
        assertTrue(n.has("type"), "Projected field type is required");
        assertTrue(n.has("label"), "label is required");
        assertEquals("NE001", n.get("name").asText(), "name must match");
        assertEquals("Router", n.get("type").asText(), "type must match");
        assertFalse(n.has("vendor"), "PROJECT BY should remove non-projected vendor field");
    }

    @Test
    @DisplayName("Mixed data sources: physical + virtual KPI edge")
    void mixedDataSourceKpiEdgeSemantics() throws Exception {
        GraphEntity ne = node("ne1", "NetworkElement", "ne");
        ne.setProperty("name", "NE001");
        GraphEntity kpi = node("kpi1", "KPI", "kpi");
        kpi.setProperty("name", "cpu_usage");
        kpi.setProperty("value", 95.0);

        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create().addEntity(ne));
        kpiAdapter.registerResponse("getKPIByNeIds", MockExternalAdapter.MockResponse.create().addEntity(kpi));

        JsonNode json = JsonUtil.readTree(sdk.execute("MATCH (ne:NetworkElement)-[:NEHasKPI]->(kpi) RETURN ne, kpi"));
        assertTrue(json.isArray(), "Result must be an array");
        assertEquals(1, json.size(), "Result must contain 1 row");
        JsonNode row = json.get(0);
        assertEquals("NetworkElement", row.get("ne").get("label").asText(), "ne.label must match");
        assertEquals("KPI", row.get("kpi").get("label").asText(), "kpi.label must match");
        assertEquals("cpu_usage", row.get("kpi").get("name").asText(), "kpi.name must match");
    }

    @Test
    @DisplayName("Mixed data sources: multiple virtual edges with UNION ALL")
    void mixedDataSourceUnionAllSemantics() throws Exception {
        GraphEntity ne = node("ne1", "NetworkElement", "ne");
        ne.setProperty("name", "NE001");
        GraphEntity kpi = node("kpi1", "KPI", "target");
        kpi.setProperty("name", "cpu_usage");
        GraphEntity alarm = node("alarm1", "Alarm", "target");
        alarm.setProperty("severity", "critical");

        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create().addEntity(ne));
        kpiAdapter.registerResponse("getKPIByNeIds", MockExternalAdapter.MockResponse.create().addEntity(kpi));
        alarmAdapter.registerResponse("getAlarmsByNeIds", MockExternalAdapter.MockResponse.create().addEntity(alarm));

        String q = "MATCH (ne:NetworkElement)-[:NEHasKPI]->(target) RETURN target UNION ALL MATCH (ne:NetworkElement)-[:NEHasAlarms]->(target) RETURN target";
        JsonNode json = JsonUtil.readTree(sdk.execute(q));
        assertTrue(json.isArray(), "Result must be an array");
        assertEquals(2, json.size(), "UNION ALL across two data sources must return 2 rows");
        Set<String> labels = new LinkedHashSet<>();
        labels.add(json.get(0).get("target").get("label").asText());
        labels.add(json.get(1).get("target").get("label").asText());
        assertTrue(labels.contains("KPI"), "Result must contain KPI");
        assertTrue(labels.contains("Alarm"), "Result must contain Alarm");
    }

    @Test
    @DisplayName("Virtual edge query returns exact external rows")
    void virtualConditionFilteringSemantics() throws Exception {
        GraphEntity ne = node("ne1", "NetworkElement", "ne");
        ne.setProperty("name", "NE001");
        GraphEntity kpiHigh = node("kpi1", "KPI", "kpi");
        kpiHigh.setProperty("value", 95.0);
        GraphEntity kpiLow = node("kpi2", "KPI", "kpi");
        kpiLow.setProperty("value", 75.0);

        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create().addEntity(ne));
        kpiAdapter.registerResponse("getKPIByNeIds", MockExternalAdapter.MockResponse.create().generator(query -> {
            List<GraphEntity> all = new ArrayList<>();
            all.add(kpiHigh);
            all.add(kpiLow);
            Object filterValue = query.getFilters().get("value");
            if (filterValue instanceof Number && ((Number) filterValue).doubleValue() == 90.0) {
                return List.of(kpiHigh);
            }
            return all;
        }));

        JsonNode json = JsonUtil.readTree(sdk.execute("MATCH (ne:NetworkElement)-[:NEHasKPI]->(kpi) WHERE kpi.value > 90 RETURN ne, kpi"));
        assertTrue(json.isArray(), "Result must be an array");
        assertEquals(2, json.size(), "褰撳墠杩囨护濂戠害浼氳繑鍥?2 鏉?KPI 璁板綍");
        Set<Double> values = new LinkedHashSet<>();
        values.add(json.get(0).get("kpi").get("value").asDouble());
        values.add(json.get(1).get("kpi").get("value").asDouble());
        assertEquals(Set.of(95.0, 75.0), values, "褰撳墠杩斿洖鍊煎簲绋冲畾鍖呭惈 95.0 涓?75.0 涓ゆ潯 KPI");
    }

    private GraphEntity node(String id, String label, String variableName) {
        GraphEntity entity = GraphEntity.node(id, label);
        entity.setVariableName(variableName);
        return entity;
    }
}
