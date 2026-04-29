package com.fangyang.federatedquery.rewriter;

import com.fangyang.federatedquery.ast.*;
import com.fangyang.metadata.MetadataQueryService;
import com.fangyang.metadata.VirtualEdgeBinding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class VirtualEdgeDetector {
    @Autowired
    private MetadataQueryService metadataQueryService;

    public VirtualEdgeDetector(MetadataQueryService metadataQueryService) {
        this.metadataQueryService = metadataQueryService;
    }
    
    public DetectionResult detect(Pattern pattern) {
        DetectionResult result = new DetectionResult();
        
        for (Pattern.PatternPart part : pattern.getPatternParts()) {
            detectInPatternPart(part, result);
        }

        return result;
    }
    
    private void detectInPatternPart(Pattern.PatternPart part, DetectionResult result) {
        Pattern.PatternElement element = part.getPatternElement();
        if (element == null) return;
        
        NodePattern startNode = element.getNodePattern();
        if (startNode != null) {
            checkNodeVirtual(startNode, result);
        }
        
        List<Pattern.PatternElementChain> chains = element.getChains();
        List<Boolean> edgeKindsInOrder = new ArrayList<>();
        for (int i = 0; i < chains.size(); i++) {
            Pattern.PatternElementChain chain = chains.get(i);
            RelationshipPattern rel = chain.getRelationshipPattern();
            NodePattern endNode = chain.getNodePattern();
            
            boolean isFirst = (i == 0);
            boolean isLast = (i == chains.size() - 1);
            
            boolean hasVirtualRelationship = checkRelationshipVirtual(rel, startNode, endNode, isFirst, isLast, result);
            edgeKindsInOrder.add(hasVirtualRelationship);
            
            checkNodeVirtual(endNode, result);
            
            startNode = endNode;
        }

        validatePatternConstraints(element, edgeKindsInOrder, result);
    }
    
    private void checkNodeVirtual(NodePattern node, DetectionResult result) {
        if (node.getLabels().isEmpty()) {
            PhysicalNodePart part = new PhysicalNodePart();
            part.setVariable(node.getVariable());
            part.setProperties(node.getProperties());
            result.addPhysicalNodePart(part);
            return;
        }
        
        for (String label : node.getLabels()) {
            if (metadataQueryService.isVirtualLabel(label)) {
                VirtualNodePart part = new VirtualNodePart();
                part.setVariable(node.getVariable());
                part.setLabel(label);
                part.setProperties(node.getProperties());
                part.setDataSource(metadataQueryService.getDataSourceForLabel(label));
                result.addVirtualNodePart(part);
            } else {
                PhysicalNodePart part = new PhysicalNodePart();
                part.setVariable(node.getVariable());
                part.setLabels(node.getLabels());
                part.setProperties(node.getProperties());
                result.addPhysicalNodePart(part);
            }
        }
    }
    
    private boolean checkRelationshipVirtual(RelationshipPattern rel, NodePattern startNode,
                                             NodePattern endNode, boolean isFirst, boolean isLast,
                                             DetectionResult result) {
        List<String> virtualTypes = new ArrayList<>();
        List<String> physicalTypes = new ArrayList<>();
        
        for (String relType : rel.getRelationshipTypes()) {
            if (metadataQueryService.isVirtualEdge(relType)) {
                virtualTypes.add(relType);
            } else {
                physicalTypes.add(relType);
            }
        }
        
        for (String relType : virtualTypes) {
            VirtualEdgeBinding binding = metadataQueryService.getVirtualEdgeBinding(relType).orElse(null);
            
            if (binding != null) {
                if (binding.isFirstHopOnly() && !isFirst) {
                    result.addError("Virtual edge '" + relType + "' can only be used as first hop");
                }
                if (binding.isLastHopOnly() && !isLast) {
                    result.addError("Virtual edge '" + relType + "' can only be used as last hop");
                }
            }
            
            VirtualEdgePart part = new VirtualEdgePart();
            part.setEdgeType(relType);
            part.setVariable(rel.getVariable());
            part.setStartNode(startNode);
            part.setEndNode(endNode);
            part.setDirection(rel.getDirection());
            part.setProperties(rel.getProperties());
            part.setFirstHop(isFirst);
            part.setLastHop(isLast);
            if (binding != null) {
                part.setDataSource(binding.getTargetDataSource());
                part.setOperator(binding.getOperatorName());
                part.setIdMapping(binding.getIdMapping());
            }
            result.addVirtualEdgePart(part);
        }
        
        if (!physicalTypes.isEmpty()) {
            PhysicalEdgePart part = new PhysicalEdgePart();
            part.setEdgeTypes(physicalTypes);
            part.setVariable(rel.getVariable());
            part.setStartNode(startNode);
            part.setEndNode(endNode);
            part.setDirection(rel.getDirection());
            part.setProperties(rel.getProperties());
            result.addPhysicalEdgePart(part);
        }

        return !virtualTypes.isEmpty();
    }

    private void validatePatternConstraints(Pattern.PatternElement element, List<Boolean> edgeKindsInOrder, DetectionResult result) {
        List<NodePattern> nodeOrder = new ArrayList<>();
        if (element.getNodePattern() != null) {
            nodeOrder.add(element.getNodePattern());
        }
        for (Pattern.PatternElementChain chain : element.getChains()) {
            nodeOrder.add(chain.getNodePattern());
        }

        if (edgeKindsInOrder.size() == 1 && edgeKindsInOrder.get(0)) {
            result.addError("Single-hop virtual edges are not supported");
        }

        if (!nodeOrder.isEmpty() && nodeOrder.size() == 1 && isVirtualNode(nodeOrder.get(0))) {
            result.addError("Pure virtual node patterns are not supported");
        }

        for (int i = 0; i < edgeKindsInOrder.size(); i++) {
            if (!edgeKindsInOrder.get(i)) {
                continue;
            }

            NodePattern leftNode = i < nodeOrder.size() ? nodeOrder.get(i) : null;
            NodePattern rightNode = i + 1 < nodeOrder.size() ? nodeOrder.get(i + 1) : null;
            if (isVirtualNode(leftNode) && isVirtualNode(rightNode)) {
                result.addError("Virtual-to-virtual patterns are not supported");
            }
        }

        for (int i = 1; i < edgeKindsInOrder.size() - 1; i++) {
            if (edgeKindsInOrder.get(i) && !edgeKindsInOrder.get(i - 1) && !edgeKindsInOrder.get(i + 1)) {
                result.addError("Invalid pattern: [physical]->[virtual]->[physical] sandwich structure is not allowed");
            }
        }
    }

    private boolean isVirtualNode(NodePattern nodePattern) {
        if (nodePattern == null || nodePattern.getLabels().isEmpty()) {
            return false;
        }
        for (String label : nodePattern.getLabels()) {
            if (metadataQueryService.isVirtualLabel(label)) {
                return true;
            }
        }
        return false;
    }
    
    public static class DetectionResult {
        private List<PhysicalNodePart> physicalNodeParts = new ArrayList<>();
        private List<PhysicalEdgePart> physicalEdgeParts = new ArrayList<>();
        private List<VirtualNodePart> virtualNodeParts = new ArrayList<>();
        private List<VirtualEdgePart> virtualEdgeParts = new ArrayList<>();
        private List<String> errors = new ArrayList<>();
        
        public List<PhysicalNodePart> getPhysicalNodeParts() {
            return physicalNodeParts;
        }
        
        public void addPhysicalNodePart(PhysicalNodePart part) {
            this.physicalNodeParts.add(part);
        }
        
        public List<PhysicalEdgePart> getPhysicalEdgeParts() {
            return physicalEdgeParts;
        }
        
        public void addPhysicalEdgePart(PhysicalEdgePart part) {
            this.physicalEdgeParts.add(part);
        }
        
        public List<VirtualNodePart> getVirtualNodeParts() {
            return virtualNodeParts;
        }
        
        public void addVirtualNodePart(VirtualNodePart part) {
            this.virtualNodeParts.add(part);
        }
        
        public List<VirtualEdgePart> getVirtualEdgeParts() {
            return virtualEdgeParts;
        }
        
        public void addVirtualEdgePart(VirtualEdgePart part) {
            this.virtualEdgeParts.add(part);
        }
        
        public List<String> getErrors() {
            return errors;
        }
        
        public void addError(String error) {
            this.errors.add(error);
        }
        
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
        
        public boolean hasVirtualElements() {
            return !virtualNodeParts.isEmpty() || !virtualEdgeParts.isEmpty();
        }
    }
    
    public static class PhysicalNodePart {
        private String variable;
        private List<String> labels = new ArrayList<>();
        private java.util.Map<String, Object> properties;
        
        public String getVariable() { return variable; }
        public void setVariable(String variable) { this.variable = variable; }
        public List<String> getLabels() { return labels; }
        public void setLabels(List<String> labels) { this.labels = labels; }
        public java.util.Map<String, Object> getProperties() { return properties; }
        public void setProperties(java.util.Map<String, Object> properties) { this.properties = properties; }
    }
    
    public static class PhysicalEdgePart {
        private List<String> edgeTypes = new ArrayList<>();
        private String variable;
        private NodePattern startNode;
        private NodePattern endNode;
        private RelationshipPattern.Direction direction;
        private java.util.Map<String, Object> properties;
        
        public List<String> getEdgeTypes() { return edgeTypes; }
        public void setEdgeTypes(List<String> edgeTypes) { this.edgeTypes = edgeTypes; }
        public String getVariable() { return variable; }
        public void setVariable(String variable) { this.variable = variable; }
        public NodePattern getStartNode() { return startNode; }
        public void setStartNode(NodePattern startNode) { this.startNode = startNode; }
        public NodePattern getEndNode() { return endNode; }
        public void setEndNode(NodePattern endNode) { this.endNode = endNode; }
        public RelationshipPattern.Direction getDirection() { return direction; }
        public void setDirection(RelationshipPattern.Direction direction) { this.direction = direction; }
        public java.util.Map<String, Object> getProperties() { return properties; }
        public void setProperties(java.util.Map<String, Object> properties) { this.properties = properties; }
    }
    
    public static class VirtualNodePart {
        private String variable;
        private String label;
        private java.util.Map<String, Object> properties;
        private String dataSource;
        
        public String getVariable() { return variable; }
        public void setVariable(String variable) { this.variable = variable; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public java.util.Map<String, Object> getProperties() { return properties; }
        public void setProperties(java.util.Map<String, Object> properties) { this.properties = properties; }
        public String getDataSource() { return dataSource; }
        public void setDataSource(String dataSource) { this.dataSource = dataSource; }
    }
    
    public static class VirtualEdgePart {
        private String edgeType;
        private String variable;
        private NodePattern startNode;
        private NodePattern endNode;
        private RelationshipPattern.Direction direction;
        private java.util.Map<String, Object> properties;
        private String dataSource;
        private String operator;
        private boolean isFirstHop;
        private boolean isLastHop;
        private java.util.Map<String, String> idMapping;
        
        public String getEdgeType() { return edgeType; }
        public void setEdgeType(String edgeType) { this.edgeType = edgeType; }
        public String getVariable() { return variable; }
        public void setVariable(String variable) { this.variable = variable; }
        public NodePattern getStartNode() { return startNode; }
        public void setStartNode(NodePattern startNode) { this.startNode = startNode; }
        public NodePattern getEndNode() { return endNode; }
        public void setEndNode(NodePattern endNode) { this.endNode = endNode; }
        public RelationshipPattern.Direction getDirection() { return direction; }
        public void setDirection(RelationshipPattern.Direction direction) { this.direction = direction; }
        public java.util.Map<String, Object> getProperties() { return properties; }
        public void setProperties(java.util.Map<String, Object> properties) { this.properties = properties; }
        public String getDataSource() { return dataSource; }
        public void setDataSource(String dataSource) { this.dataSource = dataSource; }
        public String getOperator() { return operator; }
        public void setOperator(String operator) { this.operator = operator; }
        public boolean isFirstHop() { return isFirstHop; }
        public void setFirstHop(boolean firstHop) { isFirstHop = firstHop; }
        public boolean isLastHop() { return isLastHop; }
        public void setLastHop(boolean lastHop) { isLastHop = lastHop; }
        public java.util.Map<String, String> getIdMapping() { return idMapping; }
        public void setIdMapping(java.util.Map<String, String> idMapping) { this.idMapping = idMapping; }
    }
}
