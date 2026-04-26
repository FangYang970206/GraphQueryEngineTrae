package com.fangyang.federatedquery.executor;

import com.fangyang.datasource.DataSourceAdapter;
import com.fangyang.federatedquery.model.QueryResult;
import com.fangyang.federatedquery.plan.ExecutionPlan;
import com.fangyang.federatedquery.plan.ExternalQuery;
import com.fangyang.federatedquery.plan.PhysicalQuery;
import com.fangyang.federatedquery.plan.UnionPart;
import com.fangyang.metadata.MetadataQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class FederatedExecutor {
    private static final Logger log = LoggerFactory.getLogger(FederatedExecutor.class);

    @Autowired
    private MetadataQueryService metadataQueryService;
    @Autowired
    private DependencyResolver dependencyResolver;
    @Autowired
    private ResultEnricher resultEnricher;

    private final AdapterRegistry adapterRegistry;
    private final ExecutionRuntime executionRuntime;
    private final BatchingStrategy batchingStrategy;
    private final QueryResultAssembler queryResultAssembler;
    private final QueryExecutionGateway queryExecutionGateway;
    private final UnionQueryExecutor unionQueryExecutor;

    public FederatedExecutor() {
        this.adapterRegistry = new AdapterRegistry();
        this.executionRuntime = new ExecutionRuntime();
        this.batchingStrategy = new BatchingStrategy();
        this.dependencyResolver = new DependencyResolver();
        this.resultEnricher = new ResultEnricher();
        this.queryResultAssembler = new QueryResultAssembler(() -> metadataQueryService, () -> resultEnricher);
        this.queryExecutionGateway = new QueryExecutionGateway(
                adapterRegistry,
                executionRuntime,
                () -> metadataQueryService,
                new QueryParamBuilder(),
                queryResultAssembler
        );
        this.unionQueryExecutor = new UnionQueryExecutor(queryResultAssembler);
    }

    public FederatedExecutor(
            MetadataQueryService metadataQueryService,
            DependencyResolver dependencyResolver,
            ResultEnricher resultEnricher) {
        this();
        this.metadataQueryService = metadataQueryService;
        this.dependencyResolver = dependencyResolver;
        this.resultEnricher = resultEnricher;
    }

    public void setTimeoutMs(long timeoutMs) {
        executionRuntime.setTimeoutMs(timeoutMs);
    }

    public long getTimeoutMs() {
        return executionRuntime.getTimeoutMs();
    }

    public void registerAdapter(String name, DataSourceAdapter adapter) {
        adapterRegistry.register(name, adapter);
    }

    public CompletableFuture<ExecutionResult> execute(ExecutionPlan plan) {
        ExecutionResult result = initializeResult(plan);
        List<ExternalQuery> independentQueries = extractIndependentQueries(plan);
        List<ExternalQuery> dependentQueries = extractDependentQueries(plan);

        if (dependentQueries.isEmpty()) {
            return executeAllInParallel(plan, result, plan.getPhysicalQueries(), independentQueries);
        }

        return executeWithDependencyAwareness(
                plan,
                result,
                plan.getPhysicalQueries(),
                independentQueries,
                dependentQueries
        );
    }

    private CompletableFuture<ExecutionResult> executeAllInParallel(
            ExecutionPlan plan,
            ExecutionResult result,
            List<PhysicalQuery> physicalQueries,
            List<ExternalQuery> independentQueries) {
        List<CompletableFuture<QueryResult>> futures = new ArrayList<>();
        futures.addAll(schedulePhysicalQueries(physicalQueries, result));
        futures.addAll(scheduleIndependentQueries(independentQueries, result));
        futures.addAll(scheduleUnionQueries(plan.getUnionParts(), result));
        return allOf(futures).thenApplyAsync(ignored -> markExecutionSuccess(result), executionRuntime.executor());
    }

    private CompletableFuture<ExecutionResult> executeWithDependencyAwareness(
            ExecutionPlan plan,
            ExecutionResult result,
            List<PhysicalQuery> physicalQueries,
            List<ExternalQuery> independentQueries,
            List<ExternalQuery> dependentQueries) {
        List<CompletableFuture<QueryResult>> physicalFutures = schedulePhysicalQueries(physicalQueries, result);
        List<CompletableFuture<QueryResult>> independentFutures = scheduleIndependentQueries(independentQueries, result);

        return allOf(physicalFutures)
                .thenComposeAsync(ignored -> executeDependentQueries(dependentQueries, independentFutures, result),
                        executionRuntime.executor())
                .thenComposeAsync(ignored -> allOf(scheduleUnionQueries(plan.getUnionParts(), result)),
                        executionRuntime.executor())
                .thenApplyAsync(ignored -> markExecutionSuccess(result), executionRuntime.executor());
    }

    private CompletableFuture<Void> executeDependentQueries(
            List<ExternalQuery> dependentQueries,
            List<CompletableFuture<QueryResult>> independentFutures,
            ExecutionResult result) {
        log.debug("Physical queries completed, extracting IDs for dependent queries");

        Map<String, Map<String, Set<String>>> dataByVariable =
                dependencyResolver.extractIdsAndPropertiesFromPhysicalResults(result);
        dependencyResolver.populateDependentQueryInputIds(dependentQueries, dataByVariable);

        DependencyResolver.DependencyClassification classification =
                dependencyResolver.classifyDependentQueries(dependentQueries);

        for (ExternalQuery query : classification.getNotReadyQueries()) {
            log.debug("External query {} is not ready to execute - no input IDs available, returning empty result",
                    query.getId());
            result.addExternalResult(new QueryResult());
        }

        List<CompletableFuture<QueryResult>> dependentFutures = new ArrayList<>();
        for (ExternalQuery query : classification.getDirectQueries()) {
            dependentFutures.add(scheduleDependentQuery(query, result));
        }
        if (!classification.getBatchQueries().isEmpty()) {
            for (BatchRequest batch : batchingStrategy.batch(classification.getBatchQueries())) {
                dependentFutures.add(scheduleBatchQuery(batch, result));
            }
        }

        return CompletableFuture.allOf(allOf(dependentFutures), allOf(independentFutures));
    }

    private List<CompletableFuture<QueryResult>> schedulePhysicalQueries(
            List<PhysicalQuery> physicalQueries,
            ExecutionResult result) {
        List<CompletableFuture<QueryResult>> futures = new ArrayList<>();
        for (PhysicalQuery query : physicalQueries) {
            futures.add(queryExecutionGateway.executePhysical(query).thenApplyAsync(queryResult -> {
                result.addPhysicalResult(query.getId(), queryResult);
                return queryResult;
            }, executionRuntime.executor()));
        }
        return futures;
    }

    private List<CompletableFuture<QueryResult>> scheduleIndependentQueries(
            List<ExternalQuery> independentQueries,
            ExecutionResult result) {
        List<CompletableFuture<QueryResult>> futures = new ArrayList<>();
        for (ExternalQuery query : independentQueries) {
            futures.add(queryExecutionGateway.executeExternal(query).thenApplyAsync(queryResult -> {
                result.addExternalResult(queryResult);
                return queryResult;
            }, executionRuntime.executor()));
        }
        return futures;
    }

    private List<CompletableFuture<QueryResult>> scheduleUnionQueries(
            List<UnionPart> unionParts,
            ExecutionResult result) {
        List<CompletableFuture<QueryResult>> futures = new ArrayList<>();
        for (UnionPart union : unionParts) {
            futures.add(unionQueryExecutor.execute(union, this::execute).thenApplyAsync(queryResult -> {
                result.addUnionResult(union.getId(), queryResult);
                return queryResult;
            }, executionRuntime.executor()));
        }
        return futures;
    }

    private CompletableFuture<QueryResult> scheduleDependentQuery(ExternalQuery query, ExecutionResult result) {
        return queryExecutionGateway.executeExternal(query).thenApplyAsync(queryResult -> {
            QueryResult enriched = queryResultAssembler.enrichDependentResult(query, queryResult, result);
            result.addExternalResult(enriched);
            return enriched;
        }, executionRuntime.executor());
    }

    private CompletableFuture<QueryResult> scheduleBatchQuery(BatchRequest batch, ExecutionResult result) {
        return queryExecutionGateway.executeBatch(batch).thenApplyAsync(batchResult -> {
            result.addBatchResult(batch.getId(), batchResult);
            for (QueryResult currentResult :
                    queryResultAssembler.unbatchAndEnrich(batch, batchResult, result, batchingStrategy)) {
                result.addExternalResult(currentResult);
            }
            return batchResult;
        }, executionRuntime.executor());
    }

    private ExecutionResult initializeResult(ExecutionPlan plan) {
        ExecutionResult result = new ExecutionResult();
        result.setPlanId(plan.getPlanId());
        return result;
    }

    private List<ExternalQuery> extractIndependentQueries(ExecutionPlan plan) {
        return plan.getExternalQueries().stream()
                .filter(query -> !query.isDependsOnPhysicalQuery())
                .collect(Collectors.toList());
    }

    private List<ExternalQuery> extractDependentQueries(ExecutionPlan plan) {
        return plan.getExternalQueries().stream()
                .filter(ExternalQuery::isDependsOnPhysicalQuery)
                .collect(Collectors.toList());
    }

    private CompletableFuture<Void> allOf(List<? extends CompletableFuture<?>> futures) {
        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private ExecutionResult markExecutionSuccess(ExecutionResult result) {
        result.setExecutionTimeMs(System.currentTimeMillis() - result.getStartTime());
        return result;
    }

    public void shutdown() {
        executionRuntime.shutdown();
    }
}
