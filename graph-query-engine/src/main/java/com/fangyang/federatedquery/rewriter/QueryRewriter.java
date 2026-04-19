package com.fangyang.federatedquery.rewriter;

import com.fangyang.federatedquery.ast.*;
import com.fangyang.federatedquery.exception.VirtualEdgeConstraintException;
import com.fangyang.metadata.MetadataQueryService;
import com.fangyang.federatedquery.plan.*;
import com.fangyang.federatedquery.reliability.WhereConditionPushdown;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class QueryRewriter {
    @Autowired
    private MetadataQueryService metadataQueryService;
    @Autowired
    private VirtualEdgeDetector detector;
    @Autowired
    private WhereConditionPushdown whereConditionPushdown;
    @Autowired
    private PhysicalQueryBuilder physicalQueryBuilder;
    @Autowired
    private MixedPatternRewriter mixedPatternRewriter;

    public QueryRewriter(MetadataQueryService metadataQueryService,
                        VirtualEdgeDetector detector,
                        WhereConditionPushdown whereConditionPushdown,
                        PhysicalQueryBuilder physicalQueryBuilder,
                        MixedPatternRewriter mixedPatternRewriter) {
        this.metadataQueryService = metadataQueryService;
        this.detector = detector;
        this.whereConditionPushdown = whereConditionPushdown;
        this.physicalQueryBuilder = physicalQueryBuilder;
        this.mixedPatternRewriter = mixedPatternRewriter;
    }

    public MetadataQueryService getMetadataQueryService() {
        return metadataQueryService;
    }

    public ExecutionPlan rewrite(Program program) {
        ExecutionPlan plan = new ExecutionPlan();
        plan.setPlanId(UUID.randomUUID().toString());
        plan.setOriginalCypher(program.toCypher());

        Statement statement = program.getStatement();
        if (statement == null || statement.getQuery() == null) {
            return plan;
        }

        Statement.Query query = statement.getQuery();

        boolean hasVirtualElements = hasAnyVirtualElements(query);
        plan.setHasVirtualElements(hasVirtualElements);
        plan.setDirectExecution(!hasVirtualElements);

        extractSnapshotInfo(query, plan);

        if (query.getSingleQueries().size() > 1 || !query.getUnions().isEmpty()) {
            rewriteUnionQuery(query, plan);
        } else {
            rewriteSingleQuery(query.getSingleQueries().get(0), plan);
        }

        extractProjectByInfo(query, statement, plan);

        return plan;
    }

    private boolean hasAnyVirtualElements(Statement.Query query) {
        for (Statement.SingleQuery sq : query.getSingleQueries()) {
            if (hasVirtualElementsInSingleQuery(sq)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasVirtualElementsInSingleQuery(Statement.SingleQuery sq) {
        for (MatchClause match : sq.getMatchClauses()) {
            Pattern pattern = match.getPattern();
            if (pattern != null) {
                VirtualEdgeDetector.DetectionResult detection = detector.detect(pattern);
                if (detection.hasVirtualElements()) {
                    return true;
                }
            }
        }
        for (List<MatchClause> matchList : sq.getPrecedingMatchClauses()) {
            for (MatchClause match : matchList) {
                Pattern pattern = match.getPattern();
                if (pattern != null) {
                    VirtualEdgeDetector.DetectionResult detection = detector.detect(pattern);
                    if (detection.hasVirtualElements()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void extractSnapshotInfo(Statement.Query query, ExecutionPlan plan) {
        if (!query.getSingleQueries().isEmpty()) {
            Statement.SingleQuery firstQuery = query.getSingleQueries().get(0);
            if (firstQuery.getUsingSnapshot() != null) {
                plan.getGlobalContext().setUsingSnapshot(firstQuery.getUsingSnapshot());
            }
        }
    }

    private void extractProjectByInfo(Statement.Query query, Statement statement, ExecutionPlan plan) {
        if (!query.getSingleQueries().isEmpty()) {
            Statement.SingleQuery firstQuery = query.getSingleQueries().get(0);
            if (firstQuery.getProjectBy() != null) {
                plan.getGlobalContext().setProjectBy(firstQuery.getProjectBy());
            } else if (statement.getProjectBy() != null) {
                plan.getGlobalContext().setProjectBy(statement.getProjectBy());
            }
        } else if (statement.getProjectBy() != null) {
            plan.getGlobalContext().setProjectBy(statement.getProjectBy());
        }
    }

    private void rewriteUnionQuery(Statement.Query query, ExecutionPlan plan) {
        UnionPart unionPart = new UnionPart();
        unionPart.setId(UUID.randomUUID().toString());

        for (int i = 0; i < query.getSingleQueries().size(); i++) {
            Statement.SingleQuery sq = query.getSingleQueries().get(i);
            ExecutionPlan subPlan = new ExecutionPlan();
            subPlan.setPlanId(UUID.randomUUID().toString());
            rewriteSingleQuery(sq, subPlan);
            unionPart.addSubPlan(subPlan);

            if (i < query.getUnions().size()) {
                UnionClause union = query.getUnions().get(i);
                unionPart.setAll(union.isAll());
            }
        }

        plan.addUnionPart(unionPart);
    }

    private void rewriteSingleQuery(Statement.SingleQuery singleQuery, ExecutionPlan plan) {
        if (singleQuery.hasMultiPartQuery()) {
            rewriteMultiPartQuery(singleQuery, plan);
        } else {
            for (MatchClause match : singleQuery.getMatchClauses()) {
                rewriteMatchClause(match, match.getWhereClause(), plan);
            }

            if (singleQuery.getReturnClause() != null) {
                rewriteReturnClause(singleQuery.getReturnClause(), plan);
            }
        }
    }

    private void rewriteMultiPartQuery(Statement.SingleQuery singleQuery, ExecutionPlan plan) {
        List<WithClause> withClauses = singleQuery.getPrecedingWithClauses();
        List<List<MatchClause>> matchClausesList = singleQuery.getPrecedingMatchClauses();
        List<WhereClause> whereClauses = singleQuery.getPrecedingWhereClauses();

        for (int i = 0; i < matchClausesList.size(); i++) {
            List<MatchClause> matches = matchClausesList.get(i);
            WhereClause where = i < whereClauses.size() ? whereClauses.get(i) : null;

            for (MatchClause match : matches) {
                rewriteMatchClause(match, where, plan);
            }

            if (i < withClauses.size()) {
                WithClause withClause = withClauses.get(i);
                applyWithClause(withClause, plan);
            }
        }

        for (MatchClause match : singleQuery.getMatchClauses()) {
            rewriteMatchClause(match, singleQuery.getWhereClause(), plan);
        }

        if (singleQuery.getReturnClause() != null) {
            rewriteReturnClause(singleQuery.getReturnClause(), plan);
        }
    }

    private void applyWithClause(WithClause withClause, ExecutionPlan plan) {
        if (withClause.getWhereClause() != null) {
            addPostFilterExpression(plan, withClause.getWhereClause().getExpression());
        }

        if (withClause.getOrderByClause() != null) {
            GlobalContext.OrderSpec orderSpec = convertOrderBy(withClause.getOrderByClause());
            plan.getGlobalContext().setGlobalOrder(orderSpec);
        }

        GlobalContext.LimitSpec limitSpec = new GlobalContext.LimitSpec();
        if (withClause.getSkipClause() != null) {
            limitSpec.setSkip(withClause.getSkipClause().getSkipValue());
        }
        if (withClause.getLimitClause() != null) {
            limitSpec.setLimit(withClause.getLimitClause().getLimitValue());
        }
        if (withClause.getSkipClause() != null || withClause.getLimitClause() != null) {
            plan.getGlobalContext().setGlobalLimit(limitSpec);
        }

        List<String> passedVariables = new ArrayList<>();
        for (ReturnClause.ReturnItem item : withClause.getReturnItems()) {
            if (item.getAlias() != null) {
                passedVariables.add(item.getAlias());
            } else if (item.getExpression() != null) {
                String varName = extractVariableName(item.getExpression());
                if (varName != null) {
                    passedVariables.add(varName);
                }
            }
        }
        plan.getGlobalContext().setWithVariables(passedVariables);
    }

    private String extractVariableName(Expression expr) {
        if (expr instanceof Variable) {
            return ((Variable) expr).getName();
        }
        return null;
    }

    private void rewriteMatchClause(MatchClause match, WhereClause whereClause, ExecutionPlan plan) {
        Pattern pattern = match.getPattern();
        if (pattern == null) return;

        VirtualEdgeDetector.DetectionResult detection = detector.detect(pattern);

        if (detection.hasErrors()) {
            throw new VirtualEdgeConstraintException(String.join("; ", detection.getErrors()));
        }

        WhereConditionPushdown.PushdownResult pushdownResult = null;
        if (whereClause != null) {
            pushdownResult = whereConditionPushdown.analyze(whereClause, pattern);
        }

        if (!detection.hasVirtualElements()) {
            PhysicalQuery physicalQuery = physicalQueryBuilder.createPhysicalQuery(match, pattern);
            if (pushdownResult != null) {
                physicalQueryBuilder.applyPhysicalConditions(physicalQuery, pushdownResult.getPhysicalConditions());
                applyPendingFilters(plan, pushdownResult);
            }
            plan.addPhysicalQuery(physicalQuery);
        } else {
            plan.setHasVirtualElements(true);
            mixedPatternRewriter.rewriteMixedPattern(match, detection, pushdownResult, plan);
        }
    }

    private void applyPendingFilters(ExecutionPlan plan, WhereConditionPushdown.PushdownResult pushdownResult) {
        for (WhereConditionPushdown.Condition physical : pushdownResult.getPhysicalConditions()) {
            GlobalContext.WhereCondition validationCondition = convertToWhereCondition(physical);
            validationCondition.setVirtual(false);
            plan.getGlobalContext().addValidationFilter(validationCondition);
        }
        for (WhereConditionPushdown.Condition postFilter : pushdownResult.getPostFilterConditions()) {
            plan.getGlobalContext().addPendingFilter(convertToWhereCondition(postFilter));
        }
    }

    private GlobalContext.WhereCondition convertToWhereCondition(WhereConditionPushdown.Condition c) {
        GlobalContext.WhereCondition condition = new GlobalContext.WhereCondition();
        condition.setVariable(c.getVariable());
        condition.setProperty(c.getProperty());
        condition.setOperator(c.getOperator());
        condition.setValue(c.getValue());
        condition.setOriginalExpression(c.getOriginalExpression());
        return condition;
    }

    private void rewriteReturnClause(ReturnClause returnClause, ExecutionPlan plan) {
        GlobalContext context = plan.getGlobalContext();

        if (returnClause.getOrderByClause() != null) {
            GlobalContext.OrderSpec orderSpec = convertOrderBy(returnClause.getOrderByClause());
            context.setGlobalOrder(orderSpec);
        }

        if (returnClause.getLimitClause() != null) {
            GlobalContext.LimitSpec limitSpec = new GlobalContext.LimitSpec();
            Integer limit = returnClause.getLimitClause().getLimitValue();
            if (limit != null) {
                limitSpec.setLimit(limit);
            }
            context.setGlobalLimit(limitSpec);
        }

        if (returnClause.getSkipClause() != null) {
            GlobalContext.LimitSpec limitSpec = context.getGlobalLimit();
            if (limitSpec == null) {
                limitSpec = new GlobalContext.LimitSpec();
                context.setGlobalLimit(limitSpec);
            }
            Integer skip = returnClause.getSkipClause().getSkipValue();
            if (skip != null) {
                limitSpec.setSkip(skip);
            }
        }
    }

    private GlobalContext.OrderSpec convertOrderBy(OrderByClause orderByClause) {
        GlobalContext.OrderSpec orderSpec = new GlobalContext.OrderSpec();
        for (OrderByClause.SortItem item : orderByClause.getSortItems()) {
            GlobalContext.OrderItem orderItem = new GlobalContext.OrderItem();
            if (item.getExpression() instanceof PropertyAccess) {
                PropertyAccess pa = (PropertyAccess) item.getExpression();
                if (pa.getTarget() instanceof Variable) {
                    orderItem.setVariable(((Variable) pa.getTarget()).getName());
                }
                orderItem.setProperty(pa.getPropertyName());
            } else if (item.getExpression() instanceof Variable) {
                orderItem.setVariable(((Variable) item.getExpression()).getName());
            }
            orderItem.setDescending(item.getDirection() == OrderByClause.SortDirection.DESC);
            orderSpec.addItem(orderItem);
        }
        return orderSpec;
    }

    private void addPostFilterExpression(ExecutionPlan plan, Expression expression) {
        if (expression == null) {
            return;
        }
        GlobalContext.WhereCondition condition = new GlobalContext.WhereCondition();
        condition.setOriginalExpression(expression);
        plan.getGlobalContext().addPendingFilter(condition);
    }
}
