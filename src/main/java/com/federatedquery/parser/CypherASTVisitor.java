package com.federatedquery.parser;

import com.federatedquery.ast.*;
import com.federatedquery.grammar.LcypherBaseVisitor;
import com.federatedquery.grammar.LcypherParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class CypherASTVisitor extends LcypherBaseVisitor<AstNode> {
    
    @Override
    public Program visitOC_Cypher(LcypherParser.OC_CypherContext ctx) {
        Program program = new Program();
        Statement statement = visitOC_Statement(ctx.oC_Statement());
        program.setStatement(statement);
        return program;
    }
    
    @Override
    public Statement visitOC_Statement(LcypherParser.OC_StatementContext ctx) {
        Statement statement = new Statement();
        
        if (ctx.EXPLAIN() != null) {
            statement.setExplain(true);
        }
        if (ctx.PROFILE() != null) {
            statement.setProfile(true);
        }
        
        if (ctx.oC_Query() != null) {
            statement.setQuery(visitOC_Query(ctx.oC_Query()));
        }
        
        return statement;
    }
    
    @Override
    public Statement.Query visitOC_Query(LcypherParser.OC_QueryContext ctx) {
        Statement.Query query = new Statement.Query();
        
        LcypherParser.OC_RegularQueryContext regularQueryCtx = ctx.oC_RegularQuery();
        Statement.SingleQuery firstQuery = visitOC_SingleQuery(regularQueryCtx.oC_SingleQuery());
        query.getSingleQueries().add(firstQuery);
        
        if (ctx.oC_UsingClause() != null) {
            LcypherParser.OC_SnapshotContext snapshotCtx = ctx.oC_UsingClause().oC_Snapshot();
            if (snapshotCtx != null) {
                UsingSnapshot snapshot = visitOC_Snapshot(snapshotCtx);
                firstQuery.setUsingSnapshot(snapshot);
            }
        }
        
        if (regularQueryCtx.oC_Union() != null && !regularQueryCtx.oC_Union().isEmpty()) {
            for (LcypherParser.OC_UnionContext unionCtx : regularQueryCtx.oC_Union()) {
                UnionClause union = new UnionClause();
                union.setAll(unionCtx.ALL() != null);
                query.getUnions().add(union);
                
                Statement.SingleQuery nextQuery = visitOC_SingleQuery(unionCtx.oC_SingleQuery());
                query.getSingleQueries().add(nextQuery);
            }
        }
        
        if (ctx.oC_Project() != null) {
            firstQuery.setProjectBy(visitOC_Project(ctx.oC_Project()));
        }
        
        return query;
    }
    
    @Override
    public Statement.SingleQuery visitOC_RegularQuery(LcypherParser.OC_RegularQueryContext ctx) {
        return visitOC_SingleQuery(ctx.oC_SingleQuery());
    }
    
    @Override
    public Statement.SingleQuery visitOC_SingleQuery(LcypherParser.OC_SingleQueryContext ctx) {
        Statement.SingleQuery singleQuery = new Statement.SingleQuery();
        
        if (ctx.oC_SinglePartQuery() != null) {
            visitOC_SinglePartQuery(ctx.oC_SinglePartQuery(), singleQuery);
        } else if (ctx.oC_MultiPartQuery() != null) {
            visitOC_MultiPartQuery(ctx.oC_MultiPartQuery(), singleQuery);
        }
        
        return singleQuery;
    }
    
    private void visitOC_SinglePartQuery(LcypherParser.OC_SinglePartQueryContext ctx, Statement.SingleQuery singleQuery) {
        if (!ctx.oC_UpdatingClause().isEmpty()) {
            singleQuery.setHasUpdatingClause(true);
        }
        
        for (LcypherParser.OC_ReadingClauseContext readingCtx : ctx.oC_ReadingClause()) {
            if (readingCtx.oC_Match() != null) {
                MatchClause match = visitOC_Match(readingCtx.oC_Match());
                singleQuery.getMatchClauses().add(match);
            }
            if (readingCtx.oC_Unwind() != null) {
                UnwindClause unwind = visitOC_Unwind(readingCtx.oC_Unwind());
                singleQuery.addUnwindClause(unwind);
            }
        }
        
        if (ctx.oC_Return() != null) {
            singleQuery.setReturnClause(visitOC_Return(ctx.oC_Return()));
        }
    }
    
    private void visitOC_MultiPartQuery(LcypherParser.OC_MultiPartQueryContext ctx, Statement.SingleQuery singleQuery) {
        if (!ctx.oC_UpdatingClause().isEmpty()) {
            singleQuery.setHasUpdatingClause(true);
        }
        
        List<LcypherParser.OC_ReadingClauseContext> allReadingClauses = new ArrayList<>();
        List<LcypherParser.OC_WithContext> withClauses = new ArrayList<>();
        List<LcypherParser.OC_WhereContext> withWhereClauses = new ArrayList<>();
        
        for (LcypherParser.OC_ReadingClauseContext readingCtx : ctx.oC_ReadingClause()) {
            allReadingClauses.add(readingCtx);
        }
        
        for (LcypherParser.OC_WithContext withCtx : ctx.oC_With()) {
            withClauses.add(withCtx);
            if (withCtx.oC_Where() != null) {
                withWhereClauses.add(withCtx.oC_Where());
            } else {
                withWhereClauses.add(null);
            }
        }
        
        int readingIdx = 0;
        for (int i = 0; i < withClauses.size(); i++) {
            List<MatchClause> partMatches = new ArrayList<>();
            
            while (readingIdx < allReadingClauses.size()) {
                LcypherParser.OC_ReadingClauseContext readingCtx = allReadingClauses.get(readingIdx);
                if (readingCtx.oC_Match() != null) {
                    partMatches.add(visitOC_Match(readingCtx.oC_Match()));
                    readingIdx++;
                } else {
                    break;
                }
            }
            
            singleQuery.addPrecedingMatchClauses(partMatches);
            
            WithClause withClause = visitOC_With(withClauses.get(i));
            singleQuery.addPrecedingWithClause(withClause);
            
            if (i < withWhereClauses.size() && withWhereClauses.get(i) != null) {
                singleQuery.addPrecedingWhereClause(visitOC_Where(withWhereClauses.get(i)));
            } else {
                singleQuery.addPrecedingWhereClause(null);
            }
        }
        
        if (ctx.oC_SinglePartQuery() != null) {
            visitOC_SinglePartQuery(ctx.oC_SinglePartQuery(), singleQuery);
        }
    }
    
    @Override
    public WithClause visitOC_With(LcypherParser.OC_WithContext ctx) {
        WithClause withClause = new WithClause();
        
        withClause.setDistinct(ctx.DISTINCT() != null);
        
        LcypherParser.OC_ReturnBodyContext returnBodyCtx = ctx.oC_ReturnBody();
        if (returnBodyCtx != null) {
            WithClauseReturnItems returnItems = visitOC_ReturnItemsForWith(returnBodyCtx.oC_ReturnItems());
            withClause.setReturnItems(returnItems.getItems());
            
            if (returnBodyCtx.oC_Order() != null) {
                withClause.setOrderByClause(visitOC_Order(returnBodyCtx.oC_Order()));
            }
            
            if (returnBodyCtx.oC_Skip() != null) {
                withClause.setSkipClause(visitOC_Skip(returnBodyCtx.oC_Skip()));
            }
            
            if (returnBodyCtx.oC_Limit() != null) {
                withClause.setLimitClause(visitOC_Limit(returnBodyCtx.oC_Limit()));
            }
        }
        
        if (ctx.oC_Where() != null) {
            withClause.setWhereClause(visitOC_Where(ctx.oC_Where()));
        }
        
        return withClause;
    }
    
    private WithClauseReturnItems visitOC_ReturnItemsForWith(LcypherParser.OC_ReturnItemsContext ctx) {
        WithClauseReturnItems result = new WithClauseReturnItems();
        
        for (LcypherParser.OC_ReturnItemContext itemCtx : ctx.oC_ReturnItem()) {
            ReturnClause.ReturnItem item = new ReturnClause.ReturnItem();
            
            item.setExpression(visitOC_Expression(itemCtx.oC_Expression()));
            
            if (itemCtx.AS() != null && itemCtx.oC_Variable() != null) {
                item.setAlias(itemCtx.oC_Variable().getText());
            }
            
            result.addItem(item);
        }
        
        return result;
    }
    
    private static class WithClauseReturnItems {
        private List<ReturnClause.ReturnItem> items = new ArrayList<>();
        
        public List<ReturnClause.ReturnItem> getItems() {
            return items;
        }
        
        public void addItem(ReturnClause.ReturnItem item) {
            this.items.add(item);
        }
    }
    
    @Override
    public MatchClause visitOC_Match(LcypherParser.OC_MatchContext ctx) {
        MatchClause match = new MatchClause();
        match.setOptional(ctx.OPTIONAL_() != null);
        match.setPattern(visitOC_Pattern(ctx.oC_Pattern()));
        
        if (ctx.oC_Where() != null) {
            match.setWhereClause(visitOC_Where(ctx.oC_Where()));
        }
        
        return match;
    }
    
    public UnwindClause visitOC_Unwind(LcypherParser.OC_UnwindContext ctx) {
        UnwindClause unwind = new UnwindClause();
        unwind.setExpression(visitOC_Expression(ctx.oC_Expression()));
        unwind.setVariable(ctx.oC_Variable().getText());
        return unwind;
    }
    
    @Override
    public Pattern visitOC_Pattern(LcypherParser.OC_PatternContext ctx) {
        Pattern pattern = new Pattern();
        
        for (LcypherParser.OC_PatternPartContext partCtx : ctx.oC_PatternPart()) {
            Pattern.PatternPart part = visitOC_PatternPart(partCtx);
            pattern.addPatternPart(part);
        }
        
        return pattern;
    }
    
    @Override
    public Pattern.PatternPart visitOC_PatternPart(LcypherParser.OC_PatternPartContext ctx) {
        Pattern.PatternPart part = new Pattern.PatternPart();
        
        if (ctx.oC_Variable() != null) {
            part.setVariable(ctx.oC_Variable().getText());
        }
        
        part.setPatternElement(visitOC_AnonymousPatternPart(ctx.oC_AnonymousPatternPart()));
        
        return part;
    }
    
    @Override
    public Pattern.PatternElement visitOC_AnonymousPatternPart(LcypherParser.OC_AnonymousPatternPartContext ctx) {
        return visitOC_PatternElement(ctx.oC_PatternElement());
    }
    
    @Override
    public Pattern.PatternElement visitOC_PatternElement(LcypherParser.OC_PatternElementContext ctx) {
        Pattern.PatternElement element = new Pattern.PatternElement();
        
        if (ctx.oC_NodePattern() != null) {
            element.setNodePattern(visitOC_NodePattern(ctx.oC_NodePattern()));
        }
        
        for (LcypherParser.OC_PatternElementChainContext chainCtx : ctx.oC_PatternElementChain()) {
            Pattern.PatternElementChain chain = visitOC_PatternElementChain(chainCtx);
            element.addChain(chain);
        }
        
        return element;
    }
    
    @Override
    public NodePattern visitOC_NodePattern(LcypherParser.OC_NodePatternContext ctx) {
        NodePattern node = new NodePattern();
        
        if (ctx.oC_Variable() != null) {
            node.setVariable(ctx.oC_Variable().getText());
        }
        
        if (ctx.oC_NodeLabels() != null) {
            for (LcypherParser.OC_NodeLabelContext labelCtx : ctx.oC_NodeLabels().oC_NodeLabel()) {
                node.addLabel(labelCtx.oC_LabelName().getText());
            }
        }
        
        if (ctx.oC_Properties() != null) {
            if (ctx.oC_Properties().oC_MapLiteral() != null) {
                Map<String, Object> props = visitMapLiteral(ctx.oC_Properties().oC_MapLiteral());
                node.setProperties(props);
            }
        }
        
        return node;
    }
    
    @Override
    public Pattern.PatternElementChain visitOC_PatternElementChain(LcypherParser.OC_PatternElementChainContext ctx) {
        Pattern.PatternElementChain chain = new Pattern.PatternElementChain();
        chain.setRelationshipPattern(visitOC_RelationshipPattern(ctx.oC_RelationshipPattern()));
        chain.setNodePattern(visitOC_NodePattern(ctx.oC_NodePattern()));
        return chain;
    }
    
    @Override
    public RelationshipPattern visitOC_RelationshipPattern(LcypherParser.OC_RelationshipPatternContext ctx) {
        RelationshipPattern rel = new RelationshipPattern();
        
        boolean leftArrow = ctx.oC_LeftArrowHead() != null;
        boolean rightArrow = ctx.oC_RightArrowHead() != null;
        
        if (leftArrow && !rightArrow) {
            rel.setDirection(RelationshipPattern.Direction.LEFT);
        } else if (!leftArrow && rightArrow) {
            rel.setDirection(RelationshipPattern.Direction.RIGHT);
        } else {
            rel.setDirection(RelationshipPattern.Direction.BOTH);
        }
        
        if (ctx.oC_RelationshipDetail() != null) {
            LcypherParser.OC_RelationshipDetailContext detailCtx = ctx.oC_RelationshipDetail();
            
            if (detailCtx.oC_Variable() != null) {
                rel.setVariable(detailCtx.oC_Variable().getText());
            }
            
            if (detailCtx.oC_RelationshipTypes() != null) {
                for (LcypherParser.OC_RelTypeNameContext typeCtx : detailCtx.oC_RelationshipTypes().oC_RelTypeName()) {
                    rel.addRelationshipType(typeCtx.getText());
                }
            }
            
            if (detailCtx.oC_Properties() != null && detailCtx.oC_Properties().oC_MapLiteral() != null) {
                Map<String, Object> props = visitMapLiteral(detailCtx.oC_Properties().oC_MapLiteral());
                rel.setProperties(props);
            }
        }
        
        return rel;
    }
    
    private Map<String, Object> visitMapLiteral(LcypherParser.OC_MapLiteralContext ctx) {
        Map<String, Object> map = new HashMap<>();
        
        for (int i = 0; i < ctx.oC_PropertyKeyName().size(); i++) {
            String key = ctx.oC_PropertyKeyName(i).getText();
            Object value = visitExpressionValue(ctx.oC_Expression(i));
            map.put(key, value);
        }
        
        return map;
    }
    
    private Object visitExpressionValue(LcypherParser.OC_ExpressionContext ctx) {
        Expression expr = visitOC_Expression(ctx);
        if (expr instanceof Literal) {
            return ((Literal) expr).getValue();
        }
        return expr;
    }
    
    @Override
    public ReturnClause visitOC_Return(LcypherParser.OC_ReturnContext ctx) {
        ReturnClause returnClause = new ReturnClause();
        returnClause.setDistinct(ctx.DISTINCT() != null);
        
        LcypherParser.OC_ReturnBodyContext bodyCtx = ctx.oC_ReturnBody();
        
        if (bodyCtx.oC_ReturnItems() != null) {
            LcypherParser.OC_ReturnItemsContext itemsCtx = bodyCtx.oC_ReturnItems();
            
            for (LcypherParser.OC_ReturnItemContext itemCtx : itemsCtx.oC_ReturnItem()) {
                ReturnClause.ReturnItem item = new ReturnClause.ReturnItem();
                item.setExpression(visitOC_Expression(itemCtx.oC_Expression()));
                
                if (itemCtx.AS() != null && itemCtx.oC_Variable() != null) {
                    item.setAlias(itemCtx.oC_Variable().getText());
                }
                
                returnClause.getReturnItems().add(item);
            }
        }
        
        if (bodyCtx.oC_Order() != null) {
            returnClause.setOrderByClause(visitOC_Order(bodyCtx.oC_Order()));
        }
        
        if (bodyCtx.oC_Skip() != null) {
            returnClause.setSkipClause(visitOC_Skip(bodyCtx.oC_Skip()));
        }
        
        if (bodyCtx.oC_Limit() != null) {
            returnClause.setLimitClause(visitOC_Limit(bodyCtx.oC_Limit()));
        }
        
        return returnClause;
    }
    
    @Override
    public OrderByClause visitOC_Order(LcypherParser.OC_OrderContext ctx) {
        OrderByClause orderBy = new OrderByClause();
        
        for (LcypherParser.OC_SortItemContext sortCtx : ctx.oC_SortItem()) {
            OrderByClause.SortItem sortItem = new OrderByClause.SortItem();
            sortItem.setExpression(visitOC_Expression(sortCtx.oC_Expression()));
            
            if (sortCtx.DESCENDING() != null || sortCtx.DESC() != null) {
                sortItem.setDirection(OrderByClause.SortDirection.DESC);
            }
            
            orderBy.getSortItems().add(sortItem);
        }
        
        return orderBy;
    }
    
    @Override
    public SkipClause visitOC_Skip(LcypherParser.OC_SkipContext ctx) {
        SkipClause skip = new SkipClause();
        skip.setSkip(visitOC_Expression(ctx.oC_Expression()));
        return skip;
    }
    
    @Override
    public LimitClause visitOC_Limit(LcypherParser.OC_LimitContext ctx) {
        LimitClause limit = new LimitClause();
        limit.setLimit(visitOC_Expression(ctx.oC_Expression()));
        return limit;
    }
    
    @Override
    public WhereClause visitOC_Where(LcypherParser.OC_WhereContext ctx) {
        WhereClause where = new WhereClause();
        where.setExpression(visitOC_Expression(ctx.oC_Expression()));
        return where;
    }
    
    @Override
    public Expression visitOC_Expression(LcypherParser.OC_ExpressionContext ctx) {
        return visitOC_OrExpression(ctx.oC_OrExpression());
    }
    
    @Override
    public Expression visitOC_OrExpression(LcypherParser.OC_OrExpressionContext ctx) {
        List<LcypherParser.OC_XorExpressionContext> xorCtxs = ctx.oC_XorExpression();
        
        if (xorCtxs.size() == 1) {
            return visitOC_XorExpression(xorCtxs.get(0));
        }
        
        LogicalExpression or = new LogicalExpression();
        or.setOperator("OR");
        
        for (LcypherParser.OC_XorExpressionContext xorCtx : xorCtxs) {
            or.addOperand(visitOC_XorExpression(xorCtx));
        }
        
        return or;
    }
    
    @Override
    public Expression visitOC_XorExpression(LcypherParser.OC_XorExpressionContext ctx) {
        List<LcypherParser.OC_AndExpressionContext> andCtxs = ctx.oC_AndExpression();
        
        if (andCtxs.size() == 1) {
            return visitOC_AndExpression(andCtxs.get(0));
        }
        
        LogicalExpression xor = new LogicalExpression();
        xor.setOperator("XOR");
        
        for (LcypherParser.OC_AndExpressionContext andCtx : andCtxs) {
            xor.addOperand(visitOC_AndExpression(andCtx));
        }
        
        return xor;
    }
    
    @Override
    public Expression visitOC_AndExpression(LcypherParser.OC_AndExpressionContext ctx) {
        List<LcypherParser.OC_NotExpressionContext> notCtxs = ctx.oC_NotExpression();
        
        if (notCtxs.size() == 1) {
            return visitOC_NotExpression(notCtxs.get(0));
        }
        
        LogicalExpression and = new LogicalExpression();
        and.setOperator("AND");
        
        for (LcypherParser.OC_NotExpressionContext notCtx : notCtxs) {
            and.addOperand(visitOC_NotExpression(notCtx));
        }
        
        return and;
    }
    
    @Override
    public Expression visitOC_NotExpression(LcypherParser.OC_NotExpressionContext ctx) {
        Expression expr = visitOC_ComparisonExpression(ctx.oC_ComparisonExpression());
        
        if (!ctx.NOT().isEmpty()) {
            LogicalExpression not = new LogicalExpression();
            not.setOperator("NOT");
            not.addOperand(expr);
            return not;
        }
        
        return expr;
    }
    
    @Override
    public Expression visitOC_ComparisonExpression(LcypherParser.OC_ComparisonExpressionContext ctx) {
        Expression left = visitOC_AddOrSubtractExpression(ctx.oC_AddOrSubtractExpression());
        
        if (!ctx.oC_PartialComparisonExpression().isEmpty()) {
            LcypherParser.OC_PartialComparisonExpressionContext partialCtx = ctx.oC_PartialComparisonExpression(0);
            Comparison comparison = new Comparison();
            comparison.setLeft(left);
            comparison.setOperator(getComparisonOperator(partialCtx));
            comparison.setRight(visitOC_AddOrSubtractExpression(partialCtx.oC_AddOrSubtractExpression()));
            return comparison;
        }
        
        return left;
    }
    
    private String getComparisonOperator(LcypherParser.OC_PartialComparisonExpressionContext ctx) {
        String text = ctx.getText();
        if (text.contains("=") && !text.contains("<") && !text.contains(">")) return "=";
        if (text.contains("<>") || text.contains("!=")) return "<>";
        if (text.contains("<=")) return "<=";
        if (text.contains(">=")) return ">=";
        if (text.contains("<")) return "<";
        if (text.contains(">")) return ">";
        return "=";
    }
    
    @Override
    public Expression visitOC_AddOrSubtractExpression(LcypherParser.OC_AddOrSubtractExpressionContext ctx) {
        List<LcypherParser.OC_MultiplyDivideModuloExpressionContext> multCtxs = ctx.oC_MultiplyDivideModuloExpression();
        
        if (multCtxs.size() == 1) {
            return visitOC_MultiplyDivideModuloExpression(multCtxs.get(0));
        }
        
        Expression result = visitOC_MultiplyDivideModuloExpression(multCtxs.get(0));
        
        for (int i = 1; i < multCtxs.size(); i++) {
            String op = ctx.getChild(2 * i - 1).getText();
            LogicalExpression binExpr = new LogicalExpression();
            binExpr.setOperator(op);
            binExpr.addOperand(result);
            binExpr.addOperand(visitOC_MultiplyDivideModuloExpression(multCtxs.get(i)));
            result = binExpr;
        }
        
        return result;
    }
    
    @Override
    public Expression visitOC_MultiplyDivideModuloExpression(LcypherParser.OC_MultiplyDivideModuloExpressionContext ctx) {
        List<LcypherParser.OC_PowerOfExpressionContext> powerCtxs = ctx.oC_PowerOfExpression();
        
        if (powerCtxs.size() == 1) {
            return visitOC_PowerOfExpression(powerCtxs.get(0));
        }
        
        Expression result = visitOC_PowerOfExpression(powerCtxs.get(0));
        
        for (int i = 1; i < powerCtxs.size(); i++) {
            String op = ctx.getChild(2 * i - 1).getText();
            LogicalExpression binExpr = new LogicalExpression();
            binExpr.setOperator(op);
            binExpr.addOperand(result);
            binExpr.addOperand(visitOC_PowerOfExpression(powerCtxs.get(i)));
            result = binExpr;
        }
        
        return result;
    }
    
    @Override
    public Expression visitOC_PowerOfExpression(LcypherParser.OC_PowerOfExpressionContext ctx) {
        List<LcypherParser.OC_UnaryAddOrSubtractExpressionContext> unaryCtxs = ctx.oC_UnaryAddOrSubtractExpression();
        
        if (unaryCtxs.size() == 1) {
            return visitOC_UnaryAddOrSubtractExpression(unaryCtxs.get(0));
        }
        
        Expression result = visitOC_UnaryAddOrSubtractExpression(unaryCtxs.get(0));
        
        for (int i = 1; i < unaryCtxs.size(); i++) {
            LogicalExpression binExpr = new LogicalExpression();
            binExpr.setOperator("^");
            binExpr.addOperand(result);
            binExpr.addOperand(visitOC_UnaryAddOrSubtractExpression(unaryCtxs.get(i)));
            result = binExpr;
        }
        
        return result;
    }
    
    @Override
    public Expression visitOC_UnaryAddOrSubtractExpression(LcypherParser.OC_UnaryAddOrSubtractExpressionContext ctx) {
        return visitOC_StringListNullOperatorExpression(ctx.oC_StringListNullOperatorExpression());
    }
    
    @Override
    public Expression visitOC_StringListNullOperatorExpression(LcypherParser.OC_StringListNullOperatorExpressionContext ctx) {
        Expression left = visitOC_PropertyOrLabelsExpression(ctx.oC_PropertyOrLabelsExpression());
        
        for (LcypherParser.OC_StringOperatorExpressionContext stringOpCtx : ctx.oC_StringOperatorExpression()) {
            Comparison comparison = new Comparison();
            comparison.setLeft(left);
            
            String operator;
            if (stringOpCtx.STARTS() != null) {
                operator = "STARTS WITH";
            } else if (stringOpCtx.ENDS() != null) {
                operator = "ENDS WITH";
            } else if (stringOpCtx.CONTAINS() != null) {
                operator = "CONTAINS";
            } else if (stringOpCtx.REGEXP() != null) {
                operator = "REGEXP";
            } else {
                operator = "=";
            }
            
            comparison.setOperator(operator);
            comparison.setRight(visitOC_PropertyOrLabelsExpression(stringOpCtx.oC_PropertyOrLabelsExpression()));
            left = comparison;
        }
        
        for (LcypherParser.OC_ListOperatorExpressionContext listOpCtx : ctx.oC_ListOperatorExpression()) {
            if (listOpCtx.IN() != null) {
                Comparison comparison = new Comparison();
                comparison.setLeft(left);
                comparison.setOperator("IN");
                comparison.setRight(visitOC_PropertyOrLabelsExpression(listOpCtx.oC_PropertyOrLabelsExpression()));
                left = comparison;
            }
        }
        
        for (LcypherParser.OC_NullOperatorExpressionContext nullOpCtx : ctx.oC_NullOperatorExpression()) {
            Comparison comparison = new Comparison();
            comparison.setLeft(left);
            if (nullOpCtx.NOT() != null) {
                comparison.setOperator("IS NOT NULL");
            } else {
                comparison.setOperator("IS NULL");
            }
            comparison.setRight(new Literal(null));
            left = comparison;
        }
        
        return left;
    }
    
    @Override
    public Expression visitOC_PropertyOrLabelsExpression(LcypherParser.OC_PropertyOrLabelsExpressionContext ctx) {
        Expression expr = visitOC_Atom(ctx.oC_Atom());
        
        for (LcypherParser.OC_PropertyLookupContext lookupCtx : ctx.oC_PropertyLookup()) {
            PropertyAccess propAccess = new PropertyAccess();
            propAccess.setTarget(expr);
            propAccess.setPropertyName(lookupCtx.oC_PropertyKeyName().getText());
            expr = propAccess;
        }
        
        return expr;
    }
    
    @Override
    public Expression visitOC_Atom(LcypherParser.OC_AtomContext ctx) {
        if (ctx.oC_Literal() != null) {
            return visitOC_Literal(ctx.oC_Literal());
        }
        if (ctx.oC_Parameter() != null) {
            return visitOC_Parameter(ctx.oC_Parameter());
        }
        if (ctx.oC_FunctionInvocation() != null) {
            return visitOC_FunctionInvocation(ctx.oC_FunctionInvocation());
        }
        if (ctx.oC_Variable() != null) {
            return new Variable(ctx.oC_Variable().getText());
        }
        if (ctx.oC_ParenthesizedExpression() != null) {
            return visitOC_ParenthesizedExpression(ctx.oC_ParenthesizedExpression());
        }
        
        return new Literal(null);
    }
    
    @Override
    public Expression visitOC_Literal(LcypherParser.OC_LiteralContext ctx) {
        if (ctx.StringLiteral() != null) {
            String text = ctx.StringLiteral().getText();
            return new Literal(text.substring(1, text.length() - 1));
        }
        if (ctx.oC_BooleanLiteral() != null) {
            return new Literal(ctx.oC_BooleanLiteral().TRUE_() != null);
        }
        if (ctx.NULL_() != null) {
            return new Literal(null);
        }
        if (ctx.oC_NumberLiteral() != null) {
            return visitOC_NumberLiteral(ctx.oC_NumberLiteral());
        }
        if (ctx.oC_ListLiteral() != null) {
            return visitOC_ListLiteral(ctx.oC_ListLiteral());
        }
        if (ctx.oC_MapLiteral() != null) {
            MapExpression mapExpr = new MapExpression();
            Map<String, Object> map = visitMapLiteral(ctx.oC_MapLiteral());
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getValue() instanceof Expression) {
                    mapExpr.put(entry.getKey(), (Expression) entry.getValue());
                } else {
                    mapExpr.put(entry.getKey(), new Literal(entry.getValue()));
                }
            }
            return mapExpr;
        }
        return new Literal(null);
    }
    
    @Override
    public Expression visitOC_NumberLiteral(LcypherParser.OC_NumberLiteralContext ctx) {
        String text;
        if (ctx.oC_IntegerLiteral() != null) {
            text = ctx.oC_IntegerLiteral().getText();
            try {
                return new Literal(Long.parseLong(text));
            } catch (NumberFormatException e) {
                return new Literal(text);
            }
        }
        if (ctx.oC_DoubleLiteral() != null) {
            text = ctx.oC_DoubleLiteral().getText();
            try {
                return new Literal(Double.parseDouble(text));
            } catch (NumberFormatException e) {
                return new Literal(text);
            }
        }
        return new Literal(0);
    }
    
    @Override
    public Expression visitOC_ListLiteral(LcypherParser.OC_ListLiteralContext ctx) {
        ListExpression list = new ListExpression();
        for (LcypherParser.OC_ExpressionContext exprCtx : ctx.oC_Expression()) {
            list.addElement(visitOC_Expression(exprCtx));
        }
        return list;
    }
    
    @Override
    public Expression visitOC_Parameter(LcypherParser.OC_ParameterContext ctx) {
        Parameter param = new Parameter();
        if (ctx.oC_SymbolicName() != null) {
            param.setName(ctx.oC_SymbolicName().getText());
        }
        if (ctx.DecimalInteger() != null) {
            param.setIndex(Integer.parseInt(ctx.DecimalInteger().getText()));
        }
        return param;
    }
    
    @Override
    public Expression visitOC_FunctionInvocation(LcypherParser.OC_FunctionInvocationContext ctx) {
        FunctionCall func = new FunctionCall();
        func.setFunctionName(ctx.oC_FunctionName().getText());
        func.setDistinct(ctx.DISTINCT() != null);
        
        for (LcypherParser.OC_ExpressionContext exprCtx : ctx.oC_Expression()) {
            func.addArgument(visitOC_Expression(exprCtx));
        }
        
        return func;
    }
    
    @Override
    public Expression visitOC_ParenthesizedExpression(LcypherParser.OC_ParenthesizedExpressionContext ctx) {
        return visitOC_Expression(ctx.oC_Expression());
    }
    
    @Override
    public UsingSnapshot visitOC_Snapshot(LcypherParser.OC_SnapshotContext ctx) {
        UsingSnapshot snapshot = new UsingSnapshot();
        
        if (ctx.StringLiteral() != null) {
            String text = ctx.StringLiteral().getText();
            snapshot.setSnapshotName(text.substring(1, text.length() - 1));
        }
        
        if (ctx.oC_Expression() != null) {
            snapshot.setSnapshotTime(visitOC_Expression(ctx.oC_Expression()));
        }
        
        for (LcypherParser.OC_LabelNameContext labelCtx : ctx.oC_LabelName()) {
            snapshot.addLabel(labelCtx.getText());
        }
        
        return snapshot;
    }
    
    @Override
    public ProjectBy visitOC_Project(LcypherParser.OC_ProjectContext ctx) {
        ProjectBy projectBy = new ProjectBy();
        
        LcypherParser.OC_ProjectionItemsContext itemsCtx = ctx.oC_ProjectionItems();
        for (LcypherParser.OC_ProjectionItemContext itemCtx : itemsCtx.oC_ProjectionItem()) {
            String label = itemCtx.oC_LabelName().getText();
            List<String> fields = new ArrayList<>();
            
            for (LcypherParser.OC_PropertyKeyNameContext propCtx : itemCtx.oC_PropertyKeyName()) {
                fields.add(propCtx.getText());
            }
            
            projectBy.addProjection(label, fields);
        }
        
        return projectBy;
    }
}
