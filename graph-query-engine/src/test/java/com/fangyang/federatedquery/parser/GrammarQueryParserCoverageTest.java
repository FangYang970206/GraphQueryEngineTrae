package com.fangyang.federatedquery.parser;

import com.fangyang.federatedquery.ast.Expression;
import com.fangyang.federatedquery.ast.MatchClause;
import com.fangyang.federatedquery.ast.NodePattern;
import com.fangyang.federatedquery.ast.OrderByClause;
import com.fangyang.federatedquery.ast.Pattern;
import com.fangyang.federatedquery.ast.Program;
import com.fangyang.federatedquery.ast.ProjectBy;
import com.fangyang.federatedquery.ast.ReturnClause;
import com.fangyang.federatedquery.ast.Statement;
import com.fangyang.federatedquery.ast.UnionClause;
import com.fangyang.federatedquery.ast.Variable;
import com.fangyang.federatedquery.exception.SyntaxErrorException;
import com.fangyang.federatedquery.validation.ScopeValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GrammarQueryParserCoverageTest {
    private final CypherParserFacade parser = new CypherParserFacade(new CypherASTVisitor(), new ScopeValidator());

    @Test
    @DisplayName("USING SNAPSHOT + PROJECT BY: exact AST fields")
    void usingSnapshotAndProjectByAst() {
        String cypher = "USING SNAPSHOT('s1', 1710000000) ON [NetworkElement,LTP] MATCH (n:NetworkElement) RETURN n PROJECT BY {NetworkElement:[name,type],LTP:[name]}";
        Program program = parser.parse(cypher);
        Statement statement = program.getStatement();
        Statement.Query query = statement.getQuery();
        Statement.SingleQuery sq = query.getSingleQueries().get(0);

        assertEquals("s1", sq.getUsingSnapshot().getSnapshotName(), "Snapshot name must match");
        assertEquals(1710000000L, sq.getUsingSnapshot().getSnapshotTimeAsUnixTimestamp(), "Snapshot time must match");
        assertEquals(2, sq.getUsingSnapshot().getLabels().size(), "Snapshot labels count must match");
        assertEquals("NetworkElement", sq.getUsingSnapshot().getLabels().get(0), "First snapshot label must match");
        assertEquals("LTP", sq.getUsingSnapshot().getLabels().get(1), "Second snapshot label must match");

        ProjectBy projectBy = sq.getProjectBy();
        assertNotNull(projectBy, "ProjectBy must exist");
        assertEquals(2, projectBy.getProjections().size(), "Projection label count must match");
        assertEquals(2, projectBy.getProjections().get("NetworkElement").size(), "NetworkElement projection field count must match");
        assertEquals("name", projectBy.getProjections().get("NetworkElement").get(0), "First projected field must match");
        assertEquals("type", projectBy.getProjections().get("NetworkElement").get(1), "Second projected field must match");
    }

    @Test
    @DisplayName("MATCH + relationship details: exact pattern structure")
    void matchPatternAst() {
        String cypher = "MATCH (n:NetworkElement)-[r:HAS_LTP|HAS_PORT]->(l:LTP) WHERE n.name = 'NE001' RETURN n,l";
        Program program = parser.parse(cypher);
        MatchClause match = program.getStatement().getQuery().getSingleQueries().get(0).getMatchClauses().get(0);
        Pattern.PatternElement element = match.getPattern().getPatternParts().get(0).getPatternElement();
        Pattern.PatternElementChain chain = element.getChains().get(0);

        assertEquals("n", element.getNodePattern().getVariable(), "Start node variable must match");
        assertEquals("NetworkElement", element.getNodePattern().getLabels().get(0), "Start node label must match");
        assertEquals("r", chain.getRelationshipPattern().getVariable(), "Relationship variable must match");
        assertEquals(2, chain.getRelationshipPattern().getRelationshipTypes().size(), "Relationship type count must match");
        assertEquals("HAS_LTP", chain.getRelationshipPattern().getRelationshipTypes().get(0), "First relationship type must match");
        assertEquals("HAS_PORT", chain.getRelationshipPattern().getRelationshipTypes().get(1), "Second relationship type must match");
        assertEquals("l", chain.getNodePattern().getVariable(), "End node variable must match");
        assertEquals("LTP", chain.getNodePattern().getLabels().get(0), "End node label must match");
        assertNotNull(match.getWhereClause(), "Where clause must exist");
    }

    @Test
    @DisplayName("WITH + ORDER BY + LIMIT: exact AST semantics")
    void withOrderLimitAst() {
        String cypher = "MATCH (n:NetworkElement) WITH n ORDER BY n.name DESC LIMIT 3 RETURN n";
        Program program = parser.parse(cypher);
        Statement.SingleQuery sq = program.getStatement().getQuery().getSingleQueries().get(0);
        assertEquals(1, sq.getPrecedingWithClauses().size(), "WITH clause count must match");

        ReturnClause.ReturnItem withItem = sq.getPrecedingWithClauses().get(0).getReturnItems().get(0);
        Expression withExpr = withItem.getExpression();
        assertEquals(Variable.class, withExpr.getClass(), "WITH return expression must be variable");
        assertEquals("n", ((Variable) withExpr).getName(), "WITH variable must match");
        assertEquals(3, sq.getPrecedingWithClauses().get(0).getLimitClause().getLimitValue(), "WITH LIMIT must match");
        OrderByClause.SortItem sort = sq.getPrecedingWithClauses().get(0).getOrderByClause().getSortItems().get(0);
        assertEquals(OrderByClause.SortDirection.DESC, sort.getDirection(), "WITH ORDER direction must match");
    }

    @Test
    @DisplayName("UNION / UNION ALL: exact union flags")
    void unionFlagsAst() {
        String cypher = "MATCH (n:NetworkElement) RETURN n UNION ALL MATCH (m:NetworkElement) RETURN m UNION MATCH (k:NetworkElement) RETURN k";
        Program program = parser.parse(cypher);
        Statement.Query query = program.getStatement().getQuery();
        UnionClause first = query.getUnions().get(0);
        UnionClause second = query.getUnions().get(1);

        assertEquals(3, query.getSingleQueries().size(), "Single query count must match");
        assertEquals(2, query.getUnions().size(), "Union clause count must match");
        assertEquals(true, first.isAll(), "First union must be UNION ALL");
        assertFalse(second.isAll(), "Second union must be UNION DISTINCT");
    }

    @Test
    @DisplayName("Unsupported clauses are rejected")
    void unsupportedClausesFailFast() {
        assertThrows(SyntaxErrorException.class, () -> parser.parse("OPTIONAL MATCH (n:NetworkElement) RETURN n"));
        assertThrows(SyntaxErrorException.class, () -> parser.parse("UNWIND [1,2,3] AS x RETURN x"));
        assertThrows(SyntaxErrorException.class, () -> parser.parse("CALL db.labels()"));
    }

    @Test
    @DisplayName("Write clauses are rejected")
    void updatingClauseParserContract() {
        assertThrows(SyntaxErrorException.class, () -> parser.parse("CREATE (n:NetworkElement {name:'NE001'})"));
        assertThrows(SyntaxErrorException.class, () -> parser.parse("MERGE (n:NetworkElement {name:'NE002'})"));
        assertThrows(SyntaxErrorException.class, () -> parser.parse("MATCH (n:NetworkElement) DELETE n"));
        assertThrows(SyntaxErrorException.class, () -> parser.parse("MATCH (n:NetworkElement) SET n.name = 'NE003'"));
        assertThrows(SyntaxErrorException.class, () -> parser.parse("MATCH (n:NetworkElement) REMOVE n.name"));
    }

    @Test
    @DisplayName("Syntax error contract: invalid query must throw")
    void syntaxErrorContract() {
        assertThrows(SyntaxErrorException.class, () -> parser.parse("MATCH (n RETURN n"));
    }

    @Test
    @DisplayName("Pattern part variable assignment: exact path variable")
    void patternPartVariableAssignment() {
        Program program = parser.parse("MATCH p=(n:NetworkElement)-[:HAS_LTP]->(l:LTP) RETURN p");
        Pattern.PatternPart part = program.getStatement().getQuery().getSingleQueries().get(0).getMatchClauses().get(0).getPattern().getPatternParts().get(0);
        NodePattern start = part.getPatternElement().getNodePattern();
        assertEquals("p", part.getVariable(), "Pattern part variable must match");
        assertEquals("n", start.getVariable(), "Start node variable must match");
        assertEquals(1, part.getPatternElement().getChains().size(), "Chain count must match");
    }

    @Test
    @DisplayName("Scope restrictions fail in parser validation")
    void scopeRestrictionsFailFast() {
        assertThrows(SyntaxErrorException.class, () -> parser.parse("MATCH (n:NetworkElement) RETURN n SKIP 1"));
        assertThrows(SyntaxErrorException.class, () -> parser.parse("MATCH (n)-[:HAS_LTP]->(l:LTP) RETURN n, l"));
        assertThrows(SyntaxErrorException.class, () -> parser.parse("MATCH (n:NetworkElement)-[:HAS_LTP*1..2]->(l:LTP) RETURN n, l"));
    }
}
