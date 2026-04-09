package com.federatedquery.connector;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIf;
import org.neo4j.driver.Record;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TuGraph连接器测试")
class TuGraphConnectorTest {
    
    private static TuGraphConnector connector;
    private static boolean tuGraphAvailable = false;
    
    @BeforeAll
    static void setUp() {
        TuGraphConfig config = TuGraphConfig.defaultConfig();
        try {
            connector = new TuGraphConnectorImpl(config);
            tuGraphAvailable = connector.isConnected();
        } catch (Exception e) {
            tuGraphAvailable = false;
            System.out.println("TuGraph not available, skipping integration tests: " + e.getMessage());
        }
    }
    
    @AfterAll
    static void tearDown() {
        if (connector != null) {
            connector.close();
        }
    }
    
    @Test
    @DisplayName("连接配置测试")
    void testConfig() {
        TuGraphConfig config = TuGraphConfig.defaultConfig();
        assertEquals("bolt://127.0.0.1:7687", config.getUri(), "默认URI应该是bolt://127.0.0.1:7687");
        assertEquals("admin", config.getUsername(), "默认用户名应该是admin");
        assertEquals("73@TuGraph", config.getPassword(), "默认密码应该是73@TuGraph");
        assertEquals("default", config.getGraphName(), "默认图名称应该是default");
    }
    
    @Test
    @DisplayName("自定义配置测试")
    void testCustomConfig() {
        TuGraphConfig config = new TuGraphConfig("bolt://localhost:7687", "user", "pass", "myGraph");
        config.setMaxConnectionPoolSize(100);
        config.setConnectionTimeoutMs(60000);
        
        assertEquals("bolt://localhost:7687", config.getUri());
        assertEquals("user", config.getUsername());
        assertEquals("pass", config.getPassword());
        assertEquals("myGraph", config.getGraphName());
        assertEquals(100, config.getMaxConnectionPoolSize());
        assertEquals(60000, config.getConnectionTimeoutMs());
    }
    
    @Test
    @DisplayName("TuGraph连接状态测试")
    @DisabledIf("isTuGraphNotAvailable")
    void testConnection() {
        assertTrue(connector.isConnected(), "连接应该成功");
    }
    
    @Test
    @DisplayName("简单查询测试 - RETURN 1")
    @DisabledIf("isTuGraphNotAvailable")
    void testSimpleQuery() {
        List<Record> records = connector.executeQuery("RETURN 1 AS value");
        
        assertNotNull(records, "结果不能为空");
        assertEquals(1, records.size(), "应该返回1条记录");
        
        Record record = records.get(0);
        assertEquals(1, record.get("value").asInt(), "返回值应该是1");
    }
    
    @Test
    @DisplayName("参数化查询测试")
    @DisabledIf("isTuGraphNotAvailable")
    void testParameterizedQuery() {
        List<Record> records = connector.executeQuery(
            "RETURN $param AS value",
            "param", 42
        );
        
        assertNotNull(records, "结果不能为空");
        assertEquals(1, records.size(), "应该返回1条记录");
        assertEquals(42, records.get(0).get("value").asInt(), "返回值应该是42");
    }
    
    @Test
    @DisplayName("多参数查询测试")
    @DisabledIf("isTuGraphNotAvailable")
    void testMultipleParameterQuery() {
        List<Record> records = connector.executeQuery(
            "RETURN $a + $b AS sum",
            "a", 10, "b", 20
        );
        
        assertNotNull(records, "结果不能为空");
        assertEquals(1, records.size(), "应该返回1条记录");
        assertEquals(30, records.get(0).get("sum").asInt(), "返回值应该是30");
    }
    
    @Test
    @DisplayName("连接关闭测试")
    void testClose() {
        TuGraphConfig config = TuGraphConfig.defaultConfig();
        TuGraphConnector testConnector = new TuGraphConnectorImpl(config);
        
        testConnector.close();
        assertFalse(testConnector.isConnected(), "关闭后连接应该不可用");
    }
    
    static boolean isTuGraphNotAvailable() {
        return !tuGraphAvailable;
    }
}
