package com.fangyang.metadata;

public interface MetadataRegistrar {
    void registerDataSource(DataSourceMetadata metadata);
    void registerVirtualEdge(VirtualEdgeBinding binding);
    void registerLabel(LabelMetadata label);
    void clear();
}
