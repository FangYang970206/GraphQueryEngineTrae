package com.federatedquery.sdk;

import com.federatedquery.adapter.GraphEntity;
import com.federatedquery.adapter.QueryResult;
import com.federatedquery.connector.TuGraphConfig;
import com.federatedquery.connector.TuGraphConnector;
import com.federatedquery.connector.TuGraphConnectorImpl;
import com.federatedquery.executor.FederatedExecutor;
import com.federatedquery.parser.CypherParserFacade;
import com.federatedquery.rewriter.QueryRewriter;
import com.federatedquery.aggregator.UnionDeduplicator;
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
        parser = new CypherParserFacade();
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
        String cypher = "CREATE (n:Person) SET n = $props RETURN n";
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
