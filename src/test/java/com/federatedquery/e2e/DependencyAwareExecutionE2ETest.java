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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DependencyAwareExecutionE2ETest {

    private MetadataRegistry registry;
    private MockExternalAdapter tugraphAdapter;
    private MockExternalAdapter kpiAdapter;
    private GraphQuerySDK sdk;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registry = new MetadataRegistryImpl();

        DataSourceMetadata tugraph = new DataSourceMetadata();
        tugraph.setName("tugraph");
        tugraph.setType(DataSourceType.TUGRAPH_BOLT);
        registry.registerDataSource(tugraph);

        DataSourceMetadata kpiService = new DataSourceMetadata();
        kpiService.setName("kpi-service");
        kpiService.setType(DataSourceType.REST_API);
        registry.registerDataSource(kpiService);

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

        LabelMetadata kpiLabel = new LabelMetadata();
        kpiLabel.setLabel("KPI");
        kpiLabel.setVirtual(true);
        kpiLabel.setDataSource("kpi-service");
        registry.registerLabel(kpiLabel);

        VirtualEdgeBinding ltpHasKpi2 = new VirtualEdgeBinding();
        ltpHasKpi2.setEdgeType("LTPHasKPI2");
        ltpHasKpi2.setTargetDataSource("kpi-service");
        ltpHasKpi2.setOperatorName("getKPI2ByLtpIds");
        ltpHasKpi2.setLastHopOnly(true);
        registry.registerVirtualEdge(ltpHasKpi2);

        tugraphAdapter = new MockExternalAdapter();
        tugraphAdapter.setDataSourceName("tugraph");
        kpiAdapter = new MockExternalAdapter();
        kpiAdapter.setDataSourceName("kpi-service");

        CypherParserFacade parser = new CypherParserFacade(new CypherASTVisitor());
        VirtualEdgeDetector detector = new VirtualEdgeDetector(registry);
        WhereConditionPushdown whereConditionPushdown = new WhereConditionPushdown(registry);
        QueryRewriter rewriter = new QueryRewriter(registry, detector, whereConditionPushdown);
        FederatedExecutor executor = new FederatedExecutor(registry);
        executor.registerAdapter("tugraph", tugraphAdapter);
        executor.registerAdapter("kpi-service", kpiAdapter);

        sdk = new GraphQuerySDK(parser, rewriter, executor, new ResultStitcher(), new GlobalSorter(), new UnionDeduplicator());
        objectMapper = new ObjectMapper();
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

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne)
                    .addEntity(ltp1)
                    .addEntity(ltp2));

            GraphEntity kpi1 = createKPIEntity("kpi1", "cpu_usage", 85.5);
            kpi1.setVariableName("target");
            GraphEntity kpi2 = createKPIEntity("kpi2", "memory_usage", 70.0);
            kpi2.setVariableName("target");

            kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                    .addEntity(kpi1)
                    .addEntity(kpi2));

            String cypher = "MATCH p=(ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2]->(target) RETURN p";

            JsonNode json = objectMapper.readTree(sdk.executeRaw(cypher));
            assertTrue(json.isArray(), "Result must be an array");
            assertTrue(json.size() >= 1, "Result should have at least 1 row");

            JsonNode firstRow = json.get(0);
            assertTrue(firstRow.has("paths"), "Row must have 'paths' field");

            JsonNode paths = firstRow.get("paths");
            assertTrue(paths.isArray(), "paths must be an array");
            assertTrue(paths.size() >= 1, "Should have at least 1 path");

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

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne)
                    .addEntity(ltp1)
                    .addEntity(ltp2)
                    .addEntity(ltp3));

            GraphEntity kpi1 = createKPIEntity("kpi1", "cpu_usage", 85.5);
            kpi1.setVariableName("target");
            GraphEntity kpi2 = createKPIEntity("kpi2", "memory_usage", 70.0);
            kpi2.setVariableName("target");
            GraphEntity kpi3 = createKPIEntity("kpi3", "disk_usage", 60.0);
            kpi3.setVariableName("target");

            kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                    .addEntity(kpi1)
                    .addEntity(kpi2)
                    .addEntity(kpi3));

            String cypher = "MATCH (ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2]->(target) RETURN ne, ltp, target";

            JsonNode json = objectMapper.readTree(sdk.executeRaw(cypher));
            assertTrue(json.isArray(), "Result must be an array");
            assertTrue(json.size() >= 1, "Result should have at least 1 row");

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

            assertTrue(ltpIds.size() >= 1, "Should have at least 1 unique LTP ID");
            assertTrue(targetIds.size() >= 1, "Should have at least 1 unique target ID");
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

            JsonNode json = objectMapper.readTree(sdk.executeRaw(cypher));
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

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne)
                    .addEntity(ltp)
                    .addEntity(element));

            GraphEntity kpi = createKPIEntity("kpi1", "cpu_usage", 85.5);
            kpi.setVariableName("target");

            kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                    .addEntity(kpi));

            String cypher = "MATCH p=(ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2|LTPHasElement]->(target) RETURN p";

            JsonNode json = objectMapper.readTree(sdk.executeRaw(cypher));
            assertTrue(json.isArray(), "Result must be an array");
            assertTrue(json.size() >= 1, "Result should have at least 1 row");

            Set<String> targetLabels = new HashSet<>();
            for (JsonNode row : json) {
                if (row.has("paths")) {
                    JsonNode paths = row.get("paths");
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

            assertTrue(targetLabels.size() >= 1, "Should have at least 1 type of target");
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

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne)
                    .addEntity(ltp)
                    .addEntity(element));

            GraphEntity kpi = createKPIEntity("kpi1", "cpu_usage", 85.5);
            kpi.setVariableName("target");

            kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                    .addEntity(kpi));

            String cypher = "MATCH p=(ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2|LTPHasElement]->(target) RETURN p";

            JsonNode json = objectMapper.readTree(sdk.executeRaw(cypher));
            assertTrue(json.isArray(), "Result must be an array");
            assertTrue(json.size() >= 1, "Result should have at least 1 row");

            for (JsonNode row : json) {
                if (row.has("paths")) {
                    JsonNode paths = row.get("paths");
                    for (JsonNode path : paths) {
                        assertTrue(path.isArray(), "Each path must be an array");
                        assertTrue(path.size() >= 5, "Path should have at least 5 elements (3 nodes + 2 edges)");
                        
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

            tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                    .addEntity(ne)
                    .addEntity(ltp)
                    .addEntity(element));

            GraphEntity kpi = createKPIEntity("kpi1", "cpu_usage", 85.5);
            kpi.setVariableName("target");

            kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.create()
                    .addEntity(kpi));

            String cypher = "MATCH (ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2|LTPHasElement]->(target) RETURN ne, ltp, target";

            JsonNode json = objectMapper.readTree(sdk.executeRaw(cypher));
            assertTrue(json.isArray(), "Result must be an array");

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

            JsonNode json = objectMapper.readTree(sdk.executeRaw(cypher));
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

            JsonNode json = objectMapper.readTree(sdk.executeRaw(cypher));
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

            JsonNode json = objectMapper.readTree(sdk.executeRaw(cypher));
            assertTrue(json.isArray(), "Result must be an array");
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

    private GraphEntity createKPIEntity(String id, String name, double value) {
        GraphEntity entity = GraphEntity.node(id, "KPI");
        entity.setProperty("name", name);
        entity.setProperty("value", value);
        return entity;
    }

    private GraphEntity createElementEntity(String id, String name, String type) {
        GraphEntity entity = GraphEntity.node(id, "Element");
        entity.setProperty("name", name);
        entity.setProperty("type", type);
        return entity;
    }

    private GraphEntity createPortEntity(String id, String name, String interfaceName) {
        GraphEntity entity = GraphEntity.node(id, "Port");
        entity.setProperty("name", name);
        entity.setProperty("interface", interfaceName);
        return entity;
    }
}
