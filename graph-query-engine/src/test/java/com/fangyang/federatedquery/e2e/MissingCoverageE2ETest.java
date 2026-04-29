package com.fangyang.federatedquery.e2e;

import com.fangyang.federatedquery.adapter.MockExternalAdapter;
import com.fangyang.federatedquery.sdk.GraphQuerySDK;
import com.fangyang.federatedquery.testutil.GraphQueryMetaFactory;
import com.fangyang.metadata.DataSourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class MissingCoverageE2ETest {

    private GraphQuerySDK sdk;
    private MockExternalAdapter tugraphAdapter;
    private MockExternalAdapter kpiAdapter;
    private MockExternalAdapter alarmAdapter;

    @BeforeEach
    void setUp() {
        GraphQueryMetaFactory metaFactory = new GraphQueryMetaFactory()
                .registerDataSource("tugraph", DataSourceType.TUGRAPH_BOLT)
                .registerDataSource("kpi-service", DataSourceType.REST_API)
                .registerDataSource("alarm-service", DataSourceType.REST_API)
                .registerLabel("NetworkElement", false, "tugraph")
                .registerLabel("LTP", false, "tugraph")
                .registerLabel("KPI", true, "kpi-service")
                .registerLabel("Alarm", true, "alarm-service")
                .registerVirtualEdge("NEHasKPI", "kpi-service", "getKPIByNeIds", binding -> {
                    binding.setTargetLabel("KPI");
                    binding.getIdMapping().put("_id", "parentResId");
                    binding.setLastHopOnly(true);
                })
                .registerVirtualEdge("NEHasAlarms", "alarm-service", "getAlarmsByNeIds", binding -> {
                    binding.setTargetLabel("Alarm");
                    binding.getIdMapping().put("_id", "parentResId");
                    binding.setLastHopOnly(true);
                });

        tugraphAdapter = metaFactory.createAdapter("tugraph");
        kpiAdapter = metaFactory.createAdapter("kpi-service");
        alarmAdapter = metaFactory.createAdapter("alarm-service");
        sdk = metaFactory.createSdk();
    }

    @Test
    @DisplayName("OPTIONAL MATCH is rejected by scope")
    void rejectOptionalMatch() {
        assertThrows(Exception.class,
                () -> sdk.execute("MATCH (n:NetworkElement) OPTIONAL MATCH (n)-[:NEHasKPI]->(kpi) RETURN n, kpi"));
    }

    @Test
    @DisplayName("UNWIND is rejected by scope")
    void rejectUnwind() {
        assertThrows(Exception.class, () -> sdk.execute("UNWIND [1, 2, 3] AS x RETURN x"));
    }

    @Test
    @DisplayName("Variable length paths are rejected by scope")
    void rejectVariableLengthPath() {
        assertThrows(Exception.class,
                () -> sdk.execute("MATCH (a:NetworkElement)-[:NEHasLtps*1..3]->(b:LTP) RETURN a, b"));
    }

    @Test
    @DisplayName("Unlabeled start node is rejected by scope")
    void rejectUnlabeledStartNode() {
        assertThrows(Exception.class, () -> sdk.execute("MATCH (n) RETURN n"));
    }

    @Test
    @DisplayName("Single-hop virtual edge is rejected by scope")
    void rejectSingleHopVirtualEdge() {
        assertThrows(Exception.class,
                () -> sdk.execute("MATCH (n:NetworkElement)-[:NEHasKPI]->(kpi:KPI) RETURN n, kpi"));
    }

    @Test
    @DisplayName("Pure virtual node pattern is rejected by scope")
    void rejectPureVirtualNodePattern() {
        assertThrows(Exception.class, () -> sdk.execute("MATCH (kpi:KPI) RETURN kpi"));
    }

    @Test
    @DisplayName("Out-of-scope expressions stay rejected")
    void rejectOutOfScopeExpressions() {
        assertThrows(Exception.class,
                () -> sdk.execute("MATCH (n:NetworkElement) WHERE n.name =~ 'NE.*' XOR n.type = 'Router' RETURN n"));
    }

    @Test
    @DisplayName("Out-of-scope aggregation stays rejected")
    void rejectOutOfScopeAggregation() {
        assertThrows(Exception.class, () -> sdk.execute("MATCH (n:NetworkElement) RETURN collect(n.name) AS names"));
    }
}
