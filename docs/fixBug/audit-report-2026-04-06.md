# 图联邦查询引擎项目审计报告

**审计日期**: 2026-04-06  
**审计范围**: 功能完整性、规格一致性、测试覆盖

---

## 一、功能缺失问题

### 1. ❌ USING SNAPSHOT 语法功能未实现

**位置**: `src/main/java/com/federatedquery/rewriter/QueryRewriter.java`

**现状**:
- 语法在 `Lcypher.g4` 中已定义 (第47-49行)
- AST 节点 `UsingSnapshot.java` 已存在
- `CypherASTVisitor` 中有解析逻辑 (第640-657行)

**问题**:
- `QueryRewriter` 中**没有任何代码处理** `UsingSnapshot`
- snapshot 信息未传递给外部查询或物理查询
- 设计文档示例3要求的 `USING SNAPSHOT('latest', 1) ON ['Card']` 无法生效

**影响**: 无法实现时间旅行查询功能

---

### 2. ❌ PROJECT BY 字段投影功能未实现

**位置**: `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java`

**现状**:
- 语法在 `Lcypher.g4` 中已定义 (第51-57行)
- AST 节点 `ProjectBy.java` 已存在
- `GlobalContext` 中存储了 `projectBy` (第17行)
- `QueryRewriter` 将 `projectBy` 存入 `GlobalContext` (第40-42行)

**问题**:
- `GraphQuerySDK.buildTuGraphFormatResults()` 中**未应用字段投影**
- 返回结果包含所有字段，未按 `PROJECT BY {'Card': ['name']}` 过滤

**影响**: 无法控制返回字段，可能暴露敏感数据或返回过多数据

---

### 3. ⚠️ WHERE 条件下推功能未完全集成

**位置**: 
- `src/main/java/com/federatedquery/reliability/WhereConditionPushdown.java`
- `src/main/java/com/federatedquery/rewriter/QueryRewriter.java`

**现状**:
- `WhereConditionPushdown` 类可分析并分离物理/虚拟条件
- `QueryRewriter.extractConditions()` 提取条件到 `pendingFilters`

**问题**:
- `QueryRewriter` **未使用** `WhereConditionPushdown` 类
- 虚拟节点的 WHERE 条件**未传递**给外部查询
- 物理查询可能错误包含虚拟节点条件

**设计文档要求**:
> 如果用户输入：`MATCH (ne)-[:NEHasKPI]->(target) WHERE target.value > 90`
> 属于 `target`（虚拟节点）的 WHERE 条件**绝对不能**扔给 TuGraph 物理查询执行

---

### 4. ❌ 示例4 纯外部数据源查询测试缺失

**设计文档定义**:
```cypher
match (card:Card {name: 'card001'}) where card.resId='2131221';
```
其中 `Card` 是外部数据源（虚拟标签）

**E2E测试实际** (`E2ETest.java:352-378`):
```cypher
MATCH (n:NetworkElement) RETURN n
```
这是**纯物理查询**，不是纯外部数据源查询

**问题**: 未测试纯虚拟标签查询场景

---

### 5. ❌ 外部服务超时配置缺失

**位置**: `src/main/java/com/federatedquery/executor/FederatedExecutor.java`

**设计文档要求**:
> 外部系统可能响应缓慢甚至宕机。调用外部 API 配置超时时间（如 30 秒），如果外部请求超时，直接报错。

**现状**:
- `FederatedExecutor` 中无超时配置
- `DataSourceAdapter.execute()` 返回 `CompletableFuture` 但无超时控制
- SPEC.md 错误示例显示应有超时配置

---

### 6. ❌ 参数化查询支持缺失

**位置**: `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java`

**SPEC.md 规范** (第249-252行):
```java
Map<String, Object> params = new HashMap<>();
params.put("name", userInput);
sdk.execute("MATCH (n:NetworkElement {name: $name}) RETURN n", params);
```

**现状**:
- `execute(String cypher)` 只接受 cypher 字符串
- **不支持** `execute(String cypher, Map<String, Object> params)` 方法签名
- `Parameter` AST 节点存在但未被使用

---

### 7. ❌ MultiPartQuery (WITH 子句) 处理缺失

**位置**: `CypherASTVisitor.java:108-109`

```java
private void visitOC_MultiPartQuery(LcypherParser.OC_MultiPartQueryContext ctx, Statement.SingleQuery singleQuery) {
    // 空实现
}
```

**问题**: 不支持包含 `WITH` 子句的复杂查询

---

### 8. ⚠️ 全局排序和分页未完全实现

**位置**: `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java`

**现状**:
- `GlobalContext` 中有 `globalOrder` 和 `globalLimit`
- `QueryRewriter` 中提取了 ORDER BY 和 LIMIT
- `GlobalSorter` 类存在

**问题**:
- `GraphQuerySDK.execute()` 中**未调用** `GlobalSorter`
- 结果返回时未应用全局排序和分页

**设计文档要求**:
> 物理查询需要拉取全量候选数据集，合并外部数据后，统一在 Java 层执行最终排序和分页

---

## 二、规格不一致问题

### 9. SPEC.md 与实际实现不一致

| 功能 | SPEC.md 描述 | 实际实现 |
|------|-------------|---------|
| 参数化查询 | 支持 `$name` 参数 | ❌ 不支持 |
| 外部服务超时 | 配置超时时间 | ❌ 无配置 |
| PROJECT BY | 字段投影 | ⚠️ 解析存在，未应用 |
| USING SNAPSHOT | 时间旅行查询 | ⚠️ 解析存在，未处理 |

---

## 三、测试覆盖问题

### 10. E2E测试覆盖不足

| 示例 | 设计文档要求 | E2E测试覆盖 |
|------|-------------|------------|
| 示例3 | `USING SNAPSHOT` + `PROJECT BY` | ❌ 未测试这两个语法 |
| 示例4 | 纯外部数据源查询 | ❌ 测试的是纯物理查询 |
| 示例5 | 外部→内部关联 | ⚠️ 测试存在但未验证完整流程 |

---

## 四、建议优先级

| 优先级 | 问题 | 影响 |
|--------|------|------|
| **P0** | WHERE 条件下推未集成 | 可能导致查询错误或性能问题 |
| **P0** | 全局排序/分页未实现 | 结果可能不正确 |
| **P1** | 外部服务超时配置缺失 | 生产环境可靠性风险 |
| **P1** | 参数化查询不支持 | 安全风险（SQL注入类问题） |
| **P2** | USING SNAPSHOT 未实现 | 功能缺失 |
| **P2** | PROJECT BY 未应用 | 功能缺失 |
| **P2** | 示例4测试缺失 | 测试覆盖不足 |
| **P3** | WITH 子句不支持 | 功能限制 |

---

## 五、修复建议

### P0 问题修复方案

#### 5.1 WHERE 条件下推集成

1. 在 `QueryRewriter` 中注入 `WhereConditionPushdown`
2. 在 `rewriteMatchClause()` 中调用 `WhereConditionPushdown.analyze()`
3. 将虚拟条件传递给 `ExternalQuery`
4. 将物理条件附加到 `PhysicalQuery`

#### 5.2 全局排序/分页实现

1. 在 `GraphQuerySDK.execute()` 中调用 `GlobalSorter.sort()`
2. 应用 `LimitSpec` 进行分页
3. 确保在结果聚合后执行

### P1 问题修复方案

#### 5.3 外部服务超时配置

1. 在 `DataSourceAdapter` 接口中添加超时配置
2. 在 `FederatedExecutor` 中使用 `CompletableFuture.orTimeout()`
3. 添加配置项到 `DataSourceMetadata`

#### 5.4 参数化查询支持

1. 添加 `execute(String cypher, Map<String, Object> params)` 方法
2. 在 `CypherParserFacade` 中支持参数替换
3. 在 `ExternalQuery` 中传递参数

---

## 六、相关文件清单

| 文件路径 | 问题关联 |
|---------|---------|
| `src/main/java/com/federatedquery/rewriter/QueryRewriter.java` | #1, #3, #8 |
| `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java` | #2, #6, #8 |
| `src/main/java/com/federatedquery/executor/FederatedExecutor.java` | #5 |
| `src/main/java/com/federatedquery/reliability/WhereConditionPushdown.java` | #3 |
| `src/main/java/com/federatedquery/parser/CypherASTVisitor.java` | #7 |
| `src/test/java/com/federatedquery/e2e/E2ETest.java` | #4, #10 |
| `docs/SPEC.md` | #9 |

---

**审计人**: AI Assistant  
**状态**: 已全部修复 (P0-P3问题均已修复并验证)
