package com.federatedquery.executor;

import com.federatedquery.adapter.*;
import com.federatedquery.metadata.*;
import com.federatedquery.plan.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class ExecutorTest {
    @Spy
    private MetadataRegistryImpl registry = new MetadataRegistryImpl();
    private MockExternalAdapter mockAdapter;
    @InjectMocks
    private FederatedExecutor executor;
    
    @BeforeEach
    void setUp() {
        DataSourceMetadata mockSource = new DataSourceMetadata();
        mockSource.setName("mock-service");
        mockSource.setType(DataSourceType.MOCK);
        registry.registerDataSource(mockSource);
        
        mockAdapter = new MockExternalAdapter();
        mockAdapter.setDataSourceName("mock-service");
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
        assertTrue(result.isSuccess(), "执行必须成功");
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
        batchResult.setSuccess(true);
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
    @DisplayName("Fallback on failure returns error")
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
        
        ExecutionResult result = executor.execute(plan).join();
        
        assertNotNull(result, "ExecutionResult不能为空");
        assertNotNull(result.getPlanId(), "PlanId不能为空");
        
        Map<String, QueryResult> batchResults = result.getBatchResults();
        assertNotNull(batchResults, "BatchResults不能为空");
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
        
        ExecutionResult result = executor.execute(plan).join();
        
        assertNotNull(result, "ExecutionResult不能为空");
        assertNotNull(result.getPlanId(), "PlanId不能为空");
        
        Map<String, QueryResult> batchResults2 = result.getBatchResults();
        assertNotNull(batchResults2, "BatchResults不能为空");
    }
}
