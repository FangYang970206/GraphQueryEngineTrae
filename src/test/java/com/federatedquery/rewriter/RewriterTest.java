package com.federatedquery.rewriter;

import com.federatedquery.ast.*;
import com.federatedquery.metadata.*;
import com.federatedquery.parser.CypherParserFacade;
import com.federatedquery.plan.ExecutionPlan;
import com.federatedquery.plan.PhysicalQuery;
import com.federatedquery.plan.ExternalQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RewriterTest {
    private MetadataRegistry registry;
    private VirtualEdgeDetector detector;
    private QueryRewriter rewriter;
    private CypherParserFacade parser;
    
    @BeforeEach
    void setUp() {
        registry = new MetadataRegistryImpl();
        detector = new VirtualEdgeDetector(registry);
        rewriter = new QueryRewriter(registry, detector);
        parser = new CypherParserFacade();
        
        DataSourceMetadata tugraph = new DataSourceMetadata();
        tugraph.setName("tugraph");
        tugraph.setType(DataSourceType.TUGRAH_BOLT);
        tugraph.setEndpoint("bolt://localhost:7687");
        registry.registerDataSource(tugraph);
        
        DataSourceMetadata kpiService = new DataSourceMetadata();
        kpiService.setName("kpi-service");
        kpiService.setType(DataSourceType.REST_API);
        kpiService.setEndpoint("http://kpi-service:8080");
        registry.registerDataSource(kpiService);
        
        VirtualEdgeBinding neHasKpi = new VirtualEdgeBinding();
        neHasKpi.setEdgeType("NEHasKPI");
        neHasKpi.setTargetDataSource("kpi-service");
        neHasKpi.setOperatorName("getKPIByNeIds");
        neHasKpi.setLastHopOnly(true);
        registry.registerVirtualEdge(neHasKpi);
        
        VirtualEdgeBinding neHasAlarms = new VirtualEdgeBinding();
        neHasAlarms.setEdgeType("NEHasAlarms");
        neHasAlarms.setTargetDataSource("alarm-service");
        neHasAlarms.setOperatorName("getAlarmsByNeIds");
        neHasAlarms.setLastHopOnly(true);
        registry.registerVirtualEdge(neHasAlarms);
        
        LabelMetadata neLabel = new LabelMetadata();
        neLabel.setLabel("NetworkElement");
        neLabel.setVirtual(false);
        registry.registerLabel(neLabel);
    }
    
    @Test
    @DisplayName("Detect virtual edges in pattern")
    void detectVirtualEdges() {
        String cypher = "MATCH (ne:NetworkElement)-[r:NEHasLtps|NEHasKPI]->(target) RETURN ne,target";
        Program program = parser.parse(cypher);
        
        assertNotNull(program, "Program不能为空");
        
        MatchClause match = program.getStatement().getQuery()
                .getSingleQueries().get(0).getMatchClauses().get(0);
        
        VirtualEdgeDetector.DetectionResult result = detector.detect(match.getPattern());
        
        assertNotNull(result, "检测结果不能为空");
        assertTrue(result.hasVirtualElements(), "必须检测到虚拟元素");
        assertTrue(result.getVirtualEdgeParts().size() >= 1, "虚拟边数量必须>=1");
        
        for (VirtualEdgeDetector.VirtualEdgePart vep : result.getVirtualEdgeParts()) {
            assertNotNull(vep.getEdgeType(), "虚拟边类型不能为空");
            assertFalse(vep.getEdgeType().isEmpty(), "虚拟边类型不能为空字符串");
        }
    }
    
    @Test
    @DisplayName("Rewrite simple query")
    void rewriteSimpleQuery() {
        String cypher = "MATCH (n:NetworkElement) RETURN n";
        Program program = parser.parse(cypher);
        
        assertNotNull(program, "Program不能为空");
        
        ExecutionPlan plan = rewriter.rewrite(program);
        
        assertNotNull(plan, "ExecutionPlan不能为空");
        assertNotNull(plan.getPlanId(), "PlanId不能为空");
        assertFalse(plan.getPlanId().isEmpty(), "PlanId不能为空字符串");
        assertTrue(plan.getPhysicalQueries().size() >= 1, "PhysicalQueries数量必须>=1");
        assertFalse(plan.hasVirtualElements(), "简单查询不应包含虚拟元素");
        
        PhysicalQuery pq = plan.getPhysicalQueries().get(0);
        assertNotNull(pq.getId(), "PhysicalQuery的Id不能为空");
        assertNotNull(pq.getCypher(), "PhysicalQuery的Cypher不能为空");
        assertFalse(pq.getCypher().isEmpty(), "PhysicalQuery的Cypher不能为空字符串");
        
        String originalCypher = plan.getOriginalCypher();
        assertNotNull(originalCypher, "原始Cypher不能为空");
        assertTrue(originalCypher.contains("MATCH"), "原始Cypher必须包含MATCH");
    }
    
    @Test
    @DisplayName("Rewrite query with virtual edge")
    void rewriteWithVirtualEdge() {
        String cypher = "MATCH (ne:NetworkElement)-[:NEHasKPI]->(kpi) RETURN ne, kpi";
        Program program = parser.parse(cypher);
        
        assertNotNull(program, "Program不能为空");
        
        ExecutionPlan plan = rewriter.rewrite(program);
        
        assertNotNull(plan, "ExecutionPlan不能为空");
        assertNotNull(plan.getPlanId(), "PlanId不能为空");
        assertTrue(plan.hasVirtualElements(), "必须包含虚拟元素");
        assertTrue(plan.getExternalQueries().size() >= 1, "ExternalQueries数量必须>=1");
        
        ExternalQuery eq = plan.getExternalQueries().get(0);
        assertNotNull(eq.getId(), "ExternalQuery的Id不能为空");
        assertNotNull(eq.getDataSource(), "ExternalQuery的DataSource不能为空");
        assertEquals("kpi-service", eq.getDataSource(), "DataSource必须是kpi-service");
        assertNotNull(eq.getOperator(), "ExternalQuery的Operator不能为空");
        assertEquals("getKPIByNeIds", eq.getOperator(), "Operator必须是getKPIByNeIds");
    }
    
    @Test
    @DisplayName("Rewrite UNION query")
    void rewriteUnion() {
        String cypher = "MATCH (n) RETURN n UNION MATCH (m) RETURN m";
        Program program = parser.parse(cypher);
        
        assertNotNull(program, "Program不能为空");
        
        ExecutionPlan plan = rewriter.rewrite(program);
        
        assertNotNull(plan, "ExecutionPlan不能为空");
        assertNotNull(plan.getPlanId(), "PlanId不能为空");
        assertTrue(plan.getUnionParts().size() >= 1, "UnionParts数量必须>=1");
        
        String originalCypher = plan.getOriginalCypher();
        assertNotNull(originalCypher, "原始Cypher不能为空");
        assertTrue(originalCypher.contains("UNION"), "原始Cypher必须包含UNION");
    }
    
    @Test
    @DisplayName("Push down WHERE conditions")
    void pushdownWhere() {
        String cypher = "MATCH (n:NetworkElement) WHERE n.name = 'NE001' RETURN n";
        Program program = parser.parse(cypher);
        
        assertNotNull(program, "Program不能为空");
        
        ExecutionPlan plan = rewriter.rewrite(program);
        
        assertNotNull(plan, "ExecutionPlan不能为空");
        assertNotNull(plan.getGlobalContext(), "GlobalContext不能为空");
        
        String originalCypher = plan.getOriginalCypher();
        assertNotNull(originalCypher, "原始Cypher不能为空");
        assertTrue(originalCypher.contains("WHERE"), "原始Cypher必须包含WHERE");
        assertTrue(originalCypher.contains("NE001"), "原始Cypher必须包含NE001");
    }
}
