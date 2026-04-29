package com.fangyang.federatedquery.rewriter;

import com.fangyang.federatedquery.ast.*;
import com.fangyang.federatedquery.testutil.GraphQueryMetaFactory;
import com.fangyang.metadata.MetadataQueryService;
import com.fangyang.federatedquery.parser.CypherASTVisitor;
import com.fangyang.federatedquery.parser.CypherParserFacade;
import com.fangyang.federatedquery.plan.ExecutionPlan;
import com.fangyang.federatedquery.plan.PhysicalQuery;
import com.fangyang.federatedquery.plan.ExternalQuery;
import com.fangyang.federatedquery.reliability.WhereConditionPushdown;
import com.fangyang.federatedquery.validation.ScopeValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RewriterTest {
    private MetadataQueryService metadataQueryService;
    private VirtualEdgeDetector detector;
    private WhereConditionPushdown whereConditionPushdown;
    private QueryRewriter rewriter;
    private CypherParserFacade parser;
    
    @BeforeEach
    void setUp() {
        GraphQueryMetaFactory metaFactory = GraphQueryMetaFactory.createWithDruidZenith();
        metadataQueryService = metaFactory.metadataQueryService();
        detector = new VirtualEdgeDetector(metadataQueryService);
        whereConditionPushdown = new WhereConditionPushdown(metadataQueryService);
        PhysicalQueryBuilder physicalQueryBuilder = new PhysicalQueryBuilder();
        MixedPatternRewriter mixedPatternRewriter = new MixedPatternRewriter(metadataQueryService, physicalQueryBuilder);
        rewriter = new QueryRewriter(metadataQueryService, detector, whereConditionPushdown, physicalQueryBuilder, mixedPatternRewriter);
        parser = new CypherParserFacade(new CypherASTVisitor(), new ScopeValidator(metadataQueryService, detector));
    }
    
    @Test
    @DisplayName("Detect virtual edges in pattern")
    void detectVirtualEdges() {
        String cypher = "MATCH (ne:NetworkElement)-[r:NEHasLtps]->(ltp:LTP)-[:LTPHasKPI2]->(target:KPI2) RETURN ne,target";
        Program program = parser.parse(cypher);
        
        assertNotNull(program, "Program不能为空");
        
        MatchClause match = program.getStatement().getQuery()
                .getSingleQueries().get(0).getMatchClauses().get(0);
        
        VirtualEdgeDetector.DetectionResult result = detector.detect(match.getPattern());
        
        assertNotNull(result, "检测结果不能为空");
        assertTrue(result.hasVirtualElements(), "必须检测到虚拟元素");
        assertEquals(1, result.getVirtualEdgeParts().size(), "虚拟边数量必须为1");
        
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
        assertTrue(plan.getGlobalContext().isHasImplicitLimit(), "未显式 LIMIT 时应补默认 limit");
        assertEquals(5000, plan.getGlobalContext().getImplicitLimit(), "纯物理查询默认 limit 应为 5000");
    }
    
    @Test
    @DisplayName("Rewrite query with virtual edge")
    void rewriteWithVirtualEdge() {
        String cypher = "MATCH (ne:NetworkElement)-[:NEHasLtps]->(ltp:LTP)-[:LTPHasKPI2]->(kpi:KPI2) RETURN ne, ltp, kpi";
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
        assertEquals("druid-service", eq.getDataSource(), "DataSource必须是druid-service");
        assertNotNull(eq.getOperator(), "ExternalQuery的Operator不能为空");
        assertEquals("queryKpi2ByParentResId", eq.getOperator(), "Operator必须是queryKpi2ByParentResId");
        assertTrue(plan.getGlobalContext().isHasImplicitLimit(), "含外部查询且未显式 LIMIT 时应补默认 limit");
        assertEquals(1000, plan.getGlobalContext().getImplicitLimit(), "外部查询默认 limit 应为 1000");
    }
    
    @Test
    @DisplayName("Rewrite UNION query")
    void rewriteUnion() {
        String cypher = "MATCH (n:NetworkElement) RETURN n UNION MATCH (m:NetworkElement) RETURN m";
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
    @DisplayName("Rewrite mixed UNION and UNION ALL")
    void rewriteMixedUnionAll() {
        String cypher = "MATCH (n:NetworkElement) RETURN n UNION ALL MATCH (m:NetworkElement) RETURN m UNION MATCH (k:NetworkElement) RETURN k";
        Program program = parser.parse(cypher);
        ExecutionPlan plan = rewriter.rewrite(program);
        assertEquals(1, plan.getUnionParts().size(), "必须有1个UnionPart");
        assertFalse(plan.getUnionParts().get(0).isAll(), "混合UNION/UNION ALL时all应为false");
    }
    
    @Test
    @DisplayName("Write clause fails before rewrite")
    void rejectUpdatingClause() {
        assertThrows(Exception.class, () -> parser.parse("CREATE (n:NetworkElement {name:'NE001'})"));
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
        assertTrue(plan.getGlobalContext().getPendingFilters().isEmpty(), "已下推的纯物理条件不应重复进入 pendingFilters");
    }

    @Test
    @DisplayName("Virtual filter keeps operator semantics")
    void virtualFilterKeepsOperator() {
        String cypher = "MATCH (ne:NetworkElement)-[:NEHasLtps]->(ltp:LTP)-[:LTPHasKPI2]->(kpi:KPI2) WHERE kpi.value > 90 RETURN ne, ltp, kpi";
        Program program = parser.parse(cypher);

        ExecutionPlan plan = rewriter.rewrite(program);

        assertTrue(plan.getExternalQueries().size() >= 1, "应生成至少 1 条外部查询");
        ExternalQuery query = plan.getExternalQueries().stream()
                .filter(current -> !current.getFilterConditions().isEmpty())
                .findFirst()
                .orElseThrow();
        assertEquals(1, query.getFilterConditions().size(), "应保留 1 条带操作符的过滤条件");
        assertEquals("value", query.getFilterConditions().get(0).getKey(), "应保留过滤字段");
        assertEquals(">", query.getFilterConditions().get(0).getOperator(), "应保留比较操作符");
        assertEquals(90L, ((Number) query.getFilterConditions().get(0).getValue()).longValue(), "应保留过滤值");
        assertTrue(plan.getGlobalContext().getPendingFilters().isEmpty(), "可安全下推的虚拟条件不应再进入 pendingFilters");
    }

    @Test
    @DisplayName("Single-source OR WHERE pushes down to physical query")
    void complexWherePushesDownWhenOwnedByPhysicalVariables() {
        String cypher = "MATCH (n:NetworkElement) WHERE n.name = 'NE001' OR n.name = 'NE002' RETURN n";
        Program program = parser.parse(cypher);

        ExecutionPlan plan = rewriter.rewrite(program);

        assertEquals(1, plan.getPhysicalQueries().size(), "应生成物理查询");
        assertTrue(plan.getPhysicalQueries().get(0).getCypher().contains("WHERE"), "同源 OR 应整体下推");
        assertTrue(plan.getPhysicalQueries().get(0).getCypher().contains(" OR "), "应保留 OR 逻辑");
        assertTrue(plan.getGlobalContext().getPendingFilters().isEmpty(), "已整体下推的表达式不应再进入 pendingFilters");
    }

    @Test
    @DisplayName("Single-hop virtual edge is rejected")
    void rejectSingleHopVirtualEdge() {
        assertThrows(Exception.class,
                () -> parser.parse("MATCH (ne:NetworkElement)-[:NEHasKPI]->(kpi:KPI) RETURN ne, kpi"));
    }

    @Test
    @DisplayName("Explicit LIMIT overrides implicit default")
    void explicitLimitOverridesImplicitDefault() {
        Program program = parser.parse("MATCH (n:NetworkElement) RETURN n LIMIT 42");

        ExecutionPlan plan = rewriter.rewrite(program);

        assertNotNull(plan.getGlobalContext().getGlobalLimit(), "显式 LIMIT 应写入全局 limit");
        assertEquals(42, plan.getGlobalContext().getGlobalLimit().getLimit(), "应保留用户显式 LIMIT");
        assertFalse(plan.getGlobalContext().isHasImplicitLimit(), "显式 LIMIT 时不应再追加隐式 limit");
    }

    @Test
    @DisplayName("First-hop virtual edge rewrites as external then dependent physical query")
    void rewriteFirstHopVirtualEdgeAsExternalThenPhysical() {
        GraphQueryMetaFactory factory = GraphQueryMetaFactory.create()
                .registerDataSource("tugraph", com.fangyang.metadata.DataSourceType.TUGRAPH_BOLT)
                .registerDataSource("druid-service", com.fangyang.metadata.DataSourceType.REST_API)
                .registerLabel("NetworkElement", false, "tugraph")
                .registerLabel("LTP", false, "tugraph")
                .registerLabel("KPI2", true, "druid-service")
                .registerVirtualEdge("KPI2FromLTP", "druid-service", "queryKpi2ByParentResId", binding -> {
                    binding.setTargetLabel("KPI2");
                    binding.getIdMapping().put("resId", "parentResId");
                    binding.setFirstHopOnly(true);
                });

        MetadataQueryService localMetadata = factory.metadataQueryService();
        VirtualEdgeDetector localDetector = new VirtualEdgeDetector(localMetadata);
        PhysicalQueryBuilder localBuilder = new PhysicalQueryBuilder();
        QueryRewriter localRewriter = new QueryRewriter(
                localMetadata,
                localDetector,
                new WhereConditionPushdown(localMetadata),
                localBuilder,
                new MixedPatternRewriter(localMetadata, localBuilder));
        CypherParserFacade localParser = new CypherParserFacade(
                new CypherASTVisitor(),
                new ScopeValidator(localMetadata, localDetector));

        Program program = localParser.parse(
                "MATCH (kpi:KPI2)<-[:KPI2FromLTP]-(ltp:LTP)<-[:NEHasLtps]-(ne:NetworkElement) RETURN kpi, ltp, ne");
        ExecutionPlan plan = localRewriter.rewrite(program);

        assertEquals(1, plan.getExternalQueries().size(), "应先生成独立外部查询");
        ExternalQuery externalQuery = plan.getExternalQueries().get(0);
        assertFalse(externalQuery.isDependsOnPhysicalQuery(), "第一跳虚拟边不应再依赖物理查询");
        assertTrue(externalQuery.getOutputVariables().contains("kpi"), "外部查询应产出起始虚拟变量");
        assertFalse(externalQuery.getOutputVariables().contains("ltp"), "第一跳外部查询不应错误产出物理变量");

        assertEquals(1, plan.getPhysicalQueries().size(), "应生成依赖外部结果的物理查询");
        PhysicalQuery physicalQuery = plan.getPhysicalQueries().get(0);
        assertTrue(physicalQuery.isDependsOnExternalQuery(), "物理查询应依赖外部查询结果");
        assertEquals("kpi", physicalQuery.getSourceVariableName(), "依赖源变量应为起始虚拟变量");
        assertEquals("parentResId", physicalQuery.getDependencySourceField(), "依赖源字段应使用外部字段");
        assertEquals("ltp", physicalQuery.getDependencyTargetVariable(), "应约束首个物理节点变量");
        assertEquals("resId", physicalQuery.getDependencyTargetField(), "应约束物理侧映射字段");
        assertTrue(physicalQuery.getCypher().contains("ltp.resId IN $ltp_resId_ids"), "物理查询应注入外部依赖条件");
    }
}
