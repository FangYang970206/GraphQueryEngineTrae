package com.fangyang.federatedquery.rewriter;

import com.fangyang.federatedquery.ast.*;
import com.fangyang.federatedquery.plan.PhysicalQuery;
import com.fangyang.federatedquery.reliability.WhereConditionPushdown;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class PhysicalQueryBuilder {

    public PhysicalQuery createPhysicalQuery(MatchClause match, Pattern pattern) {
        PhysicalQuery query = new PhysicalQuery();
        query.setId(UUID.randomUUID().toString());
        query.setCypher("MATCH " + pattern.toCypher() + " " + buildReturnClause(match));
        query.setQueryType(PhysicalQuery.QueryType.MATCH);

        extractOutputVariables(pattern, query);

        return query;
    }

    public PhysicalQuery createPhysicalQueryFromParts(
            List<VirtualEdgeDetector.PhysicalNodePart> nodes,
            List<VirtualEdgeDetector.PhysicalEdgePart> edges,
            MatchClause match) {

        if (edges.isEmpty() && nodes.isEmpty()) {
            return null;
        }

        PhysicalQuery query = new PhysicalQuery();
        query.setId(UUID.randomUUID().toString());

        StringBuilder cypher = new StringBuilder("MATCH ");

        Set<String> physicalVariables = new LinkedHashSet<>();

        if (!nodes.isEmpty()) {
            VirtualEdgeDetector.PhysicalNodePart firstNode = nodes.get(0);
            cypher.append(buildNodePattern(firstNode.getVariable(), firstNode.getLabels(), firstNode.getProperties()));
            if (firstNode.getVariable() != null) {
                physicalVariables.add(firstNode.getVariable());
            }
        }

        for (VirtualEdgeDetector.PhysicalEdgePart edge : edges) {
            cypher.append(buildRelationshipPattern(edge));
            if (edge.getVariable() != null) {
                physicalVariables.add(edge.getVariable());
            }
            if (edge.getEndNode() != null) {
                cypher.append(buildNodePatternFromAst(edge.getEndNode()));
                if (edge.getEndNode().getVariable() != null) {
                    physicalVariables.add(edge.getEndNode().getVariable());
                }
            }
        }

        String returnClause = physicalVariables.isEmpty() ? "RETURN *" : "RETURN " + String.join(", ", physicalVariables);
        cypher.append(" ").append(returnClause);
        query.setCypher(cypher.toString());
        query.setQueryType(PhysicalQuery.QueryType.MATCH);

        return query;
    }

    public PhysicalQuery createStartNodeQuery(NodePattern node) {
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

    public void applyPhysicalConditions(PhysicalQuery query, List<WhereConditionPushdown.Condition> conditions) {
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

    private String buildReturnClause(MatchClause match) {
        Set<String> variables = new LinkedHashSet<>();
        extractVariablesFromMatch(match, variables);
        if (variables.isEmpty()) {
            return "RETURN *";
        }
        return "RETURN " + String.join(", ", variables);
    }

    private void extractVariablesFromMatch(MatchClause match, Set<String> variables) {
        Pattern pattern = match.getPattern();
        if (pattern == null) return;

        for (Pattern.PatternPart part : pattern.getPatternParts()) {
            extractVariablesFromElement(part.getPatternElement(), variables);
        }
    }

    private void extractVariablesFromElement(Pattern.PatternElement element, Set<String> variables) {
        if (element.getNodePattern() != null) {
            String var = element.getNodePattern().getVariable();
            if (var != null) {
                variables.add(var);
            }
        }

        for (Pattern.PatternElementChain chain : element.getChains()) {
            if (chain.getRelationshipPattern().getVariable() != null) {
                variables.add(chain.getRelationshipPattern().getVariable());
            }
            if (chain.getNodePattern().getVariable() != null) {
                variables.add(chain.getNodePattern().getVariable());
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
}
