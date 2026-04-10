package com.federatedquery.metadata;

import java.util.Optional;

public interface MetadataRegistry {
    void registerDataSource(DataSourceMetadata metadata);
    void registerVirtualEdge(VirtualEdgeBinding binding);
    void registerLabel(LabelMetadata label);
    
    Optional<DataSourceMetadata> getDataSource(String name);
    Optional<VirtualEdgeBinding> getVirtualEdgeBinding(String edgeType);
    Optional<LabelMetadata> getLabel(String label);
    
    boolean isVirtualEdge(String edgeType);
    boolean isVirtualLabel(String label);
    String getDataSourceForEdge(String edgeType);
    String getDataSourceForLabel(String label);
    String getTargetLabelForEdge(String edgeType);
    
    void clear();
}
