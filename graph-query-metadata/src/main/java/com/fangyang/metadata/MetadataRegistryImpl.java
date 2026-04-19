package com.fangyang.metadata;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Component
class MetadataRegistryImpl implements MetadataRegistrar, MetadataQueryService {
    private final Cache<String, DataSourceMetadata> dataSourceCache;
    private final Cache<String, VirtualEdgeBinding> virtualEdgeCache;
    private final Cache<String, LabelMetadata> labelCache;
    
    MetadataRegistryImpl() {
        Caffeine<Object, Object> caffeine = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .recordStats();
        
        this.dataSourceCache = caffeine.build();
        this.virtualEdgeCache = caffeine.build();
        this.labelCache = caffeine.build();
    }
    
    @Override
    public void registerDataSource(DataSourceMetadata metadata) {
        dataSourceCache.put(metadata.getName(), metadata);
    }
    
    @Override
    public void registerVirtualEdge(VirtualEdgeBinding binding) {
        virtualEdgeCache.put(binding.getEdgeType(), binding);
    }
    
    @Override
    public void registerLabel(LabelMetadata label) {
        labelCache.put(label.getLabel(), label);
    }
    
    @Override
    public Optional<DataSourceMetadata> getDataSource(String name) {
        return Optional.ofNullable(dataSourceCache.getIfPresent(name));
    }
    
    @Override
    public Optional<VirtualEdgeBinding> getVirtualEdgeBinding(String edgeType) {
        return Optional.ofNullable(virtualEdgeCache.getIfPresent(edgeType));
    }
    
    @Override
    public Optional<LabelMetadata> getLabel(String label) {
        return Optional.ofNullable(labelCache.getIfPresent(label));
    }
    
    @Override
    public boolean isVirtualEdge(String edgeType) {
        return virtualEdgeCache.getIfPresent(edgeType) != null;
    }
    
    @Override
    public boolean isVirtualLabel(String label) {
        LabelMetadata metadata = labelCache.getIfPresent(label);
        return metadata != null && metadata.isVirtual();
    }
    
    @Override
    public String getDataSourceForEdge(String edgeType) {
        VirtualEdgeBinding binding = virtualEdgeCache.getIfPresent(edgeType);
        return binding != null ? binding.getTargetDataSource() : null;
    }
    
    @Override
    public String getDataSourceForLabel(String label) {
        LabelMetadata metadata = labelCache.getIfPresent(label);
        return metadata != null ? metadata.getDataSource() : null;
    }
    
    @Override
    public String getTargetLabelForEdge(String edgeType) {
        VirtualEdgeBinding binding = virtualEdgeCache.getIfPresent(edgeType);
        return binding != null ? binding.getTargetLabel() : null;
    }
    
    @Override
    public void clear() {
        dataSourceCache.invalidateAll();
        virtualEdgeCache.invalidateAll();
        labelCache.invalidateAll();
    }
    
    Cache<String, DataSourceMetadata> getDataSourceCache() {
        return dataSourceCache;
    }
    
    Cache<String, VirtualEdgeBinding> getVirtualEdgeCache() {
        return virtualEdgeCache;
    }
    
    Cache<String, LabelMetadata> getLabelCache() {
        return labelCache;
    }
}
