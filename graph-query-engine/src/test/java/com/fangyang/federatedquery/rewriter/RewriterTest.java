package com.fangyang.federatedquery.rewriter;

import com.fangyang.federatedquery.ast.*;
import com.fangyang.federatedquery.testutil.GraphQueryMetaFactory;
import com.fangyang.metadata.*;
import com.fangyang.federatedquery.parser.CypherASTVisitor;
import com.fangyang.federatedquery.parser.CypherParserFacade;
import com.fangyang.federatedquery.plan.ExecutionPlan;
import com.fangyang.federatedquery.plan.PhysicalQuery;
import com.fangyang.federatedquery.plan.ExternalQuery;
import com.fangyang.federatedquery.reliability.WhereConditionPushdown;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class RewriterTest {
    @Spy
    private MetadataQueryService metadataQueryService = MetadataFactory.createQueryService();
        MetadataRegistrar metadataRegistrar = MetadataFactory.createRegistrar();
    private VirtualEdgeDetector detector;
    private WhereConditionPushdown whereConditionPushdown;
    private QueryRewriter rewriter;
    @Spy
    private CypherASTVisitor astVisitor = new CypherASTVisitor();
    private CypherParserFacade parser;
    
    @BeforeEach
    void setUp() {
        GraphQueryMetaFactory.createStandard();

        detector = new VirtualEdgeDetector(metadataQueryService);
        whereConditionPushdown = new WhereConditionPushdown(metadataQueryService);
        PhysicalQueryBuilder physicalQueryBuilder = new PhysicalQueryBuilder();
        MixedPatternRewriter mixedPatternRewriter = new MixedPatternRewriter(metadataQueryService, physicalQueryBuilder);
        rewriter = new QueryRewriter(metadataQueryService, detector, whereConditionPushdown, physicalQueryBuilder, mixedPatternRewriter);
        parser = new CypherParserFacade(astVisitor);
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
    @DisplayName("Rewrite mixed UNION and UNION ALL")
    void rewriteMixedUnionAll() {
        String cypher = "MATCH (n) RETURN n UNION ALL MATCH (m) RETURN m UNION MATCH (k) RETURN k";
        Program program = parser.parse(cypher);
        ExecutionPlan plan = rewriter.rewrite(program);
        assertEquals(1, plan.getUnionParts().size(), "必须有1个UnionPart");
        assertFalse(plan.getUnionParts().get(0).isAll(), "混合UNION/UNION ALL时all应为false");
    }
    
    @Test
    @DisplayName("Parse CREATE clause but skip in rewriter - read-only mode")
    void skipUpdatingClause() {
        String cypher = "CREATE (n:NetworkElement {name:'NE001'})";
        Program program = parser.parse(cypher);
        ExecutionPlan plan = rewriter.rewrite(program);
        assertNotNull(plan);
        assertTrue(plan.getPhysicalQueries().isEmpty(), "CREATE语句不应产生物理查询");
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
        String cypher = "MATCH (ne:NetworkElement)-[:NEHasKPI]->(kpi) WHERE kpi.value > 90 RETURN ne, kpi";
        Program program = parser.parse(cypher);

        ExecutionPlan plan = rewriter.rewrite(program);

        assertEquals(1, plan.getExternalQueries().size(), "应生成 1 条外部查询");
        ExternalQuery query = plan.getExternalQueries().get(0);
        assertEquals(1, query.getFilterConditions().size(), "应保留 1 条带操作符的过滤条件");
        assertEquals("value", query.getFilterConditions().get(0).getKey(), "应保留过滤字段");
        assertEquals(">", query.getFilterConditions().get(0).getOperator(), "应保留比较操作符");
        assertEquals(90L, ((Number) query.getFilterConditions().get(0).getValue()).longValue(), "应保留过滤值");
        assertTrue(plan.getGlobalContext().getPendingFilters().isEmpty(), "可安全下推的虚拟条件不应再进入 pendingFilters");
    }

    @Test
    @DisplayName("Complex WHERE stays as post filter")
    void complexWhereBecomesPostFilter() {
        String cypher = "MATCH (n:NetworkElement) WHERE n.name = 'NE001' OR n.name = 'NE002' RETURN n";
        Program program = parser.parse(cypher);

        ExecutionPlan plan = rewriter.rewrite(program);

        assertEquals(1, plan.getPhysicalQueries().size(), "应生成物理查询");
        assertFalse(plan.getPhysicalQueries().get(0).getCypher().contains("WHERE"), "复杂逻辑不应被错误下推");
        assertEquals(1, plan.getGlobalContext().getPendingFilters().size(), "复杂表达式应作为单条后置过滤保留");
        assertNotNull(plan.getGlobalContext().getPendingFilters().get(0).getOriginalExpression(), "应保留原始表达式");
    }
}
