package com.fangyang.federatedquery.testutil;

import com.fangyang.federatedquery.adapter.MockExternalAdapter;
import com.fangyang.federatedquery.aggregator.UnionDeduplicator;
import com.fangyang.datasource.TuGraphConnector;
import com.fangyang.federatedquery.executor.DependencyResolver;
import com.fangyang.federatedquery.executor.FederatedExecutor;
import com.fangyang.federatedquery.executor.ResultEnricher;
import com.fangyang.metadata.DataSourceMetadata;
import com.fangyang.metadata.DataSourceType;
import com.fangyang.metadata.LabelMetadata;
import com.fangyang.metadata.MetadataQueryService;
import com.fangyang.metadata.MetadataRegistrar;
import com.fangyang.metadata.MetadataFactory;
import com.fangyang.metadata.VirtualEdgeBinding;
import com.fangyang.federatedquery.parser.CypherASTVisitor;
import com.fangyang.federatedquery.parser.CypherParserFacade;
import com.fangyang.federatedquery.reliability.WhereConditionPushdown;
import com.fangyang.federatedquery.rewriter.MixedPatternRewriter;
import com.fangyang.federatedquery.rewriter.PhysicalQueryBuilder;
import com.fangyang.federatedquery.rewriter.QueryRewriter;
import com.fangyang.federatedquery.rewriter.VirtualEdgeDetector;
import com.fangyang.federatedquery.sdk.GraphQuerySDK;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class GraphQueryMetaFactory {
    private final MetadataQueryService metadataQueryService = MetadataFactory.createQueryService();
    private final MetadataRegistrar metadataRegistrar = MetadataFactory.createRegistrar();
    private final Map<String, MockExternalAdapter> adapters = new LinkedHashMap<>();

    public GraphQueryMetaFactory registerDataSource(String name, DataSourceType type) {
        return registerDataSource(name, type, null);
    }

    public GraphQueryMetaFactory registerDataSource(String name, DataSourceType type, String endpoint) {
        DataSourceMetadata dataSource = new DataSourceMetadata();
        dataSource.setName(name);
        dataSource.setType(type);
        if (endpoint != null) {
            dataSource.setEndpoint(endpoint);
        }
        metadataRegistrar.registerDataSource(dataSource);
        return this;
    }

    public GraphQueryMetaFactory registerLabel(String label, boolean virtual, String dataSource) {
        LabelMetadata labelMetadata = new LabelMetadata();
        labelMetadata.setLabel(label);
        labelMetadata.setVirtual(virtual);
        labelMetadata.setDataSource(dataSource);
        metadataRegistrar.registerLabel(labelMetadata);
        return this;
    }

    public GraphQueryMetaFactory registerVirtualEdge(
            String edgeType,
            String targetDataSource,
            String operatorName,
            Consumer<VirtualEdgeBinding> customizer) {
        VirtualEdgeBinding binding = new VirtualEdgeBinding();
        binding.setEdgeType(edgeType);
        binding.setTargetDataSource(targetDataSource);
        binding.setOperatorName(operatorName);
        customizer.accept(binding);
        metadataRegistrar.registerVirtualEdge(binding);
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

    public MetadataQueryService metadataQueryService() {
        return metadataQueryService;
    }

    public GraphQuerySDK createSdk() {
        CypherParserFacade parser = new CypherParserFacade(new CypherASTVisitor());
        VirtualEdgeDetector detector = new VirtualEdgeDetector(metadataQueryService);
        PhysicalQueryBuilder physicalQueryBuilder = new PhysicalQueryBuilder();
        MixedPatternRewriter mixedPatternRewriter = new MixedPatternRewriter(metadataQueryService, physicalQueryBuilder);
        QueryRewriter rewriter = new QueryRewriter(metadataQueryService, detector, new WhereConditionPushdown(metadataQueryService), physicalQueryBuilder, mixedPatternRewriter);
        DependencyResolver dependencyResolver = new DependencyResolver();
        ResultEnricher resultEnricher = new ResultEnricher();
        FederatedExecutor executor = new FederatedExecutor(metadataQueryService, dependencyResolver, resultEnricher);
        adapters.forEach(executor::registerAdapter);
        return new GraphQuerySDK(
                parser,
                rewriter,
                executor,
                new UnionDeduplicator());
    }

    public GraphQuerySDK createSdk(TuGraphConnector connector) {
        CypherParserFacade parser = new CypherParserFacade(new CypherASTVisitor());
        VirtualEdgeDetector detector = new VirtualEdgeDetector(metadataQueryService);
        PhysicalQueryBuilder physicalQueryBuilder = new PhysicalQueryBuilder();
        MixedPatternRewriter mixedPatternRewriter = new MixedPatternRewriter(metadataQueryService, physicalQueryBuilder);
        QueryRewriter rewriter = new QueryRewriter(metadataQueryService, detector, new WhereConditionPushdown(metadataQueryService), physicalQueryBuilder, mixedPatternRewriter);
        DependencyResolver dependencyResolver = new DependencyResolver();
        ResultEnricher resultEnricher = new ResultEnricher();
        FederatedExecutor executor = new FederatedExecutor(metadataQueryService, dependencyResolver, resultEnricher);
        adapters.forEach(executor::registerAdapter);
        return new GraphQuerySDK(
                parser,
                rewriter,
                executor,
                new UnionDeduplicator(),
                connector);
    }

    public FederatedExecutor createExecutor() {
        DependencyResolver dependencyResolver = new DependencyResolver();
        ResultEnricher resultEnricher = new ResultEnricher();
        FederatedExecutor executor = new FederatedExecutor(metadataQueryService, dependencyResolver, resultEnricher);
        adapters.forEach(executor::registerAdapter);
        return executor;
    }

    public QueryRewriter createRewriter() {
        VirtualEdgeDetector detector = new VirtualEdgeDetector(metadataQueryService);
        PhysicalQueryBuilder physicalQueryBuilder = new PhysicalQueryBuilder();
        MixedPatternRewriter mixedPatternRewriter = new MixedPatternRewriter(metadataQueryService, physicalQueryBuilder);
        return new QueryRewriter(metadataQueryService, detector, new WhereConditionPushdown(metadataQueryService), physicalQueryBuilder, mixedPatternRewriter);
    }

    public CypherParserFacade createParser() {
        return new CypherParserFacade(new CypherASTVisitor());
    }

    public static GraphQueryMetaFactory create() {
        return new GraphQueryMetaFactory();
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
