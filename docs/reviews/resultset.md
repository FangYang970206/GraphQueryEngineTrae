# 结果集重构计划检视报告

**检视日期**: 2026-04-15  
**检视范围**: `docs/refactor/resultset/` 目录下的重构文档  
**检视对象**: 执行层异常抛出重构方案

---

## 一、核心架构问题

### 1. CompletableFuture 异常传播机制理解偏差 ⚠️ 高风险

**问题描述**：
重构计划假设 `CompletableFuture.allOf().join()` 会在任一 Future 失败时立即抛出异常，但实际行为并非如此。

**当前代码**：
```java
// FederatedExecutor.java:98-99
return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
    .thenApplyAsync(v -> markExecutionSuccess(result), executorService);
```

**问题分析**：
- `CompletableFuture.allOf()` 会等待所有 Future 完成，即使某些已经失败
- 只有在调用 `.join()` 时才会抛出第一个失败的异常
- 当前代码使用 `.thenApplyAsync()`，不会立即传播异常

**影响**：
- 重构后可能无法实现"任一失败立即终止"的语义
- 需要重新设计异常传播机制

**建议**：
```java
return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
    .thenApplyAsync(v -> markExecutionSuccess(result), executorService)
    .exceptionally(ex -> {
        throw new CompletionException(ex.getCause());
    });
```

---

### 2. Union 查询异常处理不完整 ⚠️ 高风险

**当前代码**：
```java
// FederatedExecutor.java:584-601
for (CompletableFuture<ExecutionResult> future : subFutures) {
    try {
        ExecutionResult subResult = future.get();
        // ...
    } catch (Exception e) {
        log.error("Failed to get union sub-result", e);
    }
}
```

**问题分析**：
- 当前 Union 查询捕获异常后继续执行，与"全成功或全失败"目标不符
- 重构计划要求移除 try-catch，但需要确保异常正确传播

**影响**：
- 重构后 Union 查询的异常传播可能不完整
- 需要确保 `CompletableFuture.allOf()` 的异常传播机制正确工作

---

## 二、执行层异常抛出问题

### 3. 依赖查询输入不满足的处理不一致 ⚠️ 中风险

**当前代码**：
```java
// FederatedExecutor.java:129-133
for (ExternalQuery query : notReadyQueries) {
    log.warn("External query {} is not ready to execute - no input IDs available", query.getId());
    result.addExternalResult(QueryResult.partial(new ArrayList<>(), 
            "No input IDs available from physical query"));
}
```

**问题分析**：
- 当前返回部分结果，重构计划要求抛出异常
- 但这可能导致物理查询成功、依赖查询失败的场景被错误处理

**影响**：
- 重构后可能破坏现有的查询流程
- 需要明确：物理查询返回空结果时，依赖查询应该如何处理？

**建议**：
- 明确区分"物理查询失败"和"物理查询返回空结果"两种场景
- 物理查询失败 → 立即抛出异常
- 物理查询返回空结果 → 依赖查询不执行，但不抛出异常（返回空结果）

---

### 4. 重试机制与异常传播冲突 ⚠️ 中风险

**当前代码**：
```java
// FederatedExecutor.java:376-386
.exceptionally(e -> {
    if (attempt < maxRetries) {
        log.warn("External query attempt {}/{} failed for data source: {}, retrying...", 
                attempt + 1, maxRetries + 1, query.getDataSource());
        return null;  // 返回 null 触发重试
    }
    log.error("External query failed after {} attempts for data source: {}", 
            maxRetries + 1, query.getDataSource());
    return QueryResult.error("External query failed after " + (maxRetries + 1) + 
            " attempts: " + e.getMessage());
})
```

**问题分析**：
- 当前重试机制通过返回 `null` 触发重试
- 重构后需要抛出异常，但重试逻辑需要调整

**影响**：
- 重构后重试机制可能失效
- 需要重新设计重试逻辑，确保异常正确传播

---

## 三、SDK 层问题

### 5. execute() 和 executeRaw() 方法的异常处理不一致 ⚠️ 中风险

**当前代码**：
```java
// GraphQuerySDK.java:104-127
public String execute(String cypher) {
    try {
        // ...
        List<Map<String, Object>> results = executeQuery(ast);
        return JsonUtil.toJson(results);
    } catch (GraphQueryException e) {
        log.error("Query execution failed [code={}] [cypher={}]", e.getCode(), cypher, e);
        return buildErrorResponse(e);  // 返回错误响应
    }
}
```

**问题分析**：
- 当前 `execute()` 和 `executeRaw()` 捕获异常后返回错误响应
- `executeRecords()` 直接抛出异常
- 重构计划要求所有方法都直接抛出异常

**影响**：
- 破坏现有 API 的行为契约
- 调用方需要修改异常处理逻辑

**建议**：
- 保持 `execute()` 和 `executeRaw()` 返回 JSON 字符串的契约
- 失败时返回包含错误信息的 JSON，而不是抛出异常
- 或者明确这是一个破坏性变更，需要调用方适配

---

### 6. checkExecutionErrors() 方法的移除影响 ⚠️ 中风险

**当前代码**：
```java
// GraphQuerySDK.java:1458-1509
private void checkExecutionErrors(ExecutionResult execResult) {
    if (execResult == null) {
        log.error("Execution result is null");
        throw new GraphQueryException(ErrorCode.QUERY_EXECUTION_ERROR, "Execution result is null");
    }

    if (!execResult.isSuccess()) {
        log.error("Execution failed with errors");
        throw new GraphQueryException(ErrorCode.QUERY_EXECUTION_ERROR, "Query execution failed");
    }

    // 检查所有 QueryResult 的 success 和 warnings
    for (List<QueryResult> results : execResult.getPhysicalResults().values()) {
        for (QueryResult qr : results) {
            if (!qr.isSuccess()) {
                // ...
            }
            if (qr.getWarnings() != null && !qr.getWarnings().isEmpty()) {
                // ...
            }
        }
    }
    // ...
}
```

**问题分析**：
- 当前 `checkExecutionErrors()` 统一检查所有结果
- 重构计划要求移除这个方法，改为执行层即时抛出异常
- 但某些场景（如 Union 查询）可能需要统一检查

**影响**：
- 移除后可能遗漏某些错误检查
- 需要确保所有错误场景都在执行层正确处理

---

## 四、ExecutionResult 和 QueryResult 问题

### 7. ExecutionResult.success 字段的移除影响 ⚠️ 低风险

**当前代码**：
```java
// ExecutionResult.java:11
private boolean success = false;
```

**问题分析**：
- 当前 `success` 字段用于标记整体执行状态
- 重构计划要求移除这个字段，由"无异常"表示成功

**影响**：
- 移除后无法区分"执行中"和"执行成功"状态
- 但影响较小，因为重构后异常会立即抛出

---

### 8. QueryResult.warnings 字段的处理 ⚠️ 中风险

**当前代码**：
```java
// GraphQuerySDK.java:1476-1480
if (qr.getWarnings() != null && !qr.getWarnings().isEmpty()) {
    String warningMsg = String.join("; ", qr.getWarnings().values());
    log.error("Physical query has warnings, treating as error: {}", warningMsg);
    throw new GraphQueryException(ErrorCode.PARTIAL_RESULT_ERROR, warningMsg);
}
```

**问题分析**：
- 当前 warnings 被视为错误
- 重构计划要求移除 warnings 字段

**影响**：
- 需要明确：warnings 是否应该转为异常？
- 如果是，需要在执行层即时抛出，而不是在 SDK 层检查

---

## 五、测试覆盖问题

### 9. 测试用例需要大量更新 ⚠️ 高风险

**当前测试**：
```java
// ExecutorTest.java:213-234
@Test
@DisplayName("Fallback on failure returns error")
void fallbackOnFailure() {
    mockAdapter.registerResponse("errorOp", 
            MockExternalAdapter.MockResponse.withError("Connection refused"));
    
    ExecutionResult result = executor.execute(plan).join();
    
    assertNotNull(result, "ExecutionResult不能为空");
    // 当前期望返回结果，重构后应该抛出异常
}
```

**问题分析**：
- 当前测试期望返回包含错误的结果
- 重构后应该期望抛出异常

**影响**：
- 需要更新大量测试用例
- 测试覆盖率可能下降

**建议**：
- 优先更新关键路径的测试
- 确保异常场景的测试覆盖

---

### 10. E2E 测试的 Mock 数据问题 ⚠️ 中风险

**当前测试**：
```java
// DependencyAwareExecutionE2ETest.java:381-399
@Test
@DisplayName("外部服务不可用时应返回部分结果")
void externalServiceUnavailable_ReturnsPartialResult() throws Exception {
    // ...
    kpiAdapter.registerResponse("getKPI2ByLtpIds", MockExternalAdapter.MockResponse.withError("Service unavailable"));
    
    JsonNode json = JsonUtil.readTree(sdk.executeRaw(cypher));
    assertTrue(json.isArray(), "Result must be an array");
}
```

**问题分析**：
- 当前测试期望返回部分结果
- 重构后应该抛出异常

**影响**：
- E2E 测试需要大量更新
- Mock 数据需要调整

---

## 六、其他问题

### 11. 日志记录策略不明确 ⚠️ 低风险

**问题分析**：
- 重构计划要求"所有异常抛出前必须记录错误日志"
- 但当前代码中日志记录位置不统一

**建议**：
- 明确日志记录的位置和格式
- 确保异常传播过程中不重复记录日志

---

### 12. 错误码使用规范需要明确 ⚠️ 低风险

**问题分析**：
- 重构计划提供了错误码映射表
- 但某些场景的错误码选择不明确

**建议**：
- 明确每个场景的错误码
- 确保错误码的一致性

---

## 七、总结和建议

### 关键风险点

1. **CompletableFuture 异常传播机制**：需要重新设计，确保"任一失败立即终止"
2. **API 行为变更**：`execute()` 和 `executeRaw()` 的行为变更可能破坏现有调用方
3. **测试覆盖**：需要大量更新测试用例，确保异常场景覆盖

### 建议的实施策略

#### 1. 分阶段实施

- **第一阶段**：重构 FederatedExecutor，保持 SDK 层不变
- **第二阶段**：更新测试用例，确保异常场景覆盖
- **第三阶段**：重构 SDK 层，明确 API 行为变更

#### 2. 保持向后兼容

- `execute()` 和 `executeRaw()` 保持返回 JSON 字符串
- 失败时返回包含错误信息的 JSON，而不是抛出异常
- `executeRecords()` 可以直接抛出异常

#### 3. 完善异常传播机制

- 使用 `CompletableFuture.exceptionally()` 确保异常正确传播
- 明确 Union 查询的异常处理策略
- 确保重试机制与异常传播兼容

#### 4. 加强测试覆盖

- 优先更新关键路径的测试
- 确保异常场景的测试覆盖
- 添加集成测试验证异常传播

---

## 八、风险等级汇总

| 风险等级 | 问题数量 | 问题编号 |
|---------|---------|---------|
| 高风险 | 3 | 1, 2, 9 |
| 中风险 | 6 | 3, 4, 5, 6, 8, 10 |
| 低风险 | 3 | 7, 11, 12 |

---

## 九、后续行动建议

1. **优先解决高风险问题**：
   - 明确 CompletableFuture 异常传播机制的设计
   - 完善 Union 查询的异常处理
   - 制定测试用例更新计划

2. **明确 API 行为变更**：
   - 与团队讨论 `execute()` 和 `executeRaw()` 的行为变更
   - 确定是否需要保持向后兼容

3. **完善重构计划**：
   - 补充异常传播机制的详细设计
   - 明确测试用例更新策略
   - 制定分阶段实施计划

---

**检视结论**：重构计划的总体方向正确，但在异常传播机制、API 行为变更、测试覆盖等方面存在一定风险。建议在实施前充分讨论并完善相关设计，确保重构的可行性和完整性。
