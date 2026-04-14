package com.federatedquery.rewriter;

import com.federatedquery.ast.*;
import com.federatedquery.metadata.MetadataRegistry;
import com.federatedquery.metadata.VirtualEdgeBinding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class VirtualEdgeDetector {
    @Autowired
    private MetadataRegistry registry;

    public VirtualEdgeDetector(MetadataRegistry registry) {
        this.registry = registry;
    }
    
    public DetectionResult detect(Pattern pattern) {
        DetectionResult result = new DetectionResult();
        
        for (Pattern.PatternPart part : pattern.getPatternParts()) {
            detectInPatternPart(part, result);
        }
        
        validateConstraints(result);
        
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
        for (int i = 0; i < chains.size(); i++) {
            Pattern.PatternElementChain chain = chains.get(i);
            RelationshipPattern rel = chain.getRelationshipPattern();
            NodePattern endNode = chain.getNodePattern();
            
            boolean isFirst = (i == 0);
            boolean isLast = (i == chains.size() - 1);
            
            checkRelationshipVirtual(rel, startNode, endNode, isFirst, isLast, result);
            
            checkNodeVirtual(endNode, result);
            
            startNode = endNode;
        }
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
            if (registry.isVirtualLabel(label)) {
                VirtualNodePart part = new VirtualNodePart();
                part.setVariable(node.getVariable());
                part.setLabel(label);
                part.setProperties(node.getProperties());
                part.setDataSource(registry.getDataSourceForLabel(label));
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
    
    private void checkRelationshipVirtual(RelationshipPattern rel, NodePattern startNode, 
                                          NodePattern endNode, boolean isFirst, boolean isLast,
                                          DetectionResult result) {
        List<String> virtualTypes = new ArrayList<>();
        List<String> physicalTypes = new ArrayList<>();
        
        for (String relType : rel.getRelationshipTypes()) {
            if (registry.isVirtualEdge(relType)) {
                virtualTypes.add(relType);
            } else {
                physicalTypes.add(relType);
            }
        }
        
        for (String relType : virtualTypes) {
            VirtualEdgeBinding binding = registry.getVirtualEdgeBinding(relType).orElse(null);
            
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
    }
    
    private void validateConstraints(DetectionResult result) {
        List<EdgePart> allEdges = new ArrayList<>();
        for (PhysicalEdgePart p : result.getPhysicalEdgeParts()) {
            allEdges.add(new EdgePart(p, false));
        }
        for (VirtualEdgePart v : result.getVirtualEdgeParts()) {
            allEdges.add(new EdgePart(v, true));
        }
        
        boolean foundPhysical = false;
        boolean foundVirtual = false;
        boolean foundPhysicalAfterVirtual = false;
        
        for (EdgePart edge : allEdges) {
            if (edge.isVirtual) {
                if (foundPhysical && foundPhysicalAfterVirtual) {
                    result.addError("Invalid pattern: [physical]->[virtual]->[physical] sandwich structure is not allowed");
                    break;
                }
                foundVirtual = true;
            } else {
                if (foundVirtual) {
                    foundPhysicalAfterVirtual = true;
                }
                foundPhysical = true;
            }
        }
    }
    
    private static class EdgePart {
        final Object part;
        final boolean isVirtual;
        
        EdgePart(Object part, boolean isVirtual) {
            this.part = part;
            this.isVirtual = isVirtual;
        }
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
