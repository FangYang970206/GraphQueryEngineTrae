package com.federatedquery.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.federatedquery.adapter.MockExternalAdapter;
import com.federatedquery.aggregator.GlobalSorter;
import com.federatedquery.aggregator.ResultStitcher;
import com.federatedquery.aggregator.UnionDeduplicator;
import com.federatedquery.executor.FederatedExecutor;
import com.federatedquery.metadata.DataSourceMetadata;
import com.federatedquery.metadata.DataSourceType;
import com.federatedquery.metadata.LabelMetadata;
import com.federatedquery.metadata.MetadataRegistry;
import com.federatedquery.metadata.MetadataRegistryImpl;
import com.federatedquery.metadata.VirtualEdgeBinding;
import com.federatedquery.parser.CypherASTVisitor;
import com.federatedquery.parser.CypherParserFacade;
import com.federatedquery.reliability.WhereConditionPushdown;
import com.federatedquery.rewriter.QueryRewriter;
import com.federatedquery.rewriter.VirtualEdgeDetector;
import com.federatedquery.sdk.GraphQuerySDK;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

final class FederatedE2ETestFixture {
    private final MetadataRegistry registry = new MetadataRegistryImpl();
    private final Map<String, MockExternalAdapter> adapters = new LinkedHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    FederatedE2ETestFixture registerDataSource(String name, DataSourceType type) {
        return registerDataSource(name, type, null);
    }

    FederatedE2ETestFixture registerDataSource(String name, DataSourceType type, String endpoint) {
        DataSourceMetadata dataSource = new DataSourceMetadata();
        dataSource.setName(name);
        dataSource.setType(type);
        if (endpoint != null) {
            dataSource.setEndpoint(endpoint);
        }
        registry.registerDataSource(dataSource);
        return this;
    }

    FederatedE2ETestFixture registerLabel(String label, boolean virtual, String dataSource) {
        LabelMetadata labelMetadata = new LabelMetadata();
        labelMetadata.setLabel(label);
        labelMetadata.setVirtual(virtual);
        labelMetadata.setDataSource(dataSource);
        registry.registerLabel(labelMetadata);
        return this;
    }

    FederatedE2ETestFixture registerVirtualEdge(
            String edgeType,
            String targetDataSource,
            String operatorName,
            Consumer<VirtualEdgeBinding> customizer) {
        VirtualEdgeBinding binding = new VirtualEdgeBinding();
        binding.setEdgeType(edgeType);
        binding.setTargetDataSource(targetDataSource);
        binding.setOperatorName(operatorName);
        customizer.accept(binding);
        registry.registerVirtualEdge(binding);
        return this;
    }

    MockExternalAdapter createAdapter(String dataSourceName) {
        MockExternalAdapter adapter = new MockExternalAdapter();
        adapter.setDataSourceName(dataSourceName);
        adapters.put(dataSourceName, adapter);
        return adapter;
    }

    MetadataRegistry registry() {
        return registry;
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }

    GraphQuerySDK createSdk() {
        CypherParserFacade parser = new CypherParserFacade(new CypherASTVisitor());
        VirtualEdgeDetector detector = new VirtualEdgeDetector(registry);
        QueryRewriter rewriter = new QueryRewriter(registry, detector, new WhereConditionPushdown(registry));
        FederatedExecutor executor = new FederatedExecutor(registry);
        adapters.forEach(executor::registerAdapter);
        return new GraphQuerySDK(
                parser,
                rewriter,
                executor,
                new ResultStitcher(),
                new GlobalSorter(),
                new UnionDeduplicator());
    }
}
