# 优化待办事项 - 已完成

**创建日期**: 2026-04-07  
**完成日期**: 2026-04-07  
**依据**: 项目审计报告 v2  
**范围**: 功能缺口、架构问题、代码质量  

---

## P0 — 必须修复（阻塞正确性）

### [P0-1] `pendingFilters` 数据流断裂 — WHERE 虚拟条件未过滤结果 ✅

**问题描述**:  
`QueryRewriter.whereConditionPushdown.analyze()` 正确收集了 `isVirtual=true` 的条件到 `GlobalContext.pendingFilters`，但 `GraphQuerySDK.buildTuGraphFormatResults()` 从未读取 `stitched.getPendingFilters()` 对结果进行过滤。

**修复内容**:
1. 在 `GraphQuerySDK.execute()` 中添加 `applyPendingFilters()` 方法调用
2. 实现 `applyPendingFilters()` 方法，读取 `stitched.getPendingFilters()` 对结果进行过滤
3. 实现条件求值逻辑：`evaluateCondition()` 支持 `=`, `<>`, `>`, `>=`, `<`, `<=`, `IN`, `CONTAINS`, `STARTS WITH`, `ENDS WITH` 操作符

**涉及文件**:
- `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java`

---

### [P0-2] `deduplicate()` 注入但从未调用 — UNION 去重不工作 ✅

**问题描述**:  
`GraphQuerySDK` 注入了 `UnionDeduplicator deduplicator`，但整个类中零调用。

**修复内容**:
1. 在 `GraphQuerySDK.execute()` 中添加 `applyDeduplication()` 方法调用
2. 实现 `applyDeduplication()` 方法，检测 UNION 查询并调用 `deduplicator.deduplicateRows()`
3. 仅对 `UNION DISTINCT` 去重，`UNION ALL` 保持原样

**涉及文件**:
- `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java`
- `src/main/java/com/federatedquery/aggregator/UnionDeduplicator.java`

---

## P1 — 高优先级（功能完整性）

### [P1-1] `EXPLAIN`/`PROFILE` — 语法解析了但从未执行 ✅

**问题描述**:  
`CypherASTVisitor.visitOC_Statement()` 正确解析了 `EXPLAIN`/`PROFILE` 标记到 `Statement`，但后续 pipeline 完全忽略这些标志。

**修复内容**:
1. 在 `GraphQuerySDK.execute()` 入口检测 `Statement.isExplain()` / `isProfile()`
2. 实现 `buildExplainResponse()` 方法：返回逻辑执行计划（不实际执行）
3. 实现 `executeWithProfile()` 方法：实际执行并返回带执行耗时和统计信息的报告

**涉及文件**:
- `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java`

---

### [P1-2] `BatchingStrategy.unbatch()` 空实现 ✅

**问题描述**:  
`BatchingStrategy.unbatch()` 直接透传批量结果，未按 `originalQueries` 拆分到 individual `QueryResult`。

**修复内容**:
1. 修改 `unbatch()` 返回类型为 `List<QueryResult>`
2. 根据 `batch.getOriginalQueries()` 将批量结果拆分映射回各原始 ExternalQuery
3. 按 `inputIdCount` 比例分配实体到各 QueryResult

**涉及文件**:
- `src/main/java/com/federatedquery/executor/BatchingStrategy.java`

---

### [P1-3] 清理重复类 — `executor/` vs `aggregator/` ✅

**问题描述**:  
以下类在 `executor/` 和 `aggregator/` 两个包中各有一份：
- UnionDeduplicator, GlobalSorter, ResultStitcher, StitchedResult, PathBuilder

**修复内容**:
1. 删除 `executor/` 下的重复类：
   - `GlobalSorter.java`
   - `PathBuilder.java`
   - `ResultStitcher.java`
   - `StitchedResult.java`
   - `UnionDeduplicator.java`
2. 保留 `aggregator/` 下的实现（SDK 使用这些）

**涉及文件**:
- `src/main/java/com/federatedquery/executor/` (已删除重复类)

---

### [P1-4] 参数化查询安全加固 ✅

**问题描述**:  
当前 `GraphQuerySDK.resolveParameters()` 使用简单字符串替换，存在注入风险。

**修复内容**:
1. 添加 `isValidParameterName()` 方法，校验参数名格式（`^[a-zA-Z_][a-zA-Z0-9_]*$`）和长度（≤128）
2. 增强 `formatParameterValue()` 方法：
   - String 类型：转义 `\`, `'`, `"`, `\n`, `\r`, `\t`
   - Collection 类型：生成 Cypher 列表语法 `[...]`
   - Map 类型：生成 Cypher Map 语法 `{...}`
3. 跳过无效参数名并记录警告日志

**涉及文件**:
- `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java`

---

### [P1-5] Spring 依赖注入配置不完整 ✅

**问题描述**:  
以下类缺少 `@Component` 注解或构造函数中使用 `new` 创建依赖对象。

**修复内容**:
1. 为 `ResultStitcher`、`GlobalSorter`、`UnionDeduplicator` 添加 `@Component` 注解
2. 修改 `CypherParserFacade` 构造函数，通过构造函数注入 `CypherASTVisitor`
3. 修改 `QueryRewriter` 构造函数，通过构造函数注入 `WhereConditionPushdown`

**涉及文件**:
- `src/main/java/com/federatedquery/aggregator/ResultStitcher.java`
- `src/main/java/com/federatedquery/aggregator/GlobalSorter.java`
- `src/main/java/com/federatedquery/aggregator/UnionDeduplicator.java`
- `src/main/java/com/federatedquery/parser/CypherParserFacade.java`
- `src/main/java/com/federatedquery/rewriter/QueryRewriter.java`

---

## P2 — 中优先级（代码质量）

### [P2-1] 拼写错误 — `TUGRAH_BOLT` → `TUGRAPH_BOLT` ✅

**问题描述**:  
`DataSourceType.java` 枚举值拼写错误。

**修复内容**:
1. 重命名枚举值 `TUGRAH_BOLT` → `TUGRAPH_BOLT`
2. 更新 `E2ETest.java` 和 `RewriterTest.java` 中的引用

**涉及文件**:
- `src/main/java/com/federatedquery/metadata/DataSourceType.java`
- `src/test/java/com/federatedquery/e2e/E2ETest.java`
- `src/test/java/com/federatedquery/rewriter/RewriterTest.java`

---

### [P2-2] ThreadPool 无溢出保护 ✅

**问题描述**:  
`FederatedExecutor` 使用 `Executors.newFixedThreadPool(10)`，队列无界，高并发时可能导致 OOM。

**修复内容**:
1. 使用 `new ThreadPoolExecutor` 替代 `Executors.newFixedThreadPool`
2. 配置参数：
   - `CORE_POOL_SIZE = 10`
   - `MAX_POOL_SIZE = 20`
   - `QUEUE_CAPACITY = 100`
   - `KEEP_ALIVE_TIME = 60s`
3. 使用 `LinkedBlockingQueue` 有界队列
4. 配置 `CallerRunsPolicy` 作为拒绝策略

**涉及文件**:
- `src/main/java/com/federatedquery/executor/FederatedExecutor.java`

---

### [P2-3] 外部服务失败静默吞掉 ✅

**问题描述**:  
`FederatedExecutor.executeExternal()` 在外部服务不可用时返回 `QueryResult.partial()` 但 SDK 层从未读取 `warnings` 暴露给用户。

**修复内容**:
1. 在 `GraphQuerySDK.execute()` 中添加 `collectWarnings()` 方法
2. 收集 `ExecutionResult`、`QueryResult` 中的所有 warnings
3. 当存在 warnings 时，返回包含 `results` 和 `warnings` 字段的 JSON 响应

**涉及文件**:
- `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java`

---

### [P2-4] `ResultStitcher.buildRows()` 逻辑过于简化 ✅

**问题描述**:  
`buildRows()` 只处理第一个 `QueryResult` 的实体，忽略所有后续 `externalResults` 和其他 `physicalResults`。

**修复内容**:
1. 遍历所有 `physicalResults` 中的实体
2. 遍历所有 `externalResults` 中的实体
3. 遍历所有 `batchResults` 中的实体
4. 遍历所有 `unionResults` 中的实体
5. 为每个实体创建独立的行数据

**涉及文件**:
- `src/main/java/com/federatedquery/aggregator/ResultStitcher.java`

---

### [P2-5] 外部服务重试机制未实现 ✅

**问题描述**:  
`DataSourceMetadata` 定义了 `maxRetries = 3` 但从未被使用。

**修复内容**:
1. 添加 `DEFAULT_MAX_RETRIES = 3` 和 `RETRY_DELAY_MS = 100` 常量
2. 添加 `getMaxRetries()` 方法，从 `DataSourceMetadata` 获取重试次数
3. 添加 `executeWithRetry()` 和 `executeWithRetryInternal()` 方法
4. 实现指数退避重试逻辑（每次重试延迟增加）
5. 在 `executeExternal()` 中使用重试机制

**涉及文件**:
- `src/main/java/com/federatedquery/executor/FederatedExecutor.java`

---

## 汇总

| 优先级 | 编号 | 问题 | 工作量 | 状态 |
|--------|------|------|--------|------|
| 🔴 P0 | P0-1 | pendingFilters 过滤未应用 | 小 | ✅ 已修复 |
| 🔴 P0 | P0-2 | deduplicate() 未调用 | 小 | ✅ 已修复 |
| 🟡 P1 | P1-1 | EXPLAIN/PROFILE 未执行 | 小 | ✅ 已修复 |
| 🟡 P1 | P1-2 | unbatch() 空实现 | 小 | ✅ 已修复 |
| 🟡 P1 | P1-3 | 清理 executor/ 重复类 | 中 | ✅ 已修复 |
| 🟡 P1 | P1-4 | 参数化查询安全加固 | 小 | ✅ 已修复 |
| 🟡 P1 | P1-5 | Spring DI 配置不完整 | 小 | ✅ 已修复 |
| 🟢 P2 | P2-1 | TUGRAPH_BOLT 拼写修正 | 微 | ✅ 已修复 |
| 🟢 P2 | P2-2 | ThreadPool 溢出保护 | 小 | ✅ 已修复 |
| 🟢 P2 | P2-3 | 外部服务失败暴露用户 | 小 | ✅ 已修复 |
| 🟡 P2 | P2-4 | ResultStitcher.buildRows() 简化 | 中 | ✅ 已修复 |
| 🟢 P2 | P2-5 | 重试机制未实现 | 小 | ✅ 已修复 |

> **说明**: 本项目专注于 Cypher 解析和执行计划生成，所有数据源（包括 TuGraph 和外部服务）均使用 `MockExternalAdapter` 模拟，不提供生产级数据源适配器实现。

---

**文档维护人**: AI Assistant  
**创建日期**: 2026-04-07  
**完成日期**: 2026-04-07
