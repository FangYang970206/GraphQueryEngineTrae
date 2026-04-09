# 图联邦查询引擎 SDK 规范文档

## 1. 概述

本文档定义了图联邦查询引擎 SDK 的技术规范、约束条件和设计原则。

### 1.1 系统定位

本 SDK 定位于客户端（应用层）与底层数据源（TuGraph / 外部服务）之间的中间件层。它将 Cypher 语言视为统一的数据访问协议，通过抽象语法树（AST）解析、重写、分发，最终在内存中将异构数据融合成统一的图结构返回。

### 1.2 核心处理流程

```
Cypher 输入
    ↓
解析层 (Parser) - ANTLR4 生成 AST
    ↓
计划生成层 (Planner & Rewriter) - 基于元数据裂变为执行计划
    ↓
联邦执行层 (Federated Executor) - 并行请求 TuGraph 和外部服务
    ↓
结果聚合层 (Result Stitcher) - 内存数据对齐、路径拼接、排序分页
    ↓
JSON 输出
```

## 2. 核心约束

### 2.1 边界约束

虚拟边/虚拟节点仅允许出现在图遍历路径的**第一跳**或**最后一跳**。

**禁止的模式**：
```
[物理节点] -> [虚拟节点] -> [物理节点]  ❌ 夹心结构
```

**允许的模式**：
```
[虚拟节点] -> [物理节点]              ✅ 第一跳
[物理节点] -> [虚拟节点]              ✅ 最后一跳
[虚拟节点] -> [虚拟节点]              ✅ 纯虚拟查询
```

### 2.2 读写分离约束

SDK 仅支持**只读查询**，任何包含写操作的语句将抛出异常。

**支持的语句**：
- `MATCH` - 模式匹配
- `RETURN` - 结果返回
- `WITH` - 中间处理
- `WHERE` - 条件过滤
- `ORDER BY` - 排序
- `LIMIT` / `SKIP` - 分页
- `UNION` / `UNION ALL` - 结果合并
- `UNWIND` - 列表展开
- `OPTIONAL MATCH` - 可选匹配

**不支持的语句**：
- `CREATE` - 创建节点/关系
- `MERGE` - 合并节点/关系
- `DELETE` / `DETACH DELETE` - 删除节点/关系
- `SET` - 设置属性
- `REMOVE` - 删除属性

### 2.3 存储过程调用约束

**`CALL ... YIELD` 存储过程调用不经过联邦层**。

存储过程调用（如 `CALL algo.shortestPath(...)`、`CALL db.vertexLabels` 等）具有以下特点：

1. **无外部数据源**：存储过程完全在 TuGraph 内部执行，不涉及外部数据源
2. **直接路由**：此类查询应直接发送到原生 TuGraph 执行，不经过联邦查询引擎的解析和重写流程
3. **使用场景**：
   - 图算法调用（最短路径、PageRank 等）
   - 元数据查询（标签列表、属性列表等）
   - 系统管理操作

**正确使用方式**：
```java
// 存储过程调用应直接使用 TuGraph 原生客户端
TuGraphClient nativeClient = new TuGraphClient(endpoint);
String result = nativeClient.executeCypher("CALL algo.shortestPath(start, end) YIELD path RETURN path");

// 联邦查询使用 SDK
GraphQuerySDK sdk = new GraphQuerySDK(...);
String result = sdk.execute("MATCH (n:Person)-[:KNOWS]->(m) RETURN n, m");
```

**设计原因**：
- 存储过程是 TuGraph 特有功能，不涉及联邦数据融合
- 避免不必要的解析开销
- 保持存储过程调用的原生性能

### 2.3 外部数据源关联约束

外部数据源通过虚拟边与内部数据源进行关联，内外节点通过属性进行映射。

**映射规则**：
- 外部数据源的实体必须有唯一标识符
- 虚拟边定义中必须指定关联属性映射
- 支持一对多和多对一的关联关系

### 2.4 依赖感知执行约束

虚拟边的位置决定了数据流传递方向，系统根据虚拟边位置自动识别依赖关系并按正确顺序执行查询。

#### 2.4.1 虚拟边在第一跳（外部→物理）

**场景描述**：查询以虚拟边开始，外部数据源的查询结果需要传递给下一跳的TuGraph作为输入。

**数据流方向**：`外部数据源 → TuGraph`

**执行顺序**：
1. 先执行外部数据源查询，获取起始节点集合
2. 从外部数据源结果中提取节点ID
3. 将ID集合作为TuGraph查询的输入条件
4. 执行TuGraph查询

**示例**：
```cypher
-- 从KPI服务获取KPI节点，然后查询关联的LTP
MATCH (kpi:KPI)-[:KPIBelongsToLTP]->(ltp:LTP)
RETURN kpi, ltp
```

**执行流程**：
```
1. 外部查询: getKPIs() → 返回 [kpi1, kpi2, kpi3]
2. 提取ID: [kpi1.id, kpi2.id, kpi3.id]
3. 物理查询: MATCH (ltp:LTP) WHERE ltp.kpiId IN [kpi1.id, kpi2.id, kpi3.id]
4. 结果拼接: 返回完整的 (kpi, ltp) 对
```

#### 2.4.2 虚拟边在最后一跳（物理→外部）

**场景描述**：查询以虚拟边结束，TuGraph的查询结果需要传递给外部数据源查询作为输入。

**数据流方向**：`TuGraph → 外部数据源`

**执行顺序**：
1. 先执行TuGraph物理查询，获取前置节点集合
2. 从物理查询结果中提取源节点ID
3. 将ID集合作为外部数据源查询的输入条件
4. 执行外部数据源查询

**示例**：
```cypher
-- 先查询TuGraph中的LTP，然后获取关联的KPI数据
MATCH (ne:NetworkElement)-[:NEHasLtps]->(ltp:LTP)-[:LTPHasKPI2]->(kpi:KPI)
RETURN ne, ltp, kpi
```

**执行流程**：
```
1. 物理查询: MATCH (ne:NetworkElement)-[:NEHasLtps]->(ltp:LTP) 
   → 返回 [(ne1, ltp1), (ne1, ltp2), ...]
2. 提取ID: 从ltp节点提取 [ltp1.id, ltp2.id, ...]
3. 外部查询: getKPIsByLtpIds([ltp1.id, ltp2.id, ...]) → 返回 [kpi1, kpi2, ...]
4. 结果拼接: 返回完整的 (ne, ltp, kpi) 三元组
```

#### 2.4.3 混合边场景

**场景描述**：同一跳包含虚拟边和物理边的组合（使用 `|` 操作符）。

**示例**：
```cypher
-- LTPHasKPI2是虚拟边，LTPHasElement是物理边
MATCH (ne:NetworkElement)-[:NEHasLtps]->(ltp:LTP)-[:LTPHasKPI2|LTPHasElement]->(target)
RETURN ne, ltp, target
```

**处理逻辑**：
1. 物理边 `LTPHasElement` 由TuGraph直接处理
2. 虚拟边 `LTPHasKPI2` 触发依赖感知执行：
   - 先完成物理查询获取ltp节点
   - 提取ltp ID传递给外部数据源
   - 执行外部查询获取KPI数据
3. 合并两种边的结果，统一返回

#### 2.4.4 实现机制

**ExternalQuery 依赖字段**：
```java
public class ExternalQuery {
    private boolean dependsOnPhysicalQuery;  // 是否依赖物理查询
    private String sourceVariableName;       // 源变量名（用于提取ID）
    private String inputIdField;             // 输入ID字段
    private List<String> inputIds;           // 输入ID列表（运行时填充）
    
    // 判断是否需要等待物理查询结果
    public boolean needsInputIds() {
        return inputIdField != null && !inputIdField.isEmpty();
    }
    
    // 判断是否已准备好执行
    public boolean isReadyToExecute() {
        return !needsInputIds() || hasInputIds();
    }
}
```

**FederatedExecutor 执行逻辑**：
```java
public CompletableFuture<ExecutionResult> execute(ExecutionPlan plan) {
    // 1. 分类查询
    List<ExternalQuery> independent = externalQueries.stream()
        .filter(q -> !q.isDependsOnPhysicalQuery())
        .collect(toList());
    List<ExternalQuery> dependent = externalQueries.stream()
        .filter(ExternalQuery::isDependsOnPhysicalQuery)
        .collect(toList());
    
    // 2. 先执行物理查询
    return executePhysicalQueries(physicalQueries)
        .thenCompose(physicalResults -> {
            // 3. 提取ID并填充到依赖查询
            Map<String, Set<String>> idsByVar = extractIdsFromResults(physicalResults);
            for (ExternalQuery dep : dependent) {
                dep.setInputIds(idsByVar.get(dep.getSourceVariableName()));
            }
            // 4. 执行外部查询
            return executeExternalQueries(dependent);
        });
}
```

**ID提取规则**：
- 按变量名（`variableName`）分组提取ID
- 同时按标签名（`label`）建立索引
- 支持多变量映射（同一ID可属于多个变量）

#### 2.4.5 空结果处理

| 场景 | 物理查询结果 | 外部查询行为 | 最终结果 |
|------|-------------|-------------|---------|
| 物理查询返回空 | 无节点 | 不执行（无输入ID） | 空结果集 |
| 外部查询返回空 | 有节点 | 执行但返回空 | 物理节点 + NULL外部节点 |
| 两者都返回空 | 无节点 | 不执行 | 空结果集 |

## 3. 数据源类型

### 3.1 物理数据源

| 类型 | 说明 | 适配器 |
|------|------|--------|
| TuGraph | 图数据库 | 用户实现 `DataSourceAdapter` 接口 |

> **注意**: SDK 仅提供 `DataSourceAdapter` 接口定义，具体的数据源适配器（如 TuGraphAdapter）由用户根据实际需求实现。单元测试中使用 `MockExternalAdapter` 模拟所有数据源行为。

### 3.2 外部数据源

| 类型 | 说明 | 接口规范 |
|------|------|----------|
| REST API | HTTP 服务 | RESTful API |
| gRPC | RPC 服务 | Protocol Buffers |
| 自定义 | 用户定义 | DataSourceAdapter 接口 |

> **注意**: 外部数据源适配器同样由用户实现 `DataSourceAdapter` 接口，单元测试中使用 `MockExternalAdapter` 模拟。

## 4. 元数据定义

### 4.1 数据源元数据

```java
DataSourceMetadata {
    String name;           // 数据源名称
    DataSourceType type;   // 数据源类型
    String endpoint;       // 服务端点
    Map<String, Object> config; // 额外配置
}
```

### 4.2 虚拟边绑定

```java
VirtualEdgeBinding {
    String edgeType;        // 边类型名称
    String targetDataSource; // 目标数据源
    String operatorName;     // 操作名称
    String inputIdField;     // 输入ID字段
    String outputIdField;    // 输出ID字段
    boolean firstHopOnly;    // 仅允许第一跳
    boolean lastHopOnly;     // 仅允许最后一跳
}
```

### 4.3 标签元数据

```java
LabelMetadata {
    String label;          // 标签名称
    boolean virtual;       // 是否虚拟
    String dataSource;     // 所属数据源
    List<String> properties; // 属性列表
}
```

## 5. 返回格式规范

### 5.1 节点返回格式

```json
[
  {
    "variableName": {
      "id": "nodeId",
      "label": "LabelName",
      "property1": "value1",
      "property2": "value2"
    }
  }
]
```

### 5.2 Path 返回格式

```json
{
  "paths": [
    [
      {"type": "node", "label": "Label1", "props": {"name": "value"}},
      {"type": "edge", "label": "RELATIONSHIP", "props": {}},
      {"type": "node", "label": "Label2", "props": {"name": "value"}}
    ]
  ]
}
```

**Path 元素规范**：
- `type`: 元素类型，值为 `node` 或 `edge`
- `label`: 节点标签或关系类型
- `props`: 属性字典，包含所有属性键值对

## 6. 性能规范

### 6.1 批量请求策略

**禁止**：在循环中逐个请求外部接口
```java
// ❌ 错误示例
for (String id : ids) {
    externalApi.query(id);  // N+1 问题
}
```

**要求**：批量请求
```java
// ✅ 正确示例
externalApi.batchQuery(ids);  // 一次请求
```

### 6.2 执行计划缓存

- 使用 Caffeine 缓存执行计划
- 缓存键：Cypher 字符串的 Hash 值
- 缓存值：编译后的 ExecutionPlan 对象
- 默认过期策略：LRU

### 6.3 并行执行

- UNION 查询的各部分并行执行
- 物理查询和外部查询并行执行
- 使用线程池管理并发

## 7. 错误处理规范

### 7.1 语法错误

```json
{
  "success": false,
  "error": "Syntax error at line 1, position 10: mismatched input 'RETUR' expecting 'RETURN'"
}
```

### 7.2 约束违规

```json
{
  "success": false,
  "error": "VirtualEdgeConstraintException: Virtual edge 'NEHasKPI' cannot appear in middle of path. Virtual edges are only allowed at first or last hop."
}
```

### 7.3 外部服务错误

```json
{
  "success": false,
  "error": "External service 'kpi-service' unavailable: Connection timeout after 30000ms"
}
```

## 8. 扩展机制

### 8.1 自定义数据源适配器

```java
public interface DataSourceAdapter {
    String getDataSourceType();
    String getDataSourceName();
    CompletableFuture<QueryResult> execute(ExternalQuery query);
    QueryResult executeSync(ExternalQuery query);
    boolean isHealthy();
}
```

### 8.2 自定义语法扩展

在 `Lcypher.g4` 中定义新的语法规则，然后在 `CypherASTVisitor` 中实现访问逻辑。

## 9. 安全规范

### 9.1 参数化查询

推荐使用参数化查询防止注入攻击：

```java
Map<String, Object> params = new HashMap<>();
params.put("name", userInput);
sdk.execute("MATCH (n:NetworkElement {name: $name}) RETURN n", params);
```

### 9.2 敏感信息保护

- 不在日志中记录敏感参数
- 不在错误消息中暴露内部实现细节
- 外部服务调用使用安全协议 (HTTPS)

## 10. 版本兼容性

### 10.1 Java 版本

- 最低要求：Java 17
- 推荐版本：Java 17 LTS

### 10.2 依赖版本

| 依赖 | 最低版本 | 推荐版本 |
|------|----------|----------|
| Spring Framework | 6.0.0 | 6.1.6 |
| ANTLR4 | 4.9.0 | 4.13.1 |
| Caffeine | 3.0.0 | 3.1.8 |
| Jackson | 2.14.0 | 2.17.0 |

## 11. 测试规范

### 11.1 单元测试

- 每个模块必须有对应的单元测试
- 测试覆盖率目标：80%+

### 11.2 端到端测试

- 覆盖所有支持的查询场景
- **所有数据源必须使用 Mock**：
  - TuGraph（物理图数据库）通过 `MockExternalAdapter` 模拟
  - 外部服务（REST API、gRPC 等）通过 `MockExternalAdapter` 模拟
  - 禁止在测试中连接真实数据库或外部服务
- `MockExternalAdapter` 位置：`src/test/java/.../adapter/MockExternalAdapter.java`
- 验证返回格式的正确性
- 遵循 UT 强制精确校验规范（见 AGENTS.md）

## 12. 变更日志

| 版本 | 日期 | 变更内容 |
|------|------|----------|
| 1.0.0 | 2024-01 | 初始版本 |
