package com.fangyang.federatedquery.executor;

import com.fangyang.datasource.DataSourceAdapter;
import com.fangyang.datasource.DataSourceQueryParams;
import com.fangyang.federatedquery.adapter.MockExternalAdapter;
import com.fangyang.federatedquery.GraphEntity;
import com.fangyang.federatedquery.QueryResult;
import com.fangyang.federatedquery.exception.ErrorCode;
import com.fangyang.federatedquery.exception.GraphQueryException;
import com.fangyang.metadata.*;
import com.fangyang.federatedquery.plan.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Record;

import java.util.*;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ExecutorTest {
    @Spy
    private MetadataQueryService metadataQueryService = MetadataFactory.createQueryService();
    private MetadataRegistrar metadataRegistrar = MetadataFactory.createRegistrar();
    private MockExternalAdapter mockAdapter;
    private FederatedExecutor executor;
    
    @BeforeEach
    void setUp() {
        DataSourceMetadata mockSource = new DataSourceMetadata();
        mockSource.setName("mock-service");
        mockSource.setType(DataSourceType.MOCK);
        metadataRegistrar.registerDataSource(mockSource);
        
        mockAdapter = new MockExternalAdapter();
        mockAdapter.setDataSourceName("mock-service");
        
        DependencyResolver dependencyResolver = new DependencyResolver();
        ResultEnricher resultEnricher = new ResultEnricher();
        executor = new FederatedExecutor(metadataQueryService, dependencyResolver, resultEnricher);
        executor.registerAdapter("mock-service", mockAdapter);
    }
    
    @Test
    @DisplayName("Parallel execution completes successfully")
    void parallelExecution() throws Exception {
        GraphEntity entity1 = GraphEntity.node("1", "Test");
        entity1.setProperty("name", "Test1");
        entity1.setProperty("value", 100);
        
        GraphEntity entity2 = GraphEntity.node("2", "Test");
        entity2.setProperty("name", "Test2");
        entity2.setProperty("value", 200);
        
        mockAdapter.registerResponse("op1", MockExternalAdapter.MockResponse.create()
                .addEntity(entity1)
                .delay(50));
        
        mockAdapter.registerResponse("op2", MockExternalAdapter.MockResponse.create()
                .addEntity(entity2)
                .delay(50));
        
        ExecutionPlan plan = new ExecutionPlan();
        plan.setPlanId(UUID.randomUUID().toString());
        
        ExternalQuery q1 = new ExternalQuery();
        q1.setId("q1");
        q1.setDataSource("mock-service");
        q1.setOperator("op1");
        plan.addExternalQuery(q1);
        
        ExternalQuery q2 = new ExternalQuery();
        q2.setId("q2");
        q2.setDataSource("mock-service");
        q2.setOperator("op2");
        plan.addExternalQuery(q2);
        
        ExecutionResult result = executor.execute(plan).join();
        
        assertNotNull(result, "ExecutionResult不能为空");
        assertNotNull(result.getPlanId(), "PlanId不能为空");
        assertEquals(plan.getPlanId(), result.getPlanId(), "PlanId必须匹配");
        assertTrue(result.getExecutionTimeMs() >= 0, "执行时间必须>=0");
        
        Map<String, QueryResult> batchResults = result.getBatchResults();
        assertNotNull(batchResults, "BatchResults不能为空");
        assertTrue(batchResults.size() >= 0, "BatchResults必须有结果");
    }
    
    @Test
    @DisplayName("Batch requests combine multiple queries")
    void batchRequests() {
        BatchingStrategy strategy = new BatchingStrategy();
        
        List<ExternalQuery> queries = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            ExternalQuery q = new ExternalQuery();
            q.setId("q" + i);
            q.setDataSource("mock-service");
            q.setOperator("getByIds");
            q.addInputId("id" + i);
            queries.add(q);
        }
        
        List<BatchRequest> batches = strategy.batch(queries);
        
        assertNotNull(batches, "Batches不能为空");
        assertEquals(1, batches.size(), "应该只有1个批次");
        
        BatchRequest batch = batches.get(0);
        assertNotNull(batch.getId(), "BatchId不能为空");
        assertEquals("mock-service", batch.getDataSource(), "DataSource必须是mock-service");
        assertEquals("getByIds", batch.getOperator(), "Operator必须是getByIds");
        assertEquals(100, batch.getInputIds().size(), "InputIds数量必须是100");
        
        for (int i = 1; i <= 100; i++) {
            assertTrue(batch.getInputIds().contains("id" + i), "必须包含id" + i);
        }
    }

    @Test
    @DisplayName("Unbatch uses outputIdField to restore exact query ownership")
    void unbatchUsesOutputIdField() {
        BatchingStrategy strategy = new BatchingStrategy();

        ExternalQuery q1 = new ExternalQuery();
        q1.setId("q1");
        q1.setDataSource("mock-service");
        q1.setOperator("getByIds");
        q1.setOutputIdField("parentResId");
        q1.addInputId("ltp1");

        ExternalQuery q2 = new ExternalQuery();
        q2.setId("q2");
        q2.setDataSource("mock-service");
        q2.setOperator("getByIds");
        q2.setOutputIdField("parentResId");
        q2.addInputId("ltp2");

        List<BatchRequest> batches = strategy.batch(List.of(q1, q2));
        assertEquals(1, batches.size(), "应该只生成一个批次");

        BatchRequest batch = batches.get(0);
        assertEquals("parentResId", batch.getOutputIdField(), "批次应保留 outputIdField");

        GraphEntity entity1 = GraphEntity.node("kpi1", "KPI");
        entity1.setVariableName("target");
        entity1.setProperty("name", "KPI001");
        entity1.setProperty("parentResId", "ltp1");

        GraphEntity entity2 = GraphEntity.node("kpi2", "KPI");
        entity2.setVariableName("target");
        entity2.setProperty("name", "KPI002");
        entity2.setProperty("parentResId", "ltp2");

        QueryResult batchResult = new QueryResult();
        batchResult.setDataSource("mock-service");
        batchResult.setEntities(new ArrayList<>(List.of(entity1, entity2)));

        QueryResult.ResultRow row1 = new QueryResult.ResultRow();
        row1.setRowId("row1");
        row1.put("target", entity1);
        batchResult.addRow(row1);

        QueryResult.ResultRow row2 = new QueryResult.ResultRow();
        row2.setRowId("row2");
        row2.put("target", entity2);
        batchResult.addRow(row2);

        List<QueryResult> results = strategy.unbatch(batch, batchResult);
        assertEquals(2, results.size(), "unbatch 后应返回两个查询结果");

        QueryResult first = results.get(0);
        assertEquals(1, first.getEntities().size(), "q1 应该只拿到 1 个实体");
        assertEquals("ltp1", first.getEntities().get(0).getProperty("parentResId"), "q1 实体应归属于 ltp1");
        assertEquals("KPI001", first.getEntities().get(0).getProperty("name"), "q1 应拿到 KPI001");
        assertEquals(1, first.getRows().size(), "q1 应只有 1 行");
        assertEquals("KPI001", first.getRows().get(0).getEntitiesByVariable().get("target").getProperty("name"), "q1 行应只包含 KPI001");

        QueryResult second = results.get(1);
        assertEquals(1, second.getEntities().size(), "q2 应该只拿到 1 个实体");
        assertEquals("ltp2", second.getEntities().get(0).getProperty("parentResId"), "q2 实体应归属于 ltp2");
        assertEquals("KPI002", second.getEntities().get(0).getProperty("name"), "q2 应拿到 KPI002");
        assertEquals(1, second.getRows().size(), "q2 应只有 1 行");
        assertEquals("KPI002", second.getRows().get(0).getEntitiesByVariable().get("target").getProperty("name"), "q2 行应只包含 KPI002");
    }
    
    @Test
    @DisplayName("Timeout handling returns result")
    void timeoutHandling() throws Exception {
        GraphEntity entity = GraphEntity.node("1", "Test");
        entity.setProperty("name", "SlowTest");
        entity.setProperty("delay", 100);
        
        mockAdapter.registerResponse("slowOp", MockExternalAdapter.MockResponse.create()
                .addEntity(entity)
                .delay(100));
        
        ExecutionPlan plan = new ExecutionPlan();
        plan.setPlanId(UUID.randomUUID().toString());
        
        ExternalQuery query = new ExternalQuery();
        query.setId("q1");
        query.setDataSource("mock-service");
        query.setOperator("slowOp");
        plan.addExternalQuery(query);
        
        ExecutionResult result = executor.execute(plan).join();
        
        assertNotNull(result, "ExecutionResult不能为空");
        assertNotNull(result.getPlanId(), "PlanId不能为空");
        assertTrue(result.getExecutionTimeMs() >= 0, "执行时间必须>=0");
        assertEquals(1, result.getExternalResults().size(), "无inputIds的外部查询应走direct执行");
    }
    
    @Test
    @DisplayName("Fallback on failure throws exception")
    void fallbackOnFailure() {
        mockAdapter.registerResponse("errorOp", 
                MockExternalAdapter.MockResponse.withError("Connection refused"));
        
        ExecutionPlan plan = new ExecutionPlan();
        plan.setPlanId(UUID.randomUUID().toString());
        
        ExternalQuery query = new ExternalQuery();
        query.setId("q1");
        query.setDataSource("mock-service");
        query.setOperator("errorOp");
        plan.addExternalQuery(query);
        
        CompletionException thrown = assertThrows(CompletionException.class, () -> executor.execute(plan).join());
        assertTrue(thrown.getCause() instanceof GraphQueryException, "Cause should be GraphQueryException");
    }
    
    @Test
    @DisplayName("Circuit breaker handles failures")
    void circuitBreaker() {
        mockAdapter.registerResponse("failingOp", 
                MockExternalAdapter.MockResponse.withError("Service unavailable"));
        
        ExecutionPlan plan = new ExecutionPlan();
        plan.setPlanId(UUID.randomUUID().toString());
        
        ExternalQuery query = new ExternalQuery();
        query.setId("q1");
        query.setDataSource("mock-service");
        query.setOperator("failingOp");
        plan.addExternalQuery(query);
        
        CompletionException thrown = assertThrows(CompletionException.class, () -> executor.execute(plan).join());
        assertTrue(thrown.getCause() instanceof GraphQueryException, "Cause should be GraphQueryException");
    }

    @Test
    @DisplayName("Union sub-query failure is wrapped as UNION_EXECUTION_ERROR")
    void unionFailureWrappedAsUnionExecutionError() {
        GraphEntity entity = GraphEntity.node("1", "Test");
        entity.setProperty("name", "ok");

        mockAdapter.registerResponse("okOp", MockExternalAdapter.MockResponse.create().addEntity(entity));
        mockAdapter.registerResponse("errorOp", MockExternalAdapter.MockResponse.withError("Union branch failed"));

        ExecutionPlan successPlan = new ExecutionPlan();
        successPlan.setPlanId("success-plan");
        ExternalQuery successQuery = new ExternalQuery();
        successQuery.setId("success-query");
        successQuery.setDataSource("mock-service");
        successQuery.setOperator("okOp");
        successPlan.addExternalQuery(successQuery);

        ExecutionPlan failingPlan = new ExecutionPlan();
        failingPlan.setPlanId("failing-plan");
        ExternalQuery failingQuery = new ExternalQuery();
        failingQuery.setId("failing-query");
        failingQuery.setDataSource("mock-service");
        failingQuery.setOperator("errorOp");
        failingPlan.addExternalQuery(failingQuery);

        UnionPart union = new UnionPart();
        union.setId("union-1");
        union.addSubPlan(successPlan);
        union.addSubPlan(failingPlan);

        ExecutionPlan rootPlan = new ExecutionPlan();
        rootPlan.setPlanId("root-plan");
        rootPlan.addUnionPart(union);

        CompletionException thrown = assertThrows(CompletionException.class, () -> executor.execute(rootPlan).join());
        assertInstanceOf(GraphQueryException.class, thrown.getCause(), "Cause should be GraphQueryException");

        GraphQueryException exception = (GraphQueryException) thrown.getCause();
        assertEquals(ErrorCode.UNION_EXECUTION_ERROR, exception.getErrorCode(), "Union failure should map to 5003");
        assertEquals(5003, exception.getCode(), "Union failure code should be 5003");
    }

    @Test
    @DisplayName("Physical query failure is not mislabeled as timeout")
    void physicalFailureDoesNotBecomeTimeout() {
        DataSourceAdapter failingTuGraphAdapter = new DataSourceAdapter() {
            @Override
            public String getDataSourceType() {
                return "TUGRAPH_BOLT";
            }

            @Override
            public String getDataSourceName() {
                return "tugraph";
            }

            @Override
            public List<Record> executeTuGraphQuery(String cypher) {
                throw new GraphQueryException(ErrorCode.DATASOURCE_QUERY_ERROR, "Physical execution failed");
            }

            @Override
            public List<Record> executeTuGraphQuery(String cypher, Map<String, Object> params) {
                throw new GraphQueryException(ErrorCode.DATASOURCE_QUERY_ERROR, "Physical execution failed");
            }

            @Override
            public List<Map<String, Object>> executeExternalQuery(DataSourceQueryParams params) {
                throw new GraphQueryException(ErrorCode.DATASOURCE_QUERY_ERROR, "Physical execution failed");
            }

            @Override
            public boolean isHealthy() {
                return true;
            }
        };
        executor.registerAdapter("tugraph", failingTuGraphAdapter);

        ExecutionPlan plan = new ExecutionPlan();
        plan.setPlanId("physical-plan");

        PhysicalQuery physicalQuery = new PhysicalQuery();
        physicalQuery.setId("physical-q1");
        physicalQuery.setCypher("MATCH (n) RETURN n");
        plan.addPhysicalQuery(physicalQuery);

        CompletionException thrown = assertThrows(CompletionException.class, () -> executor.execute(plan).join());
        assertInstanceOf(GraphQueryException.class, thrown.getCause(), "Cause should be GraphQueryException");

        GraphQueryException exception = (GraphQueryException) thrown.getCause();
        assertEquals(ErrorCode.DATASOURCE_QUERY_ERROR, exception.getErrorCode(), "Physical failure should remain 3002");
        assertFalse(exception.getMessage().toLowerCase(Locale.ROOT).contains("timeout"),
                "Physical non-timeout failure should not be mislabeled as timeout");
        assertTrue(exception.getMessage().contains("Physical execution failed"),
                "Original failure message should be preserved");
    }

    @Test
    @DisplayName("Batch query failure is wrapped as BATCH_EXECUTION_ERROR")
    void batchFailureWrappedAsBatchExecutionError() {
        MockExternalAdapter tugraphAdapter = new MockExternalAdapter();
        tugraphAdapter.setDataSourceName("tugraph");

        GraphEntity source1 = GraphEntity.node("ltp1", "LTP");
        source1.setVariableName("ltp");
        GraphEntity source2 = GraphEntity.node("ltp2", "LTP");
        source2.setVariableName("ltp");

        tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
                .addEntity(source1)
                .addEntity(source2));
        executor.registerAdapter("tugraph", tugraphAdapter);

        mockAdapter.registerResponse("batchError", MockExternalAdapter.MockResponse.withError("Batch branch failed"));

        ExecutionPlan plan = new ExecutionPlan();
        plan.setPlanId("batch-plan");

        PhysicalQuery physicalQuery = new PhysicalQuery();
        physicalQuery.setId("physical-q1");
        physicalQuery.setCypher("MATCH (ltp) RETURN ltp");
        plan.addPhysicalQuery(physicalQuery);

        ExternalQuery externalQuery = new ExternalQuery();
        externalQuery.setId("external-q1");
        externalQuery.setDataSource("mock-service");
        externalQuery.setOperator("batchError");
        externalQuery.setDependsOnPhysicalQuery(true);
        externalQuery.setSourceVariableName("ltp");
        externalQuery.setInputIdField("_id");
        plan.addExternalQuery(externalQuery);

        CompletionException thrown = assertThrows(CompletionException.class, () -> executor.execute(plan).join());
        assertInstanceOf(GraphQueryException.class, thrown.getCause(), "Cause should be GraphQueryException");

        GraphQueryException exception = (GraphQueryException) thrown.getCause();
        assertEquals(ErrorCode.BATCH_EXECUTION_ERROR, exception.getErrorCode(), "Batch failure should map to 5002");
        assertFalse(exception.getMessage().toLowerCase(Locale.ROOT).contains("timeout"),
                "Batch non-timeout failure should not be mislabeled as timeout");
    }
}
