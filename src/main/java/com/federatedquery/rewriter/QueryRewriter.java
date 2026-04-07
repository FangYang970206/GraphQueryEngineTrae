package com.federatedquery.rewriter;

import com.federatedquery.ast.*;
import com.federatedquery.metadata.MetadataRegistry;
import com.federatedquery.metadata.VirtualEdgeBinding;
import com.federatedquery.plan.*;
import com.federatedquery.reliability.WhereConditionPushdown;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class QueryRewriter {
    private final MetadataRegistry registry;
    private final VirtualEdgeDetector detector;
    private final WhereConditionPushdown whereConditionPushdown;
    
    public QueryRewriter(MetadataRegistry registry, 
                        VirtualEdgeDetector detector,
                        WhereConditionPushdown whereConditionPushdown) {
        this.registry = registry;
        this.detector = detector;
        this.whereConditionPushdown = whereConditionPushdown;
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
        
        if (!query.getSingleQueries().isEmpty()) {
            Statement.SingleQuery firstQuery = query.getSingleQueries().get(0);
            if (firstQuery.getUsingSnapshot() != null) {
                plan.getGlobalContext().setUsingSnapshot(firstQuery.getUsingSnapshot());
            }
        }
        
        if (query.getSingleQueries().size() > 1 || !query.getUnions().isEmpty()) {
            rewriteUnionQuery(query, plan);
        } else {
            rewriteSingleQuery(query.getSingleQueries().get(0), plan);
        }
        
        if (statement.getProjectBy() != null) {
            plan.getGlobalContext().setProjectBy(statement.getProjectBy());
        }
        
        return plan;
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
            extractWhereConditions(withClause.getWhereClause(), plan);
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
            PhysicalQuery physicalQuery = createPhysicalQuery(match, pattern);
            if (pushdownResult != null) {
                applyPhysicalConditions(physicalQuery, pushdownResult.getPhysicalConditions());
                for (WhereConditionPushdown.Condition pc : pushdownResult.getPhysicalConditions()) {
                    GlobalContext.WhereCondition condition = convertToWhereCondition(pc);
                    condition.setVirtual(false);
                    plan.getGlobalContext().addPendingFilter(condition);
                }
                for (WhereConditionPushdown.Condition vc : pushdownResult.getVirtualConditions()) {
                    GlobalContext.WhereCondition condition = convertToWhereCondition(vc);
                    condition.setVirtual(true);
                    plan.getGlobalContext().addPendingFilter(condition);
                }
            }
            plan.addPhysicalQuery(physicalQuery);
        } else {
            plan.setHasVirtualElements(true);
            rewriteMixedPattern(match, detection, pushdownResult, plan);
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
    
    private void applyPhysicalConditions(PhysicalQuery query, List<WhereConditionPushdown.Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) return;
        
        StringBuilder whereClause = new StringBuilder();
        for (int i = 0; i < conditions.size(); i++) {
            WhereConditionPushdown.Condition c = conditions.get(i);
            if (i > 0) whereClause.append(" AND ");
            whereClause.append(c.toCypher());
        }
        
        String cypher = query.getCypher();
        if (cypher != null && !cypher.contains("WHERE")) {
            int returnIdx = cypher.toUpperCase().indexOf("RETURN");
            if (returnIdx > 0) {
                cypher = cypher.substring(0, returnIdx) + "WHERE " + whereClause + " " + cypher.substring(returnIdx);
                query.setCypher(cypher);
            }
        }
    }
    
    private void applyVirtualConditionsToExternalQuery(ExternalQuery query, List<WhereConditionPushdown.Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) return;
        
        for (WhereConditionPushdown.Condition c : conditions) {
            String key = c.getProperty() != null ? c.getProperty() : c.getVariable();
            query.addFilter(key, c.getValue());
        }
    }
    
    private void rewriteMixedPattern(MatchClause match, VirtualEdgeDetector.DetectionResult detection, 
                                     WhereConditionPushdown.PushdownResult pushdownResult, ExecutionPlan plan) {
        List<VirtualEdgeDetector.VirtualEdgePart> virtualEdges = detection.getVirtualEdgeParts();
        List<VirtualEdgeDetector.VirtualNodePart> virtualNodes = detection.getVirtualNodeParts();
        
        if (!virtualEdges.isEmpty()) {
            for (VirtualEdgeDetector.VirtualEdgePart ve : virtualEdges) {
                if (ve.isFirstHop()) {
                    PhysicalQuery startQuery = createStartNodeQuery(ve.getStartNode());
                    if (startQuery != null) {
                        if (pushdownResult != null) {
                            applyPhysicalConditions(startQuery, pushdownResult.getPhysicalConditions());
                        }
                        plan.addPhysicalQuery(startQuery);
                    }
                }
                
                ExternalQuery extQuery = createExternalQuery(ve, plan);
                if (pushdownResult != null) {
                    applyVirtualConditionsToExternalQuery(extQuery, pushdownResult.getVirtualConditions());
                }
                plan.addExternalQuery(extQuery);
            }
        }
        
        if (!detection.getPhysicalEdgeParts().isEmpty()) {
            PhysicalQuery physicalQuery = createPhysicalQueryFromParts(
                    detection.getPhysicalNodeParts(), 
                    detection.getPhysicalEdgeParts(),
                    match
            );
            if (physicalQuery != null) {
                if (pushdownResult != null) {
                    applyPhysicalConditions(physicalQuery, pushdownResult.getPhysicalConditions());
                }
                plan.addPhysicalQuery(physicalQuery);
            }
        }
        
        for (VirtualEdgeDetector.VirtualNodePart vn : virtualNodes) {
            ExternalQuery nodeQuery = createVirtualNodeQuery(vn, plan);
            if (pushdownResult != null) {
                applyVirtualConditionsToExternalQuery(nodeQuery, pushdownResult.getVirtualConditions());
            }
            plan.addExternalQuery(nodeQuery);
        }
    }
    
    private PhysicalQuery createPhysicalQuery(MatchClause match, Pattern pattern) {
        PhysicalQuery query = new PhysicalQuery();
        query.setId(UUID.randomUUID().toString());
        query.setCypher("MATCH " + pattern.toCypher() + " " + buildReturnClause(match));
        query.setQueryType(PhysicalQuery.QueryType.MATCH);
        
        extractOutputVariables(pattern, query);
        
        return query;
    }
    
    private PhysicalQuery createPhysicalQueryFromParts(
            List<VirtualEdgeDetector.PhysicalNodePart> nodes,
            List<VirtualEdgeDetector.PhysicalEdgePart> edges,
            MatchClause match) {
        
        if (edges.isEmpty() && nodes.isEmpty()) {
            return null;
        }
        
        PhysicalQuery query = new PhysicalQuery();
        query.setId(UUID.randomUUID().toString());
        
        StringBuilder cypher = new StringBuilder("MATCH ");
        
        if (!nodes.isEmpty()) {
            VirtualEdgeDetector.PhysicalNodePart firstNode = nodes.get(0);
            cypher.append(buildNodePattern(firstNode.getVariable(), firstNode.getLabels(), firstNode.getProperties()));
        }
        
        for (VirtualEdgeDetector.PhysicalEdgePart edge : edges) {
            cypher.append(buildRelationshipPattern(edge));
            if (edge.getEndNode() != null) {
                cypher.append(buildNodePatternFromAst(edge.getEndNode()));
            }
        }
        
        cypher.append(" ").append(buildReturnClause(match));
        query.setCypher(cypher.toString());
        query.setQueryType(PhysicalQuery.QueryType.MATCH);
        
        return query;
    }
    
    private PhysicalQuery createStartNodeQuery(NodePattern node) {
        if (node == null) return null;
        
        PhysicalQuery query = new PhysicalQuery();
        query.setId(UUID.randomUUID().toString());
        
        StringBuilder cypher = new StringBuilder("MATCH ");
        cypher.append(buildNodePatternFromAst(node));
        cypher.append(" RETURN ").append(node.getVariable() != null ? node.getVariable() : "n");
        
        query.setCypher(cypher.toString());
        query.setQueryType(PhysicalQuery.QueryType.MATCH);
        
        if (node.getVariable() != null) {
            query.addOutputVariable(node.getVariable());
        }
        
        return query;
    }
    
    private ExternalQuery createExternalQuery(VirtualEdgeDetector.VirtualEdgePart ve, ExecutionPlan plan) {
        ExternalQuery query = new ExternalQuery();
        query.setId(UUID.randomUUID().toString());
        query.setDataSource(ve.getDataSource());
        query.setOperator(ve.getOperator());
        
        if (ve.getStartNode() != null && ve.getStartNode().getVariable() != null) {
            query.setInputIdField(ve.getStartNode().getVariable());
        }
        
        if (ve.getVariable() != null) {
            query.addOutputVariable(ve.getVariable());
        }
        if (ve.getEndNode() != null && ve.getEndNode().getVariable() != null) {
            query.addOutputVariable(ve.getEndNode().getVariable());
        }
        
        Optional<VirtualEdgeBinding> binding = registry.getVirtualEdgeBinding(ve.getEdgeType());
        binding.ifPresent(b -> query.setOutputFields(b.getOutputFields()));
        
        applySnapshotToQuery(query, plan);
        
        return query;
    }
    
    private ExternalQuery createVirtualNodeQuery(VirtualEdgeDetector.VirtualNodePart vn, ExecutionPlan plan) {
        ExternalQuery query = new ExternalQuery();
        query.setId(UUID.randomUUID().toString());
        query.setDataSource(vn.getDataSource());
        query.setOperator("getByLabel");
        
        if (vn.getVariable() != null) {
            query.addOutputVariable(vn.getVariable());
        }
        
        applySnapshotToQuery(query, plan);
        
        return query;
    }
    
    private void applySnapshotToQuery(ExternalQuery query, ExecutionPlan plan) {
        UsingSnapshot snapshot = plan.getGlobalContext().getUsingSnapshot();
        if (snapshot != null) {
            query.setSnapshotName(snapshot.getSnapshotName());
            Long unixTimestamp = snapshot.getSnapshotTimeAsUnixTimestamp();
            if (unixTimestamp != null) {
                query.setSnapshotTime(unixTimestamp);
            }
        }
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
    
    private void extractWhereConditions(WhereClause where, ExecutionPlan plan) {
        extractConditions(where.getExpression(), plan);
    }
    
    private void extractConditions(Expression expr, ExecutionPlan plan) {
        if (expr instanceof LogicalExpression) {
            LogicalExpression logic = (LogicalExpression) expr;
            for (Expression operand : logic.getOperands()) {
                extractConditions(operand, plan);
            }
        } else if (expr instanceof Comparison) {
            Comparison comp = (Comparison) expr;
            GlobalContext.WhereCondition condition = new GlobalContext.WhereCondition();
            condition.setOperator(comp.getOperator());
            condition.setOriginalExpression(expr);
            
            if (comp.getLeft() instanceof PropertyAccess) {
                PropertyAccess pa = (PropertyAccess) comp.getLeft();
                if (pa.getTarget() instanceof Variable) {
                    condition.setVariable(((Variable) pa.getTarget()).getName());
                }
                condition.setProperty(pa.getPropertyName());
            }
            
            if (comp.getRight() instanceof Literal) {
                condition.setValue(((Literal) comp.getRight()).getValue());
            }
            
            if (condition.getVariable() != null) {
                plan.getGlobalContext().addPendingFilter(condition);
            }
        }
    }
    
    private void extractOutputVariables(Pattern pattern, PhysicalQuery query) {
        for (Pattern.PatternPart part : pattern.getPatternParts()) {
            if (part.getVariable() != null) {
                query.addOutputVariable(part.getVariable());
            }
            extractVariablesFromElement(part.getPatternElement(), query);
        }
    }
    
    private void extractVariablesFromElement(Pattern.PatternElement element, PhysicalQuery query) {
        if (element.getNodePattern() != null) {
            String var = element.getNodePattern().getVariable();
            if (var != null) {
                query.addOutputVariable(var);
            }
        }
        
        for (Pattern.PatternElementChain chain : element.getChains()) {
            if (chain.getRelationshipPattern().getVariable() != null) {
                query.addOutputVariable(chain.getRelationshipPattern().getVariable());
            }
            if (chain.getNodePattern().getVariable() != null) {
                query.addOutputVariable(chain.getNodePattern().getVariable());
            }
        }
    }
    
    private String buildReturnClause(MatchClause match) {
        return "RETURN *";
    }
    
    private String buildNodePattern(String variable, List<String> labels, Map<String, Object> properties) {
        StringBuilder sb = new StringBuilder("(");
        if (variable != null) {
            sb.append(variable);
        }
        for (String label : labels) {
            sb.append(":").append(label);
        }
        if (properties != null && !properties.isEmpty()) {
            sb.append(" {");
            boolean first = true;
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(entry.getKey()).append(": ");
                if (entry.getValue() instanceof String) {
                    sb.append("'").append(entry.getValue()).append("'");
                } else {
                    sb.append(entry.getValue());
                }
                first = false;
            }
            sb.append("}");
        }
        sb.append(")");
        return sb.toString();
    }
    
    private String buildNodePatternFromAst(NodePattern node) {
        return buildNodePattern(node.getVariable(), node.getLabels(), node.getProperties());
    }
    
    private String buildRelationshipPattern(VirtualEdgeDetector.PhysicalEdgePart edge) {
        StringBuilder sb = new StringBuilder();
        
        RelationshipPattern.Direction dir = edge.getDirection();
        if (dir == RelationshipPattern.Direction.LEFT) {
            sb.append("<");
        }
        sb.append("-");
        
        if (!edge.getEdgeTypes().isEmpty() || edge.getVariable() != null) {
            sb.append("[");
            if (edge.getVariable() != null) {
                sb.append(edge.getVariable());
            }
            for (int i = 0; i < edge.getEdgeTypes().size(); i++) {
                if (i == 0) sb.append(":");
                else sb.append("|:");
                sb.append(edge.getEdgeTypes().get(i));
            }
            sb.append("]");
        }
        
        sb.append("-");
        if (dir == RelationshipPattern.Direction.RIGHT) {
            sb.append(">");
        }
        
        return sb.toString();
    }
}
