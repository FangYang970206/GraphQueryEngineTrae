package com.fangyang.federatedquery.rewriter;

import com.fangyang.federatedquery.ast.*;
import com.fangyang.metadata.MetadataQueryService;
import com.fangyang.metadata.VirtualEdgeBinding;
import com.fangyang.federatedquery.plan.ExecutionPlan;
import com.fangyang.federatedquery.plan.ExternalQuery;
import com.fangyang.federatedquery.plan.PhysicalQuery;
import com.fangyang.federatedquery.ast.UsingSnapshot;
import com.fangyang.federatedquery.reliability.WhereConditionPushdown;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class MixedPatternRewriter {

    @Autowired
    private MetadataQueryService metadataQueryService;
    @Autowired
    private PhysicalQueryBuilder physicalQueryBuilder;

    public MixedPatternRewriter(MetadataQueryService metadataQueryService, PhysicalQueryBuilder physicalQueryBuilder) {
        this.metadataQueryService = metadataQueryService;
        this.physicalQueryBuilder = physicalQueryBuilder;
    }

    public void rewriteMixedPattern(MatchClause match, VirtualEdgeDetector.DetectionResult detection,
                                     WhereConditionPushdown.PushdownResult pushdownResult, ExecutionPlan plan) {
        List<VirtualEdgeDetector.VirtualEdgePart> virtualEdges = detection.getVirtualEdgeParts();
        List<VirtualEdgeDetector.VirtualNodePart> virtualNodes = detection.getVirtualNodeParts();

        if (!virtualEdges.isEmpty()) {
            for (VirtualEdgeDetector.VirtualEdgePart ve : virtualEdges) {
                ExternalQuery extQuery = createExternalQuery(ve, plan);
                if (pushdownResult != null) {
                    applyVirtualConditionsToExternalQuery(extQuery, pushdownResult.getVirtualConditions());
                }
                plan.addExternalQuery(extQuery);
            }
        }

        if (!detection.getPhysicalEdgeParts().isEmpty()) {
            PhysicalQuery physicalQuery = physicalQueryBuilder.createPhysicalQueryFromParts(
                    detection.getPhysicalNodeParts(),
                    detection.getPhysicalEdgeParts(),
                    match
            );
            if (physicalQuery != null) {
                if (pushdownResult != null) {
                    physicalQueryBuilder.applyPhysicalConditions(physicalQuery, pushdownResult.getPhysicalConditions());
                }
                applyFirstHopDependency(physicalQuery, virtualEdges);
                plan.addPhysicalQuery(physicalQuery);
            }
        }

        for (VirtualEdgeDetector.VirtualNodePart vn : virtualNodes) {
            if (isCoveredByVirtualEdge(vn, virtualEdges)) {
                continue;
            }
            ExternalQuery nodeQuery = createVirtualNodeQuery(vn, plan);
            if (pushdownResult != null) {
                applyVirtualConditionsToExternalQuery(nodeQuery, pushdownResult.getVirtualConditions());
            }
            plan.addExternalQuery(nodeQuery);
        }
    }

    private ExternalQuery createExternalQuery(VirtualEdgeDetector.VirtualEdgePart ve, ExecutionPlan plan) {
        ExternalQuery query = new ExternalQuery();
        query.setId(UUID.randomUUID().toString());
        query.setDataSource(ve.getDataSource());
        query.setOperator(ve.getOperator());
        query.setEdgeType(ve.getEdgeType());

        Optional<VirtualEdgeBinding> binding = metadataQueryService.getVirtualEdgeBinding(ve.getEdgeType());
        binding.ifPresent(b -> {
            query.setOutputFields(b.getOutputFields());
            query.setTargetLabel(b.getTargetLabel());
        });

        if (ve.isFirstHop()) {
            if (ve.getStartNode() != null && ve.getStartNode().getVariable() != null) {
                query.setSourceVariableName(ve.getStartNode().getVariable());
            }
            if (ve.getIdMapping() != null && !ve.getIdMapping().isEmpty()) {
                Map.Entry<String, String> firstMapping = ve.getIdMapping().entrySet().iterator().next();
                query.setOutputIdField(firstMapping.getValue());
            }
        } else if (ve.getStartNode() != null && ve.getStartNode().getVariable() != null) {
            String sourceVar = ve.getStartNode().getVariable();
            query.setSourceVariableName(sourceVar);
            query.setDependsOnPhysicalQuery(true);

            if (ve.getIdMapping() != null && !ve.getIdMapping().isEmpty()) {
                Map.Entry<String, String> firstMapping = ve.getIdMapping().entrySet().iterator().next();
                query.setInputIdField(firstMapping.getKey());
                query.setOutputIdField(firstMapping.getValue());
            }
        }

        if (ve.getVariable() != null) {
            query.addOutputVariable(ve.getVariable());
        }
        if (ve.isFirstHop()) {
            if (ve.getStartNode() != null && ve.getStartNode().getVariable() != null) {
                query.addOutputVariable(ve.getStartNode().getVariable());
            }
        } else if (ve.getEndNode() != null && ve.getEndNode().getVariable() != null) {
            query.addOutputVariable(ve.getEndNode().getVariable());
        }

        applySnapshotToQuery(query, plan);

        return query;
    }

    private void applyFirstHopDependency(
            PhysicalQuery physicalQuery,
            List<VirtualEdgeDetector.VirtualEdgePart> virtualEdges) {
        if (physicalQuery == null || virtualEdges == null || virtualEdges.isEmpty()) {
            return;
        }

        for (VirtualEdgeDetector.VirtualEdgePart ve : virtualEdges) {
            if (!ve.isFirstHop() || ve.getIdMapping() == null || ve.getIdMapping().isEmpty()) {
                continue;
            }
            if (ve.getStartNode() == null || ve.getEndNode() == null || ve.getEndNode().getVariable() == null) {
                continue;
            }

            Map.Entry<String, String> firstMapping = ve.getIdMapping().entrySet().iterator().next();
            physicalQueryBuilder.applyDependentInputCondition(
                    physicalQuery,
                    ve.getStartNode().getVariable(),
                    firstMapping.getValue(),
                    ve.getEndNode().getVariable(),
                    firstMapping.getKey());
            return;
        }
    }

    private boolean isCoveredByVirtualEdge(
            VirtualEdgeDetector.VirtualNodePart virtualNode,
            List<VirtualEdgeDetector.VirtualEdgePart> virtualEdges) {
        if (virtualNode == null || virtualEdges == null || virtualEdges.isEmpty()) {
            return false;
        }
        for (VirtualEdgeDetector.VirtualEdgePart edge : virtualEdges) {
            if (matchesVirtualNode(virtualNode, edge.getStartNode())) {
                return true;
            }
            if (matchesVirtualNode(virtualNode, edge.getEndNode())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesVirtualNode(VirtualEdgeDetector.VirtualNodePart virtualNode, NodePattern nodePattern) {
        if (virtualNode == null || nodePattern == null) {
            return false;
        }
        if (!Objects.equals(virtualNode.getVariable(), nodePattern.getVariable())) {
            return false;
        }
        return nodePattern.getLabels().contains(virtualNode.getLabel());
    }

    private ExternalQuery createVirtualNodeQuery(VirtualEdgeDetector.VirtualNodePart vn, ExecutionPlan plan) {
        ExternalQuery query = new ExternalQuery();
        query.setId(UUID.randomUUID().toString());
        query.setDataSource(vn.getDataSource());
        query.setOperator("getByLabel");

        if (vn.getVariable() != null) {
            query.addOutputVariable(vn.getVariable());
        }

        if (vn.getProperties() != null && !vn.getProperties().isEmpty()) {
            for (Map.Entry<String, Object> entry : vn.getProperties().entrySet()) {
                query.addFilter(entry.getKey(), entry.getValue());
            }
        }

        if (vn.getLabel() != null) {
            query.addFilter("_label", vn.getLabel());
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

    private void applyVirtualConditionsToExternalQuery(ExternalQuery query, List<WhereConditionPushdown.Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) return;

        for (WhereConditionPushdown.Condition c : conditions) {
            String key = c.getProperty() != null ? c.getProperty() : c.getVariable();
            if (isEqualityOperator(c.getOperator())) {
                query.addFilter(key, c.getValue());
            }
            query.addFilterCondition(key, c.getOperator(), c.getValue());
        }
    }

    private boolean isEqualityOperator(String operator) {
        return "=".equals(operator) || "==".equals(operator);
    }
}
