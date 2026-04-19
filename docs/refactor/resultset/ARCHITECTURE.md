# 结果集异常语义重构 - 架构设计

## 当前架构

```text
GraphQuerySDK
  1. parseCached(cypher)
  2. rewrite(ast) -> ExecutionPlan
  3. executor.execute(plan).join() -> ExecutionResult
  4. checkExecutionErrors(result)
  5. buildTuGraphFormatResults(result)
  6. 失败时 buildErrorResponse(...)

FederatedExecutor
  - 收集 Physical / External / Batch / Union 结果
  - 把失败和 warning 封装进 QueryResult / ExecutionResult
  - 依赖查询无输入时返回 partial
  - Union 子查询失败时可能被吞掉
```

当前设计的问题不是“不能表达失败”，而是失败表达分散：

- 执行层用结果对象表达失败。
- SDK 再次扫描结果对象，把失败转成异常或错误 JSON。
- 字符串接口和记录接口的失败契约不一致。

## 目标架构

```text
GraphQuerySDK
  1. parseCached(cypher)
  2. rewrite(ast) -> ExecutionPlan
  3. executor.execute(plan).join()
  4. 解包 CompletionException / ExecutionException
  5. 成功时构造结果
  6. 失败时抛出 GraphQueryException

FederatedExecutor
  - 调度 Physical / External / Batch / Union 查询
  - 在失败发生点记录日志并抛出 GraphQueryException
  - 不再返回 error / partial / warning 结果对象
  - 依赖查询无输入时按空结果处理
```

## 关键变化

### 1. 失败语义的位置

| 方面 | 当前架构 | 目标架构 |
|------|----------|----------|
| 失败表达 | `QueryResult.error()` / `partial()` | 直接抛 `GraphQueryException` |
| warning 处理 | SDK 层统一扫描 | warning 语义移除，转为异常或日志 |
| SDK 失败行为 | 返回错误 JSON 或抛异常 | 统一抛异常 |
| 日志边界 | 执行层、SDK 都可能记录 | 执行层单点记录 |

### 2. 依赖查询语义

| 场景 | 目标行为 |
|------|----------|
| 上游物理查询为空 | 返回空结果 |
| 依赖查询无输入 ID | 返回空结果，不执行外部调用 |
| 输入装配逻辑异常 | 抛 `GraphQueryException(1003)` |
| 外部调用失败/超时 | 抛 `GraphQueryException(3003)` |

### 3. Union 语义

| 场景 | 目标行为 |
|------|----------|
| 任一子查询失败 | 整体失败 |
| 对外错误码 | `5003 UNION_EXECUTION_ERROR` |
| 子异常处理 | 不吞异常，统一包装为 `GraphQueryException` |

## 异常传播链

```text
DataSourceAdapter.execute()
  -> 底层异常 / 返回失败结果
  -> FederatedExecutor 在边界处转换为 GraphQueryException
  -> CompletableFuture 传播时产生 CompletionException 包装
  -> GraphQuerySDK 解包
  -> 对外抛出 GraphQueryException
```

### 设计要求

- “异常传播”不等于“调用方直接拿到未包装异常”。
- `join()` 带来的异步包装必须显式处理。
- SDK 不应把 `CompletionException` 直接暴露给调用方。

## 组件职责

### FederatedExecutor

**职责**

- 协调物理查询、外部查询、批量查询和 Union 查询。
- 管理依赖查询的执行时序。
- 把底层失败统一转换为 `GraphQueryException`。
- 在失败发生点记录完整错误日志。

**不再承担**

- 通过 `QueryResult.error()` 表达失败。
- 通过 `QueryResult.partial()` 表达“可继续”的失败。
- 吞掉 Union 子查询异常后继续聚合。

### GraphQuerySDK

**职责**

- 提供统一的公开 API。
- 负责 parse / rewrite / execute / result rendering 串联。
- 统一解包异步异常。
- 成功返回结果，失败抛出 `GraphQueryException`。

**不再承担**

- `checkExecutionErrors()` 这种结果层兜底扫描。
- `buildErrorResponse()` 这种错误 JSON 构造职责。
- 对同一失败做重复错误日志记录。

### ExecutionResult

**职责**

- 承载成功执行后的结果集合。
- 为结果构建阶段提供 `physicalResults`、`batchResults`、`unionResults`、`externalResults`。

**调整方向**

- 不再通过 `success` 表达执行状态。
- 失败由异常表示，而不是由对象状态表示。

### QueryResult (model 包)

**保留职责**

- 承载成功查询返回的 `entities`、`rows`、`dataSource`、`executionTimeMs`。
- 位于 `model` 目录，作为核心领域模型。

**移除方向**

- 不再作为失败或 warning 的载体。
- `error`、`warnings`、`partial` 逐步退出主路径。

## 异常体系

```text
RuntimeException
  └── GraphQueryException
        ├── QUERY_PARSE_ERROR (1001, HTTP 400)
        ├── QUERY_REWRITE_ERROR (1002, HTTP 400)
        ├── VIRTUAL_EDGE_CONSTRAINT_VIOLATION (2001, HTTP 400)
        ├── QUERY_EXECUTION_ERROR (1003, HTTP 500)
        ├── DATASOURCE_CONNECTION_ERROR (3001, HTTP 500)
        ├── DATASOURCE_QUERY_ERROR (3002, HTTP 500)
        ├── EXTERNAL_DATASOURCE_ERROR (3003, HTTP 500)
        ├── BATCH_EXECUTION_ERROR (5002, HTTP 500)
        └── UNION_EXECUTION_ERROR (5003, HTTP 500)
```

### HTTP 状态映射

- 语法侧问题映射为 `400`：
  - `1001 QUERY_PARSE_ERROR`
  - `1002 QUERY_REWRITE_ERROR`
  - `2001 VIRTUAL_EDGE_CONSTRAINT_VIOLATION`
- 执行侧问题映射为 `500`：
  - `1003`、`3001`、`3002`、`3003`、`5002`、`5003`

## 并发与异常

### 1. `CompletableFuture.allOf()` 的真实语义

- 它能保证最终失败向上游传播。
- 它不能自动取消已经提交的兄弟任务。
- 因此本方案采用的语义是“最终整体失败”，不是“强制抢占式停止所有任务”。

### 2. 建议处理模型

```java
return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .thenApply(v -> result);
```

配套要求：

- 每个子任务失败时已经是 `GraphQueryException`。
- SDK 在 `join()` 边界解包 `CompletionException`。
- 不使用 `exceptionally()` 把异常重新变回普通结果对象。

### 3. 依赖查询的目标处理

```java
return CompletableFuture.allOf(physicalFutures.toArray(new CompletableFuture[0]))
        .thenComposeAsync(v -> {
            populateDependentQueryInputIds(...);

            if (noInputIds) {
                return CompletableFuture.completedFuture(emptyResult);
            }

            return runDependentQueries(...);
        }, executorService);
```

这里的 `emptyResult` 表示业务上的空数据，而不是失败。

## 日志策略

### 执行层

- 在失败发生点记录一次完整错误日志。
- 日志必须带查询类型、查询 ID、数据源、失败原因。

示例：

```java
log.error("External query failed [queryId={}] [dataSource={}]: {}",
        query.getId(), query.getDataSource(), errorMsg);
throw new GraphQueryException(ErrorCode.EXTERNAL_DATASOURCE_ERROR, errorMsg, cause);
```

### SDK 层

- 不重复记录同一失败。
- 只负责解包和透传 `GraphQueryException`。
- 如果需要补充上下文，应使用调试日志或调用方处理，而不是再打一条错误日志。

## 测试策略

### 单元测试

应覆盖以下能力：

- 适配器不存在时抛出正确错误码。
- 超时时抛出正确错误码。
- Union 子查询失败时对外为 `5003`。
- 依赖查询无输入时返回空结果。
- SDK 对外拿到的是 `GraphQueryException`，不是 `CompletionException`。

### 集成测试

旧测试：

```java
String result = sdk.execute(...);
JsonNode json = JsonUtil.readTree(result);
assertFalse(json.get("success").asBoolean());
```

目标测试：

```java
GraphQueryException e = assertThrows(GraphQueryException.class, () -> sdk.execute(...));
assertEquals(3003, e.getCode());
```

## 性能考虑

- 正常路径性能应与现状基本一致。
- 异常对象创建只发生在失败场景。
- 结果模型简化后，可减少 SDK 对结果对象的二次扫描。

## 回滚策略

如果需要回滚，可恢复以下设计：

1. 恢复 `checkExecutionErrors()`。
2. 恢复错误 JSON 输出路径。
3. 恢复 `QueryResult.error()` / `partial()` 主路径。
4. 恢复 `ExecutionResult.success` 状态位。
