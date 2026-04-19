package com.fangyang.metadata;

import java.util.Optional;

public interface MetadataQueryService {
    Optional<DataSourceMetadata> getDataSource(String name);
    Optional<VirtualEdgeBinding> getVirtualEdgeBinding(String edgeType);
    Optional<LabelMetadata> getLabel(String label);
    boolean isVirtualEdge(String edgeType);
    boolean isVirtualLabel(String label);
    String getDataSourceForEdge(String edgeType);
    String getDataSourceForLabel(String label);
    String getTargetLabelForEdge(String edgeType);
}
