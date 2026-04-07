# 优化待办事项

**创建日期**: 2026-04-07  
**依据**: 项目审计报告 v2  
**范围**: 功能缺口、架构问题、代码质量

---

## P0 — 必须修复（阻塞正确性）

### [P0-1] `pendingFilters` 数据流断裂 — WHERE 虚拟条件未过滤结果

**问题描述**:  
`QueryRewriter.whereConditionPushdown.analyze()` 正确收集了 `isVirtual=true` 的条件到 `GlobalContext.pendingFilters`，但 `GraphQuerySDK.buildTuGraphFormatResults()` 从未读取 `stitched.getPendingFilters()` 对结果进行过滤。

**数据流断裂点**:
```
QueryRewriter.analyze() → GlobalContext.pendingFilters (isVirtual=true)
→ StitchedResult.pendingFilters → 从不读取
```

**影响**: `WHERE target.value > 90` 这类虚拟节点条件被静默忽略，用户查不到正确结果。

**涉及文件**:
- `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java`

**建议修复**:
在 `buildTuGraphFormatResults()` 返回前，读取 `stitched.getPendingFilters()`，对每行结果按 `isVirtual=true` 的条件进行过滤。

**状态**: 🔴 待处理

---

### [P0-2] `deduplicate()` 注入但从未调用 — UNION 去重不工作

**问题描述**:  
`GraphQuerySDK` 注入了 `UnionDeduplicator deduplicator`（第30行），但整个类中零调用。

**涉及文件**:
- `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java`
- `src/main/java/com/federatedquery/aggregator/UnionDeduplicator.java`

**影响**: `UNION DISTINCT` 查询会产生重复行。

**建议修复**:
1. 在 `execute()` 或 `executeRaw()` 的结果返回前，调用 `deduplicator.deduplicateRows()` 进行去重
2. 对 Path 返回结果调用 `deduplicator.deduplicate()` 进行去重

**状态**: 🔴 待处理

---

## P1 — 高优先级（功能完整性）

### [P1-1] `EXPLAIN`/`PROFILE` — 语法解析了但从未执行

**问题描述**:  
`CypherASTVisitor.visitOC_Statement()` 正确解析了 `EXPLAIN`/`PROFILE` 标记到 `Statement`，但后续 pipeline 完全忽略这些标志。

**涉及文件**:
- `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java`
- `src/main/java/com/federatedquery/parser/CypherASTVisitor.java`

**建议修复**:
在 `execute()` 入口检测 `Statement.isExplain()` / `isProfile()`：
- `EXPLAIN`: 返回逻辑执行计划（不实际执行），包含 PhysicalQuery 和 ExternalQuery 的分解结果
- `PROFILE`: 实际执行并返回带执行耗时和统计信息的报告

**状态**: 🟡 待处理

---

### [P1-2] `BatchingStrategy.unbatch()` 空实现

**问题描述**:  
`BatchingStrategy.unbatch()` 直接透传批量结果，未按 `originalQueries` 拆分到 individual `QueryResult`。导致结果与原始查询的对应关系丢失。

**涉及文件**:
- `src/main/java/com/federatedquery/executor/BatchingStrategy.java:80-82`

**建议修复**:
根据 `batch.getOriginalQueries()` 将批量结果拆分映射回各原始 ExternalQuery，返回拆分后的 `List<QueryResult>`。

**状态**: 🟡 待处理

---

### [P1-3] 清理重复类 — `executor/` vs `aggregator/`

**问题描述**:  
以下类在 `executor/` 和 `aggregator/` 两个包中各有一份：

| 类 | executor/ | aggregator/ | SDK 使用 |
|---|---|---|---|
| UnionDeduplicator | ✅ | ✅ | aggregator |
| GlobalSorter | ✅ | ✅ | aggregator |
| ResultStitcher | ✅ | ✅ | aggregator |
| StitchedResult | ✅ | ✅ | aggregator |
| PathBuilder | ✅ | ✅ | (无) |
| PagedResult | ✅ (嵌套类) | ✅ (嵌套类) | (无) |

`executor/` 下的所有类从未被主代码路径使用。

**涉及文件**:
- `src/main/java/com/federatedquery/executor/UnionDeduplicator.java`
- `src/main/java/com/federatedquery/executor/GlobalSorter.java`
- `src/main/java/com/federatedquery/executor/ResultStitcher.java`
- `src/main/java/com/federatedquery/executor/StitchedResult.java`
- `src/main/java/com/federatedquery/executor/PathBuilder.java`

**建议修复**:
1. 保留 `aggregator/` 下的实现（SDK 使用这些）
2. 删除 `executor/` 下的重复类
3. 将 `PathBuilder` 移入 `aggregator/`（如需要）

**状态**: 🟡 待处理

---

### [P1-4] 参数化查询安全加固

**问题描述**:  
当前 `GraphQuerySDK.resolveParameters()` 使用简单字符串替换，存在注入风险。`formatParameterValue()` 仅做基础转义。

**涉及文件**:
- `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java:98-127`

**建议修复**:
1. 对非 String 类型（数组、Map）添加转义
2. 添加参数名白名单校验
3. 考虑使用 ANTLR 重写参数替换逻辑

**状态**: 🟡 待处理

---

## P2 — 中优先级（代码质量）

### [P2-1] 拼写错误 — `TUGRAH_BOLT` → `TUGRAPH_BOLT`

**问题描述**:  
`DataSourceType.java` 枚举值拼写错误，`E2ETest` 和 `RewriterTest` 也用了这个错误值。

**涉及文件**:
- `src/main/java/com/federatedquery/metadata/DataSourceType.java:4`
- `src/test/java/com/federatedquery/e2e/E2ETest.java`
- `src/test/java/com/federatedquery/rewriter/RewriterTest.java`

**建议修复**:
1. 重命名枚举值 `TUGRAH_BOLT` → `TUGRAPH_BOLT`
2. 更新所有引用处

**状态**: 🟢 待处理

---

### [P2-2] ThreadPool 无溢出保护

**问题描述**:  
`FederatedExecutor` 使用 `Executors.newFixedThreadPool(10)`，队列无界，高并发时可能导致 OOM。

**涉及文件**:
- `src/main/java/com/federatedquery/executor/FederatedExecutor.java:28`

**建议修复**:
使用 `new ThreadPoolExecutor` 并配置 `LinkedBlockingQueue` 有界队列 + `RejectedExecutionHandler`。

**状态**: 🟢 待处理

---

### [P2-3] 外部服务失败静默吞掉

**问题描述**:  
`FederatedExecutor.executeExternal()` 在外部服务不可用时返回 `QueryResult.partial()` 但 SDK 层从未读取 `warnings` 暴露给用户。

**涉及文件**:
- `src/main/java/com/federatedquery/executor/FederatedExecutor.java:107-110`
- `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java`

**建议修复**:
在 `GraphQuerySDK` 的 `buildErrorResponse()` 或结果 JSON 中暴露 `warnings` 字段。

**状态**: 🟢 待处理

---

### [P2-4] `ResultStitcher.buildRows()` 逻辑过于简化

**问题描述**:  
`buildRows()` 只处理第一个 `QueryResult` 的实体，忽略所有后续 `externalResults` 和其他 `physicalResults`。

**涉及文件**:
- `src/main/java/com/federatedquery/aggregator/ResultStitcher.java:47-68`

**建议修复**:
遍历所有 `physicalResults`、`externalResults`、`batchResults` 中的实体，正确组装行数据。

**状态**: 🟡 待处理

---

### [P2-5] 外部服务重试机制未实现

**问题描述**:  
`DataSourceMetadata` 定义了 `maxRetries = 3` 但从未被使用。

**涉及文件**:
- `src/main/java/com/federatedquery/metadata/DataSourceMetadata.java:12`
- `src/main/java/com/federatedquery/executor/FederatedExecutor.java`

**建议修复**:
在 `FederatedExecutor` 的各 `execute*` 方法中添加重试逻辑。

**状态**: 🟢 待处理

---

## 汇总

| 优先级 | 编号 | 问题 | 工作量 |
|--------|------|------|--------|
| 🔴 P0 | P0-1 | pendingFilters 过滤未应用 | 小 |
| 🔴 P0 | P0-2 | deduplicate() 未调用 | 小 |
| 🟡 P1 | P1-1 | EXPLAIN/PROFILE 未执行 | 小 |
| 🟡 P1 | P1-2 | unbatch() 空实现 | 小 |
| 🟡 P1 | P1-3 | 清理 executor/ 重复类 | 中 |
| 🟡 P1 | P1-4 | 参数化查询安全加固 | 小 |
| 🟢 P2 | P2-1 | TUGRAH_BOLT 拼写修正 | 微 |
| 🟢 P2 | P2-2 | ThreadPool 溢出保护 | 小 |
| 🟢 P2 | P2-3 | 外部服务失败暴露用户 | 小 |
| 🟡 P2 | P2-4 | ResultStitcher.buildRows() 简化 | 中 |
| 🟢 P2 | P2-5 | 重试机制未实现 | 小 |

> **说明**: 本项目专注于 Cypher 解析和执行计划生成，所有数据源（包括 TuGraph 和外部服务）均使用 `MockExternalAdapter` 模拟，不提供生产级数据源适配器实现。

---

**文档维护人**: AI Assistant  
**最后更新**: 2026-04-07
