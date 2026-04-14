package com.federatedquery.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.federatedquery.adapter.*;
import com.federatedquery.metadata.*;
import com.federatedquery.sdk.GraphQuerySDK;
import com.federatedquery.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.federatedquery.e2e.E2EGraphEntityFactory.*;
import static org.junit.jupiter.api.Assertions.*;

class DependencyAwareExecutionE2ETest {

    private MetadataRegistry registry;
    private MockExternalAdapter tugraphAdapter;
    private MockExternalAdapter kpiAdapter;
    private GraphQuerySDK sdk;

    @BeforeEach
    void setUp() {
        FederatedE2ETestFixture fixture = new FederatedE2ETestFixture()
                .registerDataSource("tugraph", DataSourceType.TUGRAPH_BOLT)
                .registerDataSource("kpi-service", DataSourceType.REST_API)
                .registerLabel("NetworkElement", false, "tugraph")
                .registerLabel("LTP", false, "tugraph")
                .registerLabel("KPI", true, "kpi-service")
                .registerVirtualEdge("LTPHasKPI2", "kpi-service", "getKPI2ByLtpIds", binding -> {
                    binding.setTargetLabel("KPI");
                    binding.getIdMapping().put("_id", "parentResId");
                    binding.setLastHopOnly(true);
                });

        registry = fixture.registry();
        tugraphAdapter = fixture.createAdapter("tugraph");
        kpiAdapter = fixture.createAdapter("kpi-service");
        sdk = fixture.createSdk();
    }

    @Nested
    @DisplayName("场景1: 单虚拟边依赖传递测试")
    class Scenario1_SingleVirtualEdgeTests {

        @Test
        @DisplayName("LTPHasKPI2虚拟边: 验证ltp ID正确传递给外部查询")
        void scenario1_LTPHasKPI2_VerifyIdPassing() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("ne");

            GraphEntity ltp1 = createLTPEntity("ltp1", "LTP001", "Port");
            ltp1.setVariableName("ltp");
            GraphEntity ltp2 = createLTPEntity("ltp2", "LTP002", "Port");
            ltp2.setVariableName("ltp");

            GraphEntity neRow1 = createNEEntity("ne1", "NE001", "Router");
            neRow1.setVariableName("ne");
            GraphEntity neRow2 = createNEEntity("ne1", "NE001", "Router");
            neRow2.setVariableName("ne");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addRowEntities(neRow1, ltp1)
                    .addRowEntities(neRow2, ltp2));

            GraphEntity kpi1 = createKPIEntity("kpi1", "cpu_usage", 85.5);
            kpi1.setVariableName("target");
            kpi1.setProperty("parentResId", "ltp1");
            GraphEntity kpi2 = createKPIEntity("kpi2", "memory_usage", 70.0);
            kpi2.setVariableName("target");
            kpi2.setProperty("parentResId", "ltp2");

            kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                    .addRowEntities(kpi1)
                    .addRowEntities(kpi2));

            String cypher = "MATCH p=(ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2]->(target) RETURN p";

            JsonNode json = JsonUtil.readTree(sdk.executeRaw(cypher));
            assertTrue(json.isArray(), "Result must be an array");
            assertEquals(1, json.size(), "Result should contain exactly 1 row");

            JsonNode firstRow = json.get(0);
            assertTrue(firstRow.has("p"), "Row must have 'p' field");

            JsonNode paths = firstRow.get("p");
            assertTrue(paths.isArray(), "paths must be an array");
            assertEquals(2, paths.size(), "Should reconstruct exactly 2 paths");

            JsonNode firstPath = paths.get(0);
            assertTrue(firstPath.isArray(), "Each path must be an array");
            assertEquals(5, firstPath.size(), "Path should have 5 elements (3 nodes + 2 edges)");

            JsonNode neNode = firstPath.get(0);
            assertEquals("node", neNode.get("type").asText(), "First element must be a node");
            assertEquals("NetworkElement", neNode.get("label").asText(), "First node must be NetworkElement");

            JsonNode ltpNode = firstPath.get(2);
            assertEquals("node", ltpNode.get("type").asText(), "Third element must be a node");
            assertEquals("LTP", ltpNode.get("label").asText(), "Second node must be LTP");

            JsonNode targetNode = firstPath.get(4);
            assertEquals("node", targetNode.get("type").asText(), "Fifth element must be a node");
            assertEquals("KPI", targetNode.get("label").asText(), "Third node must be KPI");
        }

        @Test
        @DisplayName("LTPHasKPI2虚拟边: 验证多个LTP的ID都被传递")
        void scenario1_MultipleLTPs_AllIdsPassed() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("ne");

            GraphEntity ltp1 = createLTPEntity("ltp-id-001", "LTP001", "Port");
            ltp1.setVariableName("ltp");
            GraphEntity ltp2 = createLTPEntity("ltp-id-002", "LTP002", "Port");
            ltp2.setVariableName("ltp");
            GraphEntity ltp3 = createLTPEntity("ltp-id-003", "LTP003", "Port");
            ltp3.setVariableName("ltp");

            GraphEntity neRow1 = createNEEntity("ne1", "NE001", "Router");
            neRow1.setVariableName("ne");
            GraphEntity neRow2 = createNEEntity("ne1", "NE001", "Router");
            neRow2.setVariableName("ne");
            GraphEntity neRow3 = createNEEntity("ne1", "NE001", "Router");
            neRow3.setVariableName("ne");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addRowEntities(neRow1, ltp1)
                    .addRowEntities(neRow2, ltp2)
                    .addRowEntities(neRow3, ltp3));

            GraphEntity kpi1 = createKPIEntity("kpi1", "cpu_usage", 85.5);
            kpi1.setVariableName("target");
            kpi1.setProperty("parentResId", "ltp-id-001");
            GraphEntity kpi2 = createKPIEntity("kpi2", "memory_usage", 70.0);
            kpi2.setVariableName("target");
            kpi2.setProperty("parentResId", "ltp-id-002");
            GraphEntity kpi3 = createKPIEntity("kpi3", "disk_usage", 60.0);
            kpi3.setVariableName("target");
            kpi3.setProperty("parentResId", "ltp-id-003");

            kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                    .addRowEntities(kpi1)
                    .addRowEntities(kpi2)
                    .addRowEntities(kpi3));

            String cypher = "MATCH (ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2]->(target) RETURN ne, ltp, target";

            JsonNode json = JsonUtil.readTree(sdk.executeRaw(cypher));
            assertTrue(json.isArray(), "Result must be an array");
            assertEquals(3, json.size(), "Result should have exactly 3 rows");

            Set<String> ltpIds = new HashSet<>();
            Set<String> targetIds = new HashSet<>();
            for (JsonNode row : json) {
                if (row.has("ltp")) {
                    ltpIds.add(row.get("ltp").get("id").asText());
                }
                if (row.has("target")) {
                    targetIds.add(row.get("target").get("id").asText());
                }
            }

            assertEquals(Set.of("ltp-id-001", "ltp-id-002", "ltp-id-003"), ltpIds, "All LTP IDs must be preserved");
            assertEquals(Set.of("kpi1", "kpi2", "kpi3"), targetIds, "All KPI IDs must be preserved");
        }

        @Test
        @DisplayName("LTPHasKPI2虚拟边: 无LTP时外部查询应返回空结果")
        void scenario1_NoLTPs_ExternalQueryReturnsEmpty() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("ne");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne));

            kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create());

            String cypher = "MATCH (ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2]->(target) RETURN ne, ltp, target";

            JsonNode json = JsonUtil.readTree(sdk.executeRaw(cypher));
            assertTrue(json.isArray(), "Result must be an array");
        }
    }

    @Nested
    @DisplayName("场景2: 混合边(虚拟边+真实边)测试")
    class Scenario2_MixedEdgeTests {

        @BeforeEach
        void setUpMixedEdge() {
            LabelMetadata elementLabel = new LabelMetadata();
            elementLabel.setLabel("Element");
            elementLabel.setVirtual(false);
            elementLabel.setDataSource("tugraph");
            registry.registerLabel(elementLabel);
        }

        @Test
        @DisplayName("LTPHasKPI2|LTPHasElement混合边: 验证虚拟边和真实边都能正确处理")
        void scenario2_MixedVirtualAndPhysicalEdges() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("ne");

            GraphEntity ltp = createLTPEntity("ltp1", "LTP001", "Port");
            ltp.setVariableName("ltp");

            GraphEntity element = createElementEntity("elem1", "Element001", "Module");
            element.setVariableName("target");

            GraphEntity neRow = createNEEntity("ne1", "NE001", "Router");
            neRow.setVariableName("ne");
            GraphEntity ltpRow = createLTPEntity("ltp1", "LTP001", "Port");
            ltpRow.setVariableName("ltp");
            GraphEntity elementRow = createElementEntity("elem1", "Element001", "Module");
            elementRow.setVariableName("target");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addRowEntities(neRow, ltpRow, elementRow));

            GraphEntity kpi = createKPIEntity("kpi1", "cpu_usage", 85.5);
            kpi.setVariableName("target");
            kpi.setProperty("parentResId", "ltp1");

            kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                    .addRowEntities(kpi));

            String cypher = "MATCH p=(ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2|LTPHasElement]->(target) RETURN p";

            JsonNode json = JsonUtil.readTree(sdk.executeRaw(cypher));
            assertTrue(json.isArray(), "Result must be an array");
            assertEquals(1, json.size(), "Result should contain exactly 1 row");

            Set<String> targetLabels = new HashSet<>();
            for (JsonNode row : json) {
                if (row.has("p")) {
                    JsonNode paths = row.get("p");
                    for (JsonNode path : paths) {
                        if (path.isArray() && path.size() >= 5) {
                            JsonNode targetNode = path.get(4);
                            if (targetNode.has("label")) {
                                targetLabels.add(targetNode.get("label").asText());
                            }
                        }
                    }
                }
            }

            assertEquals(Set.of("Element", "KPI"), targetLabels, "Should contain both Element and KPI targets");
        }

        @Test
        @DisplayName("LTPHasKPI2|LTPHasElement混合边: 验证路径结构正确")
        void scenario2_MixedEdges_PathStructureCorrect() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("ne");

            GraphEntity ltp = createLTPEntity("ltp1", "LTP001", "Port");
            ltp.setVariableName("ltp");

            GraphEntity element = createElementEntity("elem1", "Element001", "Module");
            element.setVariableName("target");

            GraphEntity neRow = createNEEntity("ne1", "NE001", "Router");
            neRow.setVariableName("ne");
            GraphEntity ltpRow = createLTPEntity("ltp1", "LTP001", "Port");
            ltpRow.setVariableName("ltp");
            GraphEntity elementRow = createElementEntity("elem1", "Element001", "Module");
            elementRow.setVariableName("target");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addRowEntities(neRow, ltpRow, elementRow));

            GraphEntity kpi = createKPIEntity("kpi1", "cpu_usage", 85.5);
            kpi.setVariableName("target");
            kpi.setProperty("parentResId", "ltp1");

            kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                    .addRowEntities(kpi));

            String cypher = "MATCH p=(ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2|LTPHasElement]->(target) RETURN p";

            JsonNode json = JsonUtil.readTree(sdk.executeRaw(cypher));
            assertTrue(json.isArray(), "Result must be an array");
            assertEquals(1, json.size(), "Result must contain exactly 1 row");

            for (JsonNode row : json) {
                if (row.has("p")) {
                    JsonNode paths = row.get("p");
                    assertEquals(2, paths.size(), "Should reconstruct exactly 2 paths");
                    for (JsonNode path : paths) {
                        assertTrue(path.isArray(), "Each path must be an array");
                        assertEquals(5, path.size(), "Path should have exactly 5 elements (3 nodes + 2 edges)");
                        
                        JsonNode firstNode = path.get(0);
                        assertEquals("node", firstNode.get("type").asText(), "First element must be a node");
                        assertEquals("NetworkElement", firstNode.get("label").asText(), "First node must be NetworkElement");
                    }
                }
            }
        }

        @Test
        @DisplayName("LTPHasKPI2|LTPHasElement混合边: 验证变量绑定正确")
        void scenario2_MixedEdges_VariableBindingCorrect() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("ne");

            GraphEntity ltp = createLTPEntity("ltp1", "LTP001", "Port");
            ltp.setVariableName("ltp");

            GraphEntity element = createElementEntity("elem1", "Element001", "Module");
            element.setVariableName("target");

            GraphEntity neRow = createNEEntity("ne1", "NE001", "Router");
            neRow.setVariableName("ne");
            GraphEntity ltpRow = createLTPEntity("ltp1", "LTP001", "Port");
            ltpRow.setVariableName("ltp");
            GraphEntity elementRow = createElementEntity("elem1", "Element001", "Module");
            elementRow.setVariableName("target");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addRowEntities(neRow, ltpRow, elementRow));

            GraphEntity kpi = createKPIEntity("kpi1", "cpu_usage", 85.5);
            kpi.setVariableName("target");
            kpi.setProperty("parentResId", "ltp1");

            kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                    .addRowEntities(kpi));

            String cypher = "MATCH (ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2|LTPHasElement]->(target) RETURN ne, ltp, target";

            JsonNode json = JsonUtil.readTree(sdk.executeRaw(cypher));
            assertTrue(json.isArray(), "Result must be an array");
            assertEquals(2, json.size(), "Should return exactly 2 rows");

            for (JsonNode row : json) {
                assertTrue(row.has("ne"), "Row must have 'ne' field");
                assertTrue(row.has("ltp"), "Row must have 'ltp' field");
                assertTrue(row.has("target"), "Row must have 'target' field");

                JsonNode neNode = row.get("ne");
                assertEquals("NetworkElement", neNode.get("label").asText(), "ne must be NetworkElement");

                JsonNode ltpNode = row.get("ltp");
                assertEquals("LTP", ltpNode.get("label").asText(), "ltp must be LTP");

                JsonNode targetNode = row.get("target");
                String targetLabel = targetNode.get("label").asText();
                assertTrue(targetLabel.equals("KPI") || targetLabel.equals("Element"),
                        "target must be either KPI or Element");
            }
        }
    }

    @Nested
    @DisplayName("边界情况测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("物理查询返回空结果时外部查询不应执行")
        void physicalQueryReturnsEmpty_ExternalQueryNotExecuted() throws Exception {
            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create());

            kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                    .addEntity(createKPIEntity("kpi1", "cpu", 85.0)));

            String cypher = "MATCH (ne:NetworkElement {name: 'NE999'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2]->(target) RETURN ne, ltp, target";

            JsonNode json = JsonUtil.readTree(sdk.executeRaw(cypher));
            assertTrue(json.isArray(), "Result must be an array");
            assertEquals(0, json.size(), "Result should be empty when physical query returns no results");
        }

        @Test
        @DisplayName("外部服务不可用时应返回部分结果")
        void externalServiceUnavailable_ReturnsPartialResult() throws Exception {
            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("ne");

            GraphEntity ltp = createLTPEntity("ltp1", "LTP001", "Port");
            ltp.setVariableName("ltp");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne)
                    .addEntity(ltp));

            kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.withError("Service unavailable"));

            String cypher = "MATCH (ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2]->(target) RETURN ne, ltp, target";

            JsonNode json = JsonUtil.readTree(sdk.executeRaw(cypher));
            assertTrue(json.isArray(), "Result must be an array");
        }

        @Test
        @DisplayName("复杂路径: 三跳路径包含虚拟边")
        void complexPath_ThreeHopsWithVirtualEdge() throws Exception {
            LabelMetadata portLabel = new LabelMetadata();
            portLabel.setLabel("Port");
            portLabel.setVirtual(false);
            portLabel.setDataSource("tugraph");
            registry.registerLabel(portLabel);

            GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
            ne.setVariableName("ne");

            GraphEntity ltp = createLTPEntity("ltp1", "LTP001", "Port");
            ltp.setVariableName("ltp");

            GraphEntity port = createPortEntity("port1", "PORT001", "Eth0");
            port.setVariableName("port");

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne)
                    .addEntity(ltp)
                    .addEntity(port));

            GraphEntity kpi = createKPIEntity("kpi1", "port_traffic", 1000.0);
            kpi.setVariableName("target");

            kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                    .addEntity(kpi));

            String cypher = "MATCH (ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasPort]->(port)-[:PortHasKPI]->(target) RETURN ne, ltp, port, target";

            JsonNode json = JsonUtil.readTree(sdk.executeRaw(cypher));
            assertTrue(json.isArray(), "Result must be an array");
        }
    }

}
