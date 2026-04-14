package com.federatedquery.testutil;

import com.federatedquery.adapter.MockExternalAdapter;
import com.federatedquery.aggregator.UnionDeduplicator;
import com.federatedquery.connector.TuGraphConnector;
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

public final class GraphQueryMetaFactory {
    private final MetadataRegistry registry = new MetadataRegistryImpl();
    private final Map<String, MockExternalAdapter> adapters = new LinkedHashMap<>();

    public GraphQueryMetaFactory registerDataSource(String name, DataSourceType type) {
        return registerDataSource(name, type, null);
    }

    public GraphQueryMetaFactory registerDataSource(String name, DataSourceType type, String endpoint) {
        registerDataSource(registry, name, type, endpoint);
        return this;
    }

    public GraphQueryMetaFactory registerLabel(String label, boolean virtual, String dataSource) {
        registerLabel(registry, label, virtual, dataSource);
        return this;
    }

    public GraphQueryMetaFactory registerVirtualEdge(
            String edgeType,
            String targetDataSource,
            String operatorName,
            Consumer<VirtualEdgeBinding> customizer) {
        registerVirtualEdge(registry, edgeType, targetDataSource, operatorName, customizer);
        return this;
    }

    public GraphQueryMetaFactory registerSimpleVirtualEdge(
            String edgeType,
            String targetDataSource,
            String operatorName) {
        return registerVirtualEdge(edgeType, targetDataSource, operatorName, binding -> {
            binding.setLastHopOnly(true);
        });
    }

    public MockExternalAdapter createAdapter(String dataSourceName) {
        MockExternalAdapter adapter = new MockExternalAdapter();
        adapter.setDataSourceName(dataSourceName);
        adapters.put(dataSourceName, adapter);
        return adapter;
    }

    public MetadataRegistry registry() {
        return registry;
    }

    public GraphQuerySDK createSdk() {
        CypherParserFacade parser = new CypherParserFacade(new CypherASTVisitor());
        VirtualEdgeDetector detector = new VirtualEdgeDetector(registry);
        QueryRewriter rewriter = new QueryRewriter(registry, detector, new WhereConditionPushdown(registry));
        FederatedExecutor executor = new FederatedExecutor(registry);
        adapters.forEach(executor::registerAdapter);
        return new GraphQuerySDK(
                parser,
                rewriter,
                executor,
                new UnionDeduplicator());
    }

    public GraphQuerySDK createSdk(TuGraphConnector connector) {
        CypherParserFacade parser = new CypherParserFacade(new CypherASTVisitor());
        VirtualEdgeDetector detector = new VirtualEdgeDetector(registry);
        QueryRewriter rewriter = new QueryRewriter(registry, detector, new WhereConditionPushdown(registry));
        FederatedExecutor executor = new FederatedExecutor(registry);
        adapters.forEach(executor::registerAdapter);
        return new GraphQuerySDK(
                parser,
                rewriter,
                executor,
                new UnionDeduplicator(),
                connector);
    }

    public FederatedExecutor createExecutor() {
        FederatedExecutor executor = new FederatedExecutor(registry);
        adapters.forEach(executor::registerAdapter);
        return executor;
    }

    public QueryRewriter createRewriter() {
        return new QueryRewriter(registry, new VirtualEdgeDetector(registry), new WhereConditionPushdown(registry));
    }

    public CypherParserFacade createParser() {
        return new CypherParserFacade(new CypherASTVisitor());
    }

    public static GraphQueryMetaFactory create() {
        return new GraphQueryMetaFactory();
    }

    public static void registerDataSource(MetadataRegistry registry, String name, DataSourceType type, String endpoint) {
        DataSourceMetadata dataSource = new DataSourceMetadata();
        dataSource.setName(name);
        dataSource.setType(type);
        if (endpoint != null) {
            dataSource.setEndpoint(endpoint);
        }
        registry.registerDataSource(dataSource);
    }

    public static void registerLabel(MetadataRegistry registry, String label, boolean virtual, String dataSource) {
        LabelMetadata labelMetadata = new LabelMetadata();
        labelMetadata.setLabel(label);
        labelMetadata.setVirtual(virtual);
        labelMetadata.setDataSource(dataSource);
        registry.registerLabel(labelMetadata);
    }

    public static void registerVirtualEdge(
            MetadataRegistry registry,
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
    }

    public static void registerSimpleVirtualEdge(
            MetadataRegistry registry,
            String edgeType,
            String targetDataSource,
            String operatorName) {
        registerVirtualEdge(registry, edgeType, targetDataSource, operatorName, binding -> {
            binding.setLastHopOnly(true);
        });
    }

    public static void populateStandardMetadata(MetadataRegistry registry) {
        registerDataSource(registry, "tugraph", DataSourceType.TUGRAPH_BOLT, "bolt://127.0.0.1:7687");
        registerDataSource(registry, "kpi-service", DataSourceType.REST_API, null);
        registerDataSource(registry, "alarm-service", DataSourceType.REST_API, null);

        registerLabel(registry, "NetworkElement", false, "tugraph");
        registerLabel(registry, "LTP", false, "tugraph");
        registerLabel(registry, "KPI", true, "kpi-service");
        registerLabel(registry, "Alarm", true, "alarm-service");

        registerSimpleVirtualEdge(registry, "NEHasKPI", "kpi-service", "getKPIByNeIds");
        registerSimpleVirtualEdge(registry, "NEHasAlarms", "alarm-service", "getAlarmsByNeIds");
    }

    public static GraphQueryMetaFactory createStandard() {
        return create()
                .registerDataSource("tugraph", DataSourceType.TUGRAPH_BOLT, "bolt://127.0.0.1:7687")
                .registerDataSource("kpi-service", DataSourceType.REST_API)
                .registerDataSource("alarm-service", DataSourceType.REST_API)
                .registerLabel("NetworkElement", false, "tugraph")
                .registerLabel("LTP", false, "tugraph")
                .registerLabel("KPI", true, "kpi-service")
                .registerLabel("Alarm", true, "alarm-service")
                .registerSimpleVirtualEdge("NEHasKPI", "kpi-service", "getKPIByNeIds")
                .registerSimpleVirtualEdge("NEHasAlarms", "alarm-service", "getAlarmsByNeIds");
    }

    public static GraphQueryMetaFactory createWithDruidZenith() {
        return create()
                .registerDataSource("tugraph", DataSourceType.TUGRAPH_BOLT, "bolt://127.0.0.1:7687")
                .registerDataSource("druid-service", DataSourceType.REST_API, "http://localhost:18080")
                .registerDataSource("zenith-service", DataSourceType.REST_API, "http://localhost:18080")
                .registerLabel("NetworkElement", false, "tugraph")
                .registerLabel("LTP", false, "tugraph")
                .registerLabel("KPI", true, "druid-service")
                .registerLabel("KPI2", true, "druid-service")
                .registerLabel("ALARM", true, "zenith-service")
                .registerVirtualEdge("NEHasKPI", "druid-service", "queryKpiByParentResId", binding -> {
                    binding.setTargetLabel("KPI");
                    binding.getIdMapping().put("resId", "parentResId");
                    binding.setLastHopOnly(true);
                })
                .registerVirtualEdge("NEHasAlarms", "zenith-service", "queryAlarmsByMedn", binding -> {
                    binding.setTargetLabel("ALARM");
                    binding.getIdMapping().put("DN", "MEDN");
                    binding.setLastHopOnly(true);
                })
                .registerVirtualEdge("LTPHasKPI2", "druid-service", "queryKpi2ByParentResId", binding -> {
                    binding.setTargetLabel("KPI2");
                    binding.getIdMapping().put("resId", "parentResId");
                    binding.setLastHopOnly(true);
                });
    }
}
