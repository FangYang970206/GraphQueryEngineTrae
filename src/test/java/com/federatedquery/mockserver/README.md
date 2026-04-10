# Mock HTTP Server for External Data Sources

用于模拟外部数据源（ALARM、KPI）的HTTP服务器，支持虚拟图查询测试。

## 功能特性

- ✅ 真实的HTTP服务器（基于Java内置HttpServer）
- ✅ 支持ALARM数据查询（`/rest/alarm/v1`）
- ✅ 支持KPI数据查询（`/rest/kpi/v1`）
- ✅ 支持时间条件过滤（latest、nearest、ffill策略）
- ✅ 支持投影列选择
- ✅ 支持GET和POST请求
- ✅ 返回JSON格式数据

## 快速开始

### 1. 在测试代码中使用

```java
import com.federatedquery.mockserver.MockHttpServer;
import org.junit.jupiter.api.*;

class MyTest {
    private MockHttpServer server;
    
    @BeforeEach
    void setUp() throws IOException {
        server = new MockHttpServer(18080); // 使用18080端口
        server.start();
    }
    
    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }
    
    @Test
    void testQuery() {
        String alarmUrl = server.getAlarmEndpoint(); // http://localhost:18080/rest/alarm/v1
        String kpiUrl = server.getKpiEndpoint();     // http://localhost:18080/rest/kpi/v1
        // 使用HTTP客户端查询...
    }
}
```

### 2. 作为独立服务运行

```bash
# 编译项目
mvn compile

# 运行服务器（默认端口8080）
mvn exec:java -Dexec.mainClass="com.federatedquery.mockserver.MockHttpServer"

# 或指定端口
mvn exec:java -Dexec.mainClass="com.federatedquery.mockserver.MockHttpServer" -Dexec.args="9090"
```

## API接口

### ALARM查询接口

**端点**: `/rest/alarm/v1`

**方法**: GET / POST

**参数**:
- `medn` (必需): 网元设备DN，用于关联ALARM数据
- `timestamp` (可选): 时间戳，用于时间条件过滤
- `strategy` (可选): 时间对齐策略，默认为`latest`
  - `latest` / `ffill`: 前向填充，返回时间戳之前的数据
  - `nearest`: 最近邻对齐，返回最接近时间戳的数据
- `fields` (可选): 投影字段列表，逗号分隔（如：`MENAME,OCCURTIME`）

**示例请求**:

```bash
# 查询NE001的所有告警
curl "http://localhost:8080/rest/alarm/v1?medn=388581df-50a3-4d9f-96d4-15faf36f9caf"

# 查询NE001的告警，带时间条件
curl "http://localhost:8080/rest/alarm/v1?medn=388581df-50a3-4d9f-96d4-15faf36f9caf&timestamp=1775825400000&strategy=latest"

# 查询NE001的告警，只返回指定字段
curl "http://localhost:8080/rest/alarm/v1?medn=388581df-50a3-4d9f-96d4-15faf36f9caf&fields=MENAME,OCCURTIME"
```

**响应示例**:

```json
[
  {
    "CSN": "0079bb0f-0e0a-44de-b595-cab5c22324ef",
    "MENAME": "ALARM001",
    "MEDN": "388581df-50a3-4d9f-96d4-15faf36f9caf",
    "OCCURTIME": 1775825151299,
    "CLEARTIME": 1775825951299
  },
  {
    "CSN": "afa87ad3-0834-467c-ba2e-1315fb9ba0cb",
    "MENAME": "ALARM002",
    "MEDN": "388581df-50a3-4d9f-96d4-15faf36f9caf",
    "OCCURTIME": 1775825251299,
    "CLEARTIME": 1775825551299
  }
]
```

### KPI查询接口

**端点**: `/rest/kpi/v1`

**方法**: GET / POST

**参数**:
- `parentResId` (必需): 父资源ID，用于关联KPI数据（支持KPI和KPI2）
- `timestamp` (可选): 时间戳，用于时间条件过滤
- `strategy` (可选): 时间对齐策略，默认为`latest`
- `fields` (可选): 投影字段列表，逗号分隔（如：`name,time`）

**示例请求**:

```bash
# 查询NE001的KPI数据
curl "http://localhost:8080/rest/kpi/v1?parentResId=eccc2c94-6a31-45ea-a16e-0c709939cbe5"

# 查询LTP001的KPI2数据
curl "http://localhost:8080/rest/kpi/v1?parentResId=db82ad76-8bdc-4f4b-96a0-a5e91c6861fb"

# 查询NE001的KPI数据，带时间条件和投影
curl "http://localhost:8080/rest/kpi/v1?parentResId=eccc2c94-6a31-45ea-a16e-0c709939cbe5&timestamp=1775825300000&strategy=latest&fields=name,time"
```

**响应示例**:

```json
[
  {
    "resId": "0079bb0f-0e0a-44de-b595-cab5c22324ef",
    "name": "KPI001",
    "parentResId": "eccc2c94-6a31-45ea-a16e-0c709939cbe5",
    "time": 1775825156755
  }
]
```

## 测试数据

服务器内置了以下测试数据（来自 `virtual_graph_case.md`）：

### ALARM数据

| CSN | MENAME | MEDN | OCCURTIME | CLEARTIME | 关联网元 |
|-----|--------|------|-----------|-----------|---------|
| 0079bb0f-... | ALARM001 | 388581df-... | 1775825151299 | 1775825951299 | NE001 |
| afa87ad3-... | ALARM002 | 388581df-... | 1775825251299 | 1775825551299 | NE001 |
| 0148b488-... | ALARM003 | a158b427-... | 1775825151299 | 1775825951299 | NE002 |

### KPI数据

| resId | name | parentResId | time | 关联实体 |
|-------|------|-------------|------|---------|
| 0079bb0f-... | KPI001 | eccc2c94-... | 1775825156755 | NE001 |
| 5af68cc1-... | KPI001 | 552dddad-... | 1775825856755 | NE002 |

### KPI2数据

| resId | name | parentResId | time | 关联实体 |
|-------|------|-------------|------|---------|
| 90d7f2d4-... | KPI2001 | db82ad76-... | 1775825256755 | LTP001 |
| 5ff8f0f4-... | KPI2002 | c889973b-... | 1775825556755 | LTP002 |
| 0036d5a5-... | KPI2003 | 537ae2db-... | 1775822856755 | LTP003 |
| d53fcfab-... | KPI2004 | 7c013137-... | 1775825951299 | LTP004 |

## 虚拟边映射关系

### NEHasAlarms
- **虚拟边**: `NetworkElement.DN = ALARM.MEDN`
- **查询方式**: 通过`medn`参数查询

### NEHasKPI
- **虚拟边**: `NetworkElement.resId = KPI.parentResId`
- **查询方式**: 通过`parentResId`参数查询

### LTPHasKPI2
- **虚拟边**: `LTP.resId = KPI2.parentResId`
- **查询方式**: 通过`parentResId`参数查询（与KPI共用接口）

## 架构设计

```
MockHttpServer (主服务器)
    ├── AlarmDataHandler (ALARM请求处理器)
    ├── KpiDataHandler (KPI请求处理器)
    └── MockDataRepository (数据仓库)
        ├── ALARM数据
        ├── KPI数据
        └── KPI2数据
```

## 技术栈

- **HTTP服务器**: Java内置 `com.sun.net.httpserver.HttpServer`
- **JSON处理**: Jackson 2.17.0
- **日志**: SLF4J + Logback
- **测试**: JUnit 5.10.2

## 注意事项

1. **端口冲突**: 默认使用8080端口，如果端口被占用，可以指定其他端口
2. **线程池**: 服务器使用固定大小线程池（10个线程）处理请求
3. **CORS**: 服务器已启用CORS，允许跨域请求
4. **数据隔离**: 每个测试实例使用独立的服务器实例，避免数据污染
