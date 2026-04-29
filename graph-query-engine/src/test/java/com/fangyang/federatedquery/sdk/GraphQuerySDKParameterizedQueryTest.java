package com.fangyang.federatedquery.sdk;

import com.fangyang.datasource.TuGraphConnector;
import com.fangyang.federatedquery.executor.FederatedExecutor;
import com.fangyang.federatedquery.exception.ErrorCode;
import com.fangyang.federatedquery.parser.CypherParserFacade;
import com.fangyang.federatedquery.parser.CypherASTVisitor;
import com.fangyang.federatedquery.exception.GraphQueryException;
import com.fangyang.federatedquery.plan.ExecutionPlan;
import com.fangyang.federatedquery.rewriter.QueryRewriter;
import com.fangyang.federatedquery.aggregator.UnionDeduplicator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GraphQuerySDKParameterizedQueryTest {
    
    private GraphQuerySDK sdk;
    private TuGraphConnector mockConnector;
    private CypherParserFacade parser;
    private QueryRewriter rewriter;
    private FederatedExecutor executor;
    private UnionDeduplicator deduplicator;
    
    @BeforeEach
    void setUp() {
        parser = new CypherParserFacade(new CypherASTVisitor());
        rewriter = mock(QueryRewriter.class);
        executor = mock(FederatedExecutor.class);
        deduplicator = new UnionDeduplicator();
        mockConnector = mock(TuGraphConnector.class);
        
        when(mockConnector.isConnected()).thenReturn(true);
        
        sdk = new GraphQuerySDK(parser, rewriter, executor, deduplicator, mockConnector);
    }
    
    @Test
    @DisplayName("测试参数化查询 - 字符串参数")
    void testParameterizedQueryWithStringParameter() {
        String cypher = "MATCH (n:Person) WHERE n.name = $name RETURN n";
        Map<String, Object> params = new HashMap<>();
        params.put("name", "张三");
        
        List<Record> mockRecords = createMockRecords("n", "Person", 
                Map.of("name", "张三", "age", 25));
        
        when(mockConnector.executeQuery(eq(cypher), any(Object[].class)))
                .thenReturn(mockRecords);
        
        List<Record> result = sdk.executeWithConnector(cypher, "name", "张三");
        
        assertNotNull(result);
        assertEquals(1, result.size());
        
        verify(mockConnector).executeQuery(eq(cypher), eq("name"), eq("张三"));
    }
    
    @Test
    @DisplayName("测试参数化查询 - 多个参数")
    void testParameterizedQueryWithMultipleParameters() {
        String cypher = "MATCH (n:Person) WHERE n.name = $name AND n.age > $age RETURN n";
        Map<String, Object> params = new HashMap<>();
        params.put("name", "李四");
        params.put("age", 30);
        
        List<Record> mockRecords = createMockRecords("n", "Person", 
                Map.of("name", "李四", "age", 35));
        
        when(mockConnector.executeQuery(eq(cypher), any(Object[].class)))
                .thenReturn(mockRecords);
        
        List<Record> result = sdk.executeWithConnector(cypher, "name", "李四", "age", 30);
        
        assertNotNull(result);
        assertEquals(1, result.size());
        
        verify(mockConnector).executeQuery(eq(cypher), eq("name"), eq("李四"), eq("age"), eq(30));
    }
    
    @Test
    @DisplayName("测试参数化查询 - 特殊字符参数")
    void testParameterizedQueryWithSpecialCharacters() {
        String cypher = "MATCH (n:Person) WHERE n.name = $name RETURN n";
        String specialName = "O'Brien's \"Special\" Name\\Test";
        
        List<Record> mockRecords = createMockRecords("n", "Person", 
                Map.of("name", specialName));
        
        when(mockConnector.executeQuery(eq(cypher), any(Object[].class)))
                .thenReturn(mockRecords);
        
        List<Record> result = sdk.executeWithConnector(cypher, "name", specialName);
        
        assertNotNull(result);
        assertEquals(1, result.size());
        
        verify(mockConnector).executeQuery(eq(cypher), eq("name"), eq(specialName));
    }
    
    @Test
    @DisplayName("测试参数化查询 - 集合参数")
    void testParameterizedQueryWithCollectionParameter() {
        String cypher = "MATCH (n:Person) WHERE n.name IN $names RETURN n";
        List<String> names = Arrays.asList("张三", "李四", "王五");
        
        List<Record> mockRecords = new ArrayList<>();
        for (String name : names) {
            mockRecords.addAll(createMockRecords("n", "Person", Map.of("name", name)));
        }
        
        when(mockConnector.executeQuery(eq(cypher), any(Object[].class)))
                .thenReturn(mockRecords);
        
        List<Record> result = sdk.executeWithConnector(cypher, "names", names);
        
        assertNotNull(result);
        assertEquals(3, result.size());
        
        verify(mockConnector).executeQuery(eq(cypher), eq("names"), eq(names));
    }
    
    @Test
    @DisplayName("测试参数化查询 - Map参数")
    void testParameterizedQueryWithMapParameter() {
        String cypher = "MATCH (n:Person) WHERE n.metadata = $props RETURN n";
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", "赵六");
        props.put("age", 28);
        props.put("city", "北京");
        
        List<Record> mockRecords = createMockRecords("n", "Person", props);
        
        when(mockConnector.executeQuery(eq(cypher), any(Object[].class)))
                .thenReturn(mockRecords);
        
        List<Record> result = sdk.executeWithConnector(cypher, "props", props);
        
        assertNotNull(result);
        assertEquals(1, result.size());
        
        verify(mockConnector).executeQuery(eq(cypher), eq("props"), eq(props));
    }
    
    @Test
    @DisplayName("测试参数化查询 - 参数名冲突测试")
    void testParameterizedNameConflict() {
        String cypher = "MATCH (n) WHERE n.name = $name AND n.fullname = $fullname RETURN n";
        Map<String, Object> params = new HashMap<>();
        params.put("name", "张");
        params.put("fullname", "张三");
        
        List<Record> mockRecords = createMockRecords("n", "Person", 
                Map.of("name", "张", "fullname", "张三"));
        
        when(mockConnector.executeQuery(eq(cypher), any(Object[].class)))
                .thenReturn(mockRecords);
        
        List<Record> result = sdk.executeWithConnector(cypher, "name", "张", "fullname", "张三");
        
        assertNotNull(result);
        assertEquals(1, result.size());
        
        verify(mockConnector).executeQuery(eq(cypher), eq("name"), eq("张"), eq("fullname"), eq("张三"));
    }

    @Test
    @DisplayName("direct execution 按查询中参数出现顺序传参")
    void executeUsesDirectExecutionParameterOrder() {
        String cypher = "MATCH (n:Person) WHERE n.age > $age AND n.name = $name RETURN n";
        Map<String, Object> params = new HashMap<>();
        params.put("name", "李四");
        params.put("age", 30);
        List<Record> mockRecords = createMockRecords("n", "Person", Map.of("name", "李四", "age", 35));

        when(rewriter.rewrite(any())).thenReturn(createDirectExecutionPlan());
        when(mockConnector.executeQuery(eq(cypher), any(Object[].class)))
                .thenReturn(mockRecords);

        sdk.executeRecords(cypher, params);

        verify(mockConnector).executeQuery(eq(cypher), eq(30), eq("李四"));
    }

    @Test
    @DisplayName("direct execution 保留重复参数出现顺序")
    void executeUsesRepeatedDirectExecutionParameters() {
        String cypher = "MATCH (n:Person) WHERE n.name = $name OR n.alias = $name RETURN n";
        Map<String, Object> params = Map.of("name", "张三");
        List<Record> mockRecords = createMockRecords("n", "Person", Map.of("name", "张三"));

        when(rewriter.rewrite(any())).thenReturn(createDirectExecutionPlan());
        when(mockConnector.executeQuery(eq(cypher), any(Object[].class)))
                .thenReturn(mockRecords);

        sdk.execute(cypher, params);

        verify(mockConnector).executeQuery(eq(cypher), eq("张三"), eq("张三"));
    }

    @Test
    @DisplayName("direct execution 缺失参数时抛出异常")
    void executeFailsWhenDirectExecutionParameterMissing() {
        String cypher = "MATCH (n:Person) WHERE n.name = $name RETURN n";

        when(rewriter.rewrite(any())).thenReturn(createDirectExecutionPlan());

        GraphQueryException thrown = assertThrows(GraphQueryException.class,
                () -> sdk.executeRecords(cypher, Collections.emptyMap()));
        assertEquals(ErrorCode.QUERY_EXECUTION_ERROR, thrown.getErrorCode());
        assertNotNull(thrown.getMessage());
        verify(mockConnector, never()).executeQuery(eq(cypher), any(Object[].class));
    }

    @Test
    @DisplayName("direct execution 对纯物理查询应用默认 5000 limit")
    void executeAppliesImplicitPhysicalLimit() {
        String cypher = "MATCH (n:Person) RETURN n";
        ExecutionPlan plan = createDirectExecutionPlan();
        plan.getGlobalContext().setHasImplicitLimit(true);
        plan.getGlobalContext().setImplicitLimit(5000);
        List<Record> mockRecords = createManyMockRecords("n", "Person", 6000);

        when(rewriter.rewrite(any())).thenReturn(plan);
        when(mockConnector.executeQuery(eq(cypher))).thenReturn(mockRecords);

        List<Map<String, Object>> results = sdk.executeRecords(cypher);

        assertEquals(5000, results.size(), "纯物理查询应被默认 limit 截断到 5000");
    }

    @Test
    @DisplayName("final result size is capped at 8000")
    void executeCapsResultSizeAtEightThousand() {
        String cypher = "MATCH (n:Person) RETURN n LIMIT 9000";
        ExecutionPlan plan = createDirectExecutionPlan();
        plan.getGlobalContext().setMaxResultSize(8000);
        com.fangyang.federatedquery.plan.GlobalContext.LimitSpec limitSpec =
                new com.fangyang.federatedquery.plan.GlobalContext.LimitSpec();
        limitSpec.setLimit(9000);
        plan.getGlobalContext().setGlobalLimit(limitSpec);
        List<Record> mockRecords = createManyMockRecords("n", "Person", 9001);

        when(rewriter.rewrite(any())).thenReturn(plan);
        when(mockConnector.executeQuery(eq(cypher))).thenReturn(mockRecords);

        List<Map<String, Object>> results = sdk.executeRecords(cypher);

        assertEquals(8000, results.size(), "最终结果集不得超过 8000");
        assertEquals(8000, plan.getGlobalContext().getGlobalLimit().getLimit(), "过大的显式 LIMIT 应被收口到 8000");
    }

    private ExecutionPlan createDirectExecutionPlan() {
        ExecutionPlan plan = new ExecutionPlan();
        plan.setDirectExecution(true);
        plan.setHasVirtualElements(false);
        return plan;
    }

    private List<Record> createManyMockRecords(String varName, String label, int count) {
        List<Record> records = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            records.addAll(createMockRecords(varName, label, Map.of("name", "name-" + i)));
        }
        return records;
    }
    
    private List<Record> createMockRecords(String varName, String label, Map<String, Object> properties) {
        Record mockRecord = mock(Record.class);
        Node mockNode = mock(Node.class);
        Value mockValue = mock(Value.class);
        
        when(mockRecord.keys()).thenReturn(Collections.singletonList(varName));
        when(mockRecord.get(varName)).thenReturn(mockValue);
        when(mockValue.asNode()).thenReturn(mockNode);
        when(mockNode.labels()).thenReturn(Collections.singletonList(label));
        when(mockNode.keys()).thenReturn(new ArrayList<>(properties.keySet()));
        
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            Value propValue = mock(Value.class);
            when(propValue.asObject()).thenReturn(entry.getValue());
            when(mockNode.get(entry.getKey())).thenReturn(propValue);
        }
        
        return Collections.singletonList(mockRecord);
    }
}
