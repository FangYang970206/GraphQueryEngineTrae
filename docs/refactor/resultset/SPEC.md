# 结果集异常语义重构方案

## 背景

当前实现把执行失败、部分结果和 warning 留在结果对象中，再由 `GraphQuerySDK.checkExecutionErrors()` 做二次检查。这种做法有三个问题：

1. 失败语义分散在执行层和 SDK 层，职责不清。
2. `execute()` / `executeRaw()` / `executeWithProfile()` 失败时返回错误 JSON，和 `executeRecords()` 抛异常的行为不一致。
3. 异步执行失败经 `CompletableFuture.join()` 传播后会被包装，文档和代码都缺少统一解包规则。

本方案的目标是把失败语义前移到执行层，并统一 SDK 的对外契约。

## 已确认口径

### 1. 执行语义

- **全成功或全失败**：物理查询、外部查询、批量查询、Union 子查询中任一失败，整次查询失败。
- **无部分结果**：不再通过结果对象返回错误或 warning。
- **依赖查询无输入不是失败**：当依赖型外部查询拿不到上游输入 ID，按空结果处理，不抛异常。
- **最终失败而非强制取消**：并发任务可继续收尾，但本次查询最终必须失败并向上抛出异常。

### 2. SDK 对外契约

- `execute()`、`executeRaw()`、`executeRecords()`、`executeWithProfile()` 失败时统一抛出 `GraphQueryException`。
- 不再返回 `{success:false,...}` 形式的错误 JSON。
- 本次改动视为**破坏性变更**，调用方与测试需要同步迁移。

### 3. 异常传播

- 执行层负责在失败发生点构造并抛出 `GraphQueryException`。
- SDK 层负责解包 `CompletionException` / `ExecutionException`，对外暴露 `GraphQueryException`。
- 同一失败只在执行层记录一次错误日志，SDK 不重复记录同一失败。

### 4. HTTP 状态映射

- **语法侧问题**：映射为 HTTP `400`
- **执行侧问题**：映射为 HTTP `500`

本文档定义错误码，不直接定义 HTTP 响应对象；但异常模型必须保留后续映射 HTTP 状态所需的信息。

## 错误码规范

| 错误码 | 名称 | 使用场景 | HTTP |
|--------|------|----------|------|
| 1001 | `QUERY_PARSE_ERROR` | 查询解析失败 | 400 |
| 1002 | `QUERY_REWRITE_ERROR` | 查询重写失败 | 400 |
| 1003 | `QUERY_EXECUTION_ERROR` | 通用执行错误、依赖装配异常 | 500 |
| 2001 | `VIRTUAL_EDGE_CONSTRAINT_VIOLATION` | 虚拟边约束违反 | 400 |
| 3001 | `DATASOURCE_CONNECTION_ERROR` | 适配器不存在、连接失败 | 500 |
| 3002 | `DATASOURCE_QUERY_ERROR` | 物理查询失败或超时 | 500 |
| 3003 | `EXTERNAL_DATASOURCE_ERROR` | 外部查询失败或超时 | 500 |
| 5002 | `BATCH_EXECUTION_ERROR` | 批量查询失败或超时 | 500 |
| 5003 | `UNION_EXECUTION_ERROR` | Union 子查询失败 | 500 |

### 明确废弃

- `5001 PARTIAL_RESULT_ERROR` 不再使用。
- `QueryResult.partial()` 和 `QueryResult.warnings` 不再作为目标模型的一部分。

## 重构范围

### 1. FederatedExecutor

| 方法 | 当前行为 | 目标行为 |
|------|----------|----------|
| `executePhysical()` | 返回 `QueryResult.error()` | 抛出 `GraphQueryException(3001/3002)` |
| `executeExternal()` | 适配器缺失时返回 `partial` | 抛出 `GraphQueryException(3001/3003)` |
| `executeBatch()` | 返回 `QueryResult.error()` | 抛出 `GraphQueryException(3001/5002)` |
| `executeUnion()` | 捕获子结果异常后继续聚合 | 失败时抛出 `GraphQueryException(5003)` |
| `executeWithDependencyAwareness()` | `notReadyQueries` 写入 partial | 无输入时生成空结果；真实执行失败时抛异常 |

### 2. GraphQuerySDK

- 移除 `checkExecutionErrors()`。
- 移除 `buildErrorResponse()` 及所有基于错误 JSON 的回退路径。
- 为所有公开入口统一异常解包逻辑。
- 保留成功路径上的结果构造、过滤、排序、分页和去重逻辑。

### 3. 结果对象模型

#### `ExecutionResult`

- 移除 `success` 字段。
- 保留结果收集职责，不再承担“成功/失败状态”表达。

#### `QueryResult` (model 包)

- 位于 `model` 目录，作为查询结果的核心领域模型。
- 逐步移除 `success`、`error`、`warnings` 作为失败语义载体。
- 成功场景仍承载 `entities`、`rows`、`dataSource`、`executionTimeMs`。
- 失败场景改为异常，不再构造 `QueryResult.error()` / `QueryResult.partial()`。

## 关键语义说明

### 1. 依赖查询无输入

以下场景按**空结果**处理，而不是失败：

- 物理查询本身返回空结果。
- 依赖查询需要输入 ID，但上游没有产出任何可用 ID。
- 由于上游为空，外部依赖查询不再实际发起调用。

以下场景按**执行失败**处理：

- 本应存在适配器但未注册。
- 外部/物理/批量查询实际执行失败或超时。
- 依赖链装配逻辑自身出错，导致无法正确构造查询输入。

### 2. Union 失败

- 任一子查询失败，整个 Union 失败。
- 对外错误码统一使用 `5003 UNION_EXECUTION_ERROR`。
- 不再吞掉子查询异常，也不再返回部分 Union 结果。

### 3. CompletableFuture 行为

- `CompletableFuture.allOf(...).join()` 可以传播失败，但不会自动把外层异常变成 `GraphQueryException`。
- SDK 或执行器边界必须显式解包 `CompletionException` / `ExecutionException`。
- 文档中“异常自然传播”指失败可沿链路上抛，不代表调用方会直接拿到未包装的业务异常。

## 异常处理流程

```text
调用方
  -> GraphQuerySDK
     -> rewriter.rewrite(ast)
     -> executor.execute(plan).join()
        -> FederatedExecutor 调度物理/外部/批量/Union 查询
        -> 失败时在发生点记录日志并抛出 GraphQueryException
     -> SDK 解包异步包装异常
     -> 对外抛出 GraphQueryException
```

## 测试影响

### 需要更新的测试类型

1. 依赖外部服务不可用的测试：应断言抛出异常。
2. Union 子查询失败的测试：应断言整体失败，错误码为 `5003`。
3. 旧错误 JSON 测试：全部迁移为异常断言。
4. 依赖查询无输入的测试：继续断言空结果，不应改成异常。
5. 异步失败传播测试：应断言 SDK 对外拿到的是 `GraphQueryException`，而不是 `CompletionException`。

### 测试断言示例

```java
@Test
void externalServiceUnavailable_ThrowsGraphQueryException() {
    GraphQueryException exception = assertThrows(GraphQueryException.class, () -> {
        sdk.executeRaw(cypher);
    });

    assertEquals(3003, exception.getCode());
}

@Test
void dependencyQueryWithoutInput_ReturnsEmptyArray() {
    String result = sdk.executeRaw(cypher);
    JsonNode json = JsonUtil.readTree(result);

    assertTrue(json.isArray());
    assertEquals(0, json.size());
}
```

## 回滚计划

如果重构后发现不可接受的问题，需要回退以下内容：

1. 恢复 `GraphQuerySDK.checkExecutionErrors()`。
2. 恢复错误 JSON 输出路径。
3. 恢复执行层使用结果对象承载失败语义的旧行为。

## 验收标准

- [ ] 所有失败场景最终都向调用方抛出 `GraphQueryException`
- [ ] `execute()` / `executeRaw()` / `executeRecords()` / `executeWithProfile()` 的失败契约一致
- [ ] SDK 对外不暴露 `CompletionException` / `ExecutionException`
- [ ] `5001 PARTIAL_RESULT_ERROR` 不再被使用
- [ ] `warning` 不再出现在结果对象语义中
- [ ] 依赖查询无输入场景继续返回空结果
- [ ] Union 失败统一使用错误码 `5003`
- [ ] 同一失败只记录一次错误日志
