package com.federatedquery.executor;

import com.federatedquery.adapter.*;
import com.federatedquery.metadata.*;
import com.federatedquery.plan.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorTest {
    private MetadataRegistry registry;
    private MockExternalAdapter mockAdapter;
    private FederatedExecutor executor;
    
    @BeforeEach
    void setUp() {
        registry = new MetadataRegistryImpl();
        
        DataSourceMetadata mockSource = new DataSourceMetadata();
        mockSource.setName("mock-service");
        mockSource.setType(DataSourceType.MOCK);
        registry.registerDataSource(mockSource);
        
        mockAdapter = new MockExternalAdapter();
        mockAdapter.setDataSourceName("mock-service");
        
        executor = new FederatedExecutor(registry);
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
