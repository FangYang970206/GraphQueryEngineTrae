package com.fangyang.federatedquery.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fangyang.federatedquery.model.GraphEntity;
import com.fangyang.federatedquery.adapter.MockExternalAdapter;
import com.fangyang.federatedquery.sdk.GraphQuerySDK;
import com.fangyang.federatedquery.testutil.GraphQueryMetaFactory;
import com.fangyang.common.JsonUtil;
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
    @DisplayName("WHERE + ORDER BY + LIMIT: deterministic semantics")
    void whereOrderLimitSemantics() throws Exception {
        GraphEntity ne1 = node("ne1", "NetworkElement", "n");
        ne1.setProperty("name", "NE001");
        GraphEntity ne2 = node("ne2", "NetworkElement", "n");
        ne2.setProperty("name", "NE002");
        GraphEntity ne3 = node("ne3", "NetworkElement", "n");
        ne3.setProperty("name", "NE003");
        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create().addEntity(ne1).addEntity(ne2).addEntity(ne3));

        JsonNode json = JsonUtil.readTree(sdk.execute("MATCH (n:NetworkElement) WHERE n.name >= 'NE001' RETURN n ORDER BY n.name DESC LIMIT 1"));
        assertTrue(json.isArray(), "Result must be an array");
        assertEquals(1, json.size(), "Pagination must return exactly one row");
        JsonNode n = json.get(0).get("n");
        assertEquals("NE003", n.get("name").asText(), "Ordering + limit must return NE003");
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
        assertThrows(com.fangyang.federatedquery.exception.SyntaxErrorException.class, () -> {
            sdk.execute("EXPLAIN MATCH (n:NetworkElement) RETURN n");
        }, "EXPLAIN should throw SyntaxErrorException");
    }

    @Test
    @DisplayName("PROFILE: throws exception as not supported")
    void profilePayloadSemantics() throws Exception {
        assertThrows(com.fangyang.federatedquery.exception.SyntaxErrorException.class, () -> {
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
    @DisplayName("Single-hop virtual edge is rejected")
    void mixedDataSourceKpiEdgeSemantics() {
        assertThrows(com.fangyang.federatedquery.exception.GraphQueryException.class,
                () -> sdk.execute("MATCH (ne:NetworkElement)-[:NEHasKPI]->(kpi:KPI) RETURN ne, kpi"));
    }

    @Test
    @DisplayName("UNION with unsupported virtual single-hop is rejected")
    void mixedDataSourceUnionAllSemantics() {
        String q = "MATCH (ne:NetworkElement)-[:NEHasKPI]->(target:KPI) RETURN target UNION ALL MATCH (ne:NetworkElement)-[:NEHasAlarms]->(target:Alarm) RETURN target";
        assertThrows(com.fangyang.federatedquery.exception.GraphQueryException.class, () -> sdk.execute(q));
    }

    @Test
    @DisplayName("Virtual edge filtering on unsupported single-hop is rejected")
    void virtualConditionFilteringSemantics() {
        assertThrows(com.fangyang.federatedquery.exception.GraphQueryException.class,
                () -> sdk.execute("MATCH (ne:NetworkElement)-[:NEHasKPI]->(kpi:KPI) WHERE kpi.value > 90 RETURN ne, kpi"));
    }

    private GraphEntity node(String id, String label, String variableName) {
        GraphEntity entity = GraphEntity.node(id, label);
        entity.setVariableName(variableName);
        return entity;
    }
}
