package com.fangyang.federatedquery.parser;

import com.fangyang.federatedquery.ast.*;
import com.fangyang.federatedquery.exception.SyntaxErrorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ParserTest {
    @Spy
    private CypherASTVisitor astVisitor = new CypherASTVisitor();
    @InjectMocks
    private CypherParserFacade parser;
    
    @Test
    @DisplayName("Parse simple MATCH clause")
    void matchClause() {
        String cypher = "MATCH (n) RETURN n";
        Program program = parser.parse(cypher);
        
        assertNotNull(program, "Program不能为空");
        assertNotNull(program.getStatement(), "Statement不能为空");
        assertNotNull(program.getStatement().getQuery(), "Query不能为空");
        
        Statement.SingleQuery sq = program.getStatement().getQuery().getSingleQueries().get(0);
        assertNotNull(sq, "SingleQuery不能为空");
        assertEquals(1, sq.getMatchClauses().size(), "MatchClauses数量必须为1");
        
        MatchClause match = sq.getMatchClauses().get(0);
        assertNotNull(match.getPattern(), "Pattern不能为空");
        
        String generatedCypher = program.toCypher();
        assertNotNull(generatedCypher, "生成的Cypher不能为空");
        assertFalse(generatedCypher.isEmpty(), "生成的Cypher不能为空字符串");
        assertTrue(generatedCypher.contains("MATCH"), "生成的Cypher必须包含MATCH");
        assertTrue(generatedCypher.contains("RETURN"), "生成的Cypher必须包含RETURN");
    }
    
    @Test
    @DisplayName("Parse RETURN clause")
    void returnClause() {
        String cypher = "MATCH (n) RETURN n";
        Program program = parser.parse(cypher);
        
        assertNotNull(program, "Program不能为空");
        
        Statement.SingleQuery sq = program.getStatement().getQuery().getSingleQueries().get(0);
        assertNotNull(sq.getReturnClause(), "ReturnClause不能为空");
        assertEquals(1, sq.getReturnClause().getReturnItems().size(), "ReturnItems数量必须为1");
        
        ReturnClause.ReturnItem item = sq.getReturnClause().getReturnItems().get(0);
        assertNotNull(item.getExpression(), "ReturnItem的Expression不能为空");
    }
    
    @Test
    @DisplayName("Parse pattern with multiple relationships")
    void patternWithMultipleRels() {
        String cypher = "MATCH (ne:NetworkElement)-[r:NEHasLtps|NEHasAlarms]->(target) RETURN ne,target";
        Program program = parser.parse(cypher);
        
        assertNotNull(program, "Program不能为空");
        
        Statement.SingleQuery sq = program.getStatement().getQuery().getSingleQueries().get(0);
        MatchClause match = sq.getMatchClauses().get(0);
        Pattern pattern = match.getPattern();
        
        assertNotNull(pattern, "Pattern不能为空");
        assertEquals(1, pattern.getPatternParts().size(), "PatternParts数量必须为1");
        
        Pattern.PatternPart part = pattern.getPatternParts().get(0);
        Pattern.PatternElement element = part.getPatternElement();
        
        assertNotNull(element.getNodePattern(), "NodePattern不能为空");
        assertEquals("ne", element.getNodePattern().getVariable(), "变量名必须是ne");
        assertTrue(element.getNodePattern().getLabels().contains("NetworkElement"), "必须包含NetworkElement标签");
        assertEquals(1, element.getNodePattern().getLabels().size(), "标签数量必须为1");
        
        assertEquals(1, element.getChains().size(), "Chains数量必须为1");
        
        Pattern.PatternElementChain chain = element.getChains().get(0);
        assertNotNull(chain.getRelationshipPattern(), "RelationshipPattern不能为空");
        assertEquals(2, chain.getRelationshipPattern().getRelationshipTypes().size(), "关系类型数量必须为2");
        assertTrue(chain.getRelationshipPattern().getRelationshipTypes().contains("NEHasLtps"), "必须包含NEHasLtps");
        assertTrue(chain.getRelationshipPattern().getRelationshipTypes().contains("NEHasAlarms"), "必须包含NEHasAlarms");
        
        assertNotNull(chain.getNodePattern(), "NodePattern不能为空");
        assertEquals("target", chain.getNodePattern().getVariable(), "目标变量名必须是target");
    }
    
    @Test
    @DisplayName("Parse UNION query")
    void unionQuery() {
        String cypher = "MATCH (n) RETURN n UNION MATCH (m) RETURN m";
        Program program = parser.parse(cypher);
        
        assertNotNull(program, "Program不能为空");
        
        Statement.Query query = program.getStatement().getQuery();
        assertNotNull(query, "Query不能为空");
        assertEquals(2, query.getSingleQueries().size(), "SingleQueries数量必须为2");
        assertEquals(1, query.getUnions().size(), "Unions数量必须为1");
        
        String generatedCypher = program.toCypher();
        assertNotNull(generatedCypher, "生成的Cypher不能为空");
        assertTrue(generatedCypher.contains("UNION"), "生成的Cypher必须包含UNION");
    }
    
    @Test
    @DisplayName("Parse UNION ALL query")
    void unionAllQuery() {
        String cypher = "MATCH (n) RETURN n UNION ALL MATCH (m) RETURN m";
        Program program = parser.parse(cypher);
        
        assertNotNull(program, "Program不能为空");
        
        Statement.Query query = program.getStatement().getQuery();
        assertNotNull(query, "Query不能为空");
        assertEquals(2, query.getSingleQueries().size(), "SingleQueries数量必须为2");
        assertEquals(1, query.getUnions().size(), "Unions数量必须为1");
        assertTrue(query.getUnions().get(0).isAll(), "UNION ALL的isAll必须为true");
        
        String generatedCypher = program.toCypher();
        assertNotNull(generatedCypher, "生成的Cypher不能为空");
        assertTrue(generatedCypher.contains("UNION"), "生成的Cypher必须包含UNION");
        assertTrue(generatedCypher.contains("ALL"), "生成的Cypher必须包含ALL");
    }
    
    @Test
    @DisplayName("Parse syntax error throws exception")
    void syntaxError() {
        String invalidCypher = "MATCH (n RETURN n";
        
        SyntaxErrorException exception = assertThrows(SyntaxErrorException.class, () -> {
            parser.parse(invalidCypher);
        }, "语法错误必须抛出SyntaxErrorException");
        
        assertNotNull(exception.getMessage(), "异常消息不能为空");
        assertFalse(exception.getMessage().isEmpty(), "异常消息不能为空字符串");
    }
    
    @Test
    @DisplayName("Parse with WHERE clause")
    void whereClause() {
        String cypher = "MATCH (n:Person) WHERE n.name = 'John' RETURN n";
        Program program = parser.parse(cypher);
        
        assertNotNull(program, "Program不能为空");
        
        Statement.SingleQuery sq = program.getStatement().getQuery().getSingleQueries().get(0);
        MatchClause match = sq.getMatchClauses().get(0);
        
        assertNotNull(match.getWhereClause(), "WhereClause不能为空");
        assertNotNull(match.getWhereClause().getExpression(), "WhereClause的Expression不能为空");
        
        String generatedCypher = program.toCypher();
        assertNotNull(generatedCypher, "生成的Cypher不能为空");
        assertTrue(generatedCypher.contains("WHERE"), "生成的Cypher必须包含WHERE");
    }
    
    @Test
    @DisplayName("Parse with ORDER BY and LIMIT")
    void orderByAndLimit() {
        String cypher = "MATCH (n) RETURN n ORDER BY n.name DESC LIMIT 10";
        Program program = parser.parse(cypher);
        
        assertNotNull(program, "Program不能为空");
        
        Statement.SingleQuery sq = program.getStatement().getQuery().getSingleQueries().get(0);
        ReturnClause returnClause = sq.getReturnClause();
        
        assertNotNull(returnClause.getOrderByClause(), "OrderByClause不能为空");
        assertEquals(1, returnClause.getOrderByClause().getSortItems().size(), "SortItems数量必须为1");
        
        OrderByClause.SortItem sortItem = returnClause.getOrderByClause().getSortItems().get(0);
        assertEquals(OrderByClause.SortDirection.DESC, sortItem.getDirection(), "排序方向必须是DESC");
        
        assertNotNull(returnClause.getLimitClause(), "LimitClause不能为空");
        
        String generatedCypher = program.toCypher();
        assertNotNull(generatedCypher, "生成的Cypher不能为空");
        assertTrue(generatedCypher.contains("ORDER BY"), "生成的Cypher必须包含ORDER BY");
        assertTrue(generatedCypher.contains("LIMIT"), "生成的Cypher必须包含LIMIT");
    }
    
    @Test
    @DisplayName("Parse cached query returns same result")
    void cachedParse() {
        String cypher = "MATCH (n) RETURN n";
        
        Program first = parser.parseCached(cypher);
        Program second = parser.parseCached(cypher);
        
        assertNotNull(first, "第一次解析结果不能为空");
        assertNotNull(second, "第二次解析结果不能为空");
        
        assertNotNull(first.getStatement(), "第一次解析的Statement不能为空");
        assertNotNull(second.getStatement(), "第二次解析的Statement不能为空");
        
        assertEquals(first.toCypher(), second.toCypher(), "两次解析结果必须相同");
    }
    
    @Test
    @DisplayName("Parse simple node pattern")
    void simpleNodePattern() {
        String cypher = "MATCH (n:Person) RETURN n";
        Program program = parser.parse(cypher);
        
        assertNotNull(program, "Program不能为空");
        
        Statement.SingleQuery sq = program.getStatement().getQuery().getSingleQueries().get(0);
        MatchClause match = sq.getMatchClauses().get(0);
        
        Pattern.PatternPart part = match.getPattern().getPatternParts().get(0);
        NodePattern node = part.getPatternElement().getNodePattern();
        
        assertNotNull(node, "NodePattern不能为空");
        assertEquals("n", node.getVariable(), "变量名必须是n");
        assertEquals(1, node.getLabels().size(), "标签数量必须为1");
        assertTrue(node.getLabels().contains("Person"), "必须包含Person标签");
    }
}
