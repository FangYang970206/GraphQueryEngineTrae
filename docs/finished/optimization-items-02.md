# 优化待办事项 - 已完成（02）

**创建日期**: 2026-04-08  
**完成日期**: 2026-04-08  
**来源文档**: `docs/fixBug/2026-04-08-functional-bugs.md`  
**范围**: 重写层、执行层、聚合与输出层、测试验证  

---

## P0 — 查询正确性（已完成）

### [P0-1] 虚拟边“夹心结构”校验顺序错误 ✅
**修复内容**:
1. 在检测结果中记录边的原始虚实顺序序列
2. 基于该序列执行 `[physical]->[virtual]->[physical]` 约束校验
3. 移除“先拼物理再拼虚拟”的错误顺序判断

**涉及文件**:
- [VirtualEdgeDetector](file:///d:/AI_Project/GraphQueryEngine/src/main/java/com/federatedquery/rewriter/VirtualEdgeDetector.java#L84-L191)

---

### [P0-2] 混合关系类型下物理查询夹带虚拟边类型 ✅
**修复内容**:
1. 物理边分支仅写入当前遍历的 `relType`
2. 避免将 `rel.getRelationshipTypes()` 全量写入物理边

**涉及文件**:
- [VirtualEdgeDetector](file:///d:/AI_Project/GraphQueryEngine/src/main/java/com/federatedquery/rewriter/VirtualEdgeDetector.java#L84-L124)

---

### [P0-3] 外部查询批处理丢失 filters/snapshot ✅
**修复内容**:
1. 为 `BatchRequest` 增加 `filters/parameters/snapshotName/snapshotTime`
2. `BatchingStrategy` 在构建批请求时透传上述字段
3. `FederatedExecutor.executeBatch` 组装 `combinedQuery` 时完整回填

**涉及文件**:
- [BatchRequest](file:///d:/AI_Project/GraphQueryEngine/src/main/java/com/federatedquery/executor/BatchRequest.java#L12-L23)
- [BatchingStrategy](file:///d:/AI_Project/GraphQueryEngine/src/main/java/com/federatedquery/executor/BatchingStrategy.java#L12-L164)
- [FederatedExecutor.executeBatch](file:///d:/AI_Project/GraphQueryEngine/src/main/java/com/federatedquery/executor/FederatedExecutor.java#L197-L220)

---

### [P0-4] 外部查询 inputIds 缺失导致空批 ✅
**修复内容**:
1. 执行器按查询特征分流：有 `inputIds` 才进入批处理
2. 无 `inputIds` 的外部查询走 direct 执行并写入 `externalResults`
3. `unbatch()` 在 `inputIdCount=0` 场景按安全分配策略回填结果

**涉及文件**:
- [FederatedExecutor.execute](file:///d:/AI_Project/GraphQueryEngine/src/main/java/com/federatedquery/executor/FederatedExecutor.java#L59-L117)
- [BatchingStrategy.unbatch](file:///d:/AI_Project/GraphQueryEngine/src/main/java/com/federatedquery/executor/BatchingStrategy.java#L88-L140)

---

### [P0-5] `execute()` 变量映射错配（变量名 vs 实体ID） ✅
**修复内容**:
1. `execute()` 改为复用与 `executeRaw()` 一致的结果构建路径
2. 移除重复批结果叠加导致的数据重复路径
3. 保持结果转换、过滤、排序、去重流程一致

**涉及文件**:
- [GraphQuerySDK.execute](file:///d:/AI_Project/GraphQueryEngine/src/main/java/com/federatedquery/sdk/GraphQuerySDK.java#L51-L110)
- [GraphQuerySDK.buildTuGraphFormatResults](file:///d:/AI_Project/GraphQueryEngine/src/main/java/com/federatedquery/sdk/GraphQuerySDK.java#L504-L607)
- [ResultStitcher.buildRows](file:///d:/AI_Project/GraphQueryEngine/src/main/java/com/federatedquery/aggregator/ResultStitcher.java#L49-L82)

---

## P1 — 约束一致性与语义（已完成）

### [P1-1] 只读约束未显式执行 ✅
**修复内容**:
1. AST 层标记 `SingleQuery` 是否含 updating clause
2. 重写阶段遇到 updating clause 直接抛出不支持异常
3. 新增对应单测验证只读拒绝行为

**涉及文件**:
- [Statement.SingleQuery](file:///d:/AI_Project/GraphQueryEngine/src/main/java/com/federatedquery/ast/Statement.java#L117-L228)
- [CypherASTVisitor](file:///d:/AI_Project/GraphQueryEngine/src/main/java/com/federatedquery/parser/CypherASTVisitor.java#L94-L117)
- [QueryRewriter.rewriteSingleQuery](file:///d:/AI_Project/GraphQueryEngine/src/main/java/com/federatedquery/rewriter/QueryRewriter.java#L80-L91)
- [RewriterTest.rejectUpdatingClause](file:///d:/AI_Project/GraphQueryEngine/src/test/java/com/federatedquery/rewriter/RewriterTest.java#L165-L170)

---

### [P1-2] 多 UNION 场景 `UNION ALL` 语义被覆盖 ✅
**修复内容**:
1. `UnionPart.all` 初始化为 `true`
2. 每段 union 以与逻辑聚合，混合 `UNION`/`UNION ALL` 时最终为 `false`
3. 增加混合 UNION 语义测试

**涉及文件**:
- [QueryRewriter.rewriteUnionQuery](file:///d:/AI_Project/GraphQueryEngine/src/main/java/com/federatedquery/rewriter/QueryRewriter.java#L59-L77)
- [RewriterTest.rewriteMixedUnionAll](file:///d:/AI_Project/GraphQueryEngine/src/test/java/com/federatedquery/rewriter/RewriterTest.java#L155-L163)

---

## P2 — 稳定性与测试门禁（已完成）

### [P2-1] 线程池创建未参与执行调度 ✅
**修复内容**:
1. 统一通过受控线程池调度 physical/external/batch/union 执行链
2. 增加 `runOnExecutor(...)` 作为统一提交入口
3. 保留超时、重试与异常处理语义

**涉及文件**:
- [FederatedExecutor.execute](file:///d:/AI_Project/GraphQueryEngine/src/main/java/com/federatedquery/executor/FederatedExecutor.java#L59-L117)
- [FederatedExecutor.runOnExecutor](file:///d:/AI_Project/GraphQueryEngine/src/main/java/com/federatedquery/executor/FederatedExecutor.java#L290-L291)

---

### [P2-2] 测试门禁缺口与主入口覆盖不足 ✅
**修复内容**:
1. 增加 `sdk.execute(...)` 主入口 E2E 覆盖
2. 修正弱断言（`size > 0`）为精确数量断言
3. 增加执行器 direct 外部查询路径断言

**涉及文件**:
- [E2ETest.simpleMatchQueryWithExecute](file:///d:/AI_Project/GraphQueryEngine/src/test/java/com/federatedquery/e2e/E2ETest.java#L453-L470)
- [ExecutorTest.timeoutHandling](file:///d:/AI_Project/GraphQueryEngine/src/test/java/com/federatedquery/executor/ExecutorTest.java#L115-L138)

---

## 验证记录
- `mvn compile -q`：通过
- `mvn test -q`：通过

---

## 汇总

| 优先级 | 编号 | 问题 | 状态 |
|--------|------|------|------|
| 🔴 P0 | P0-1 | 虚拟边夹心约束顺序校验错误 | ✅ 已修复 |
| 🔴 P0 | P0-2 | 混合关系类型污染物理查询 | ✅ 已修复 |
| 🔴 P0 | P0-3 | 批处理丢失 filters/snapshot | ✅ 已修复 |
| 🔴 P0 | P0-4 | inputIds 缺失导致空批 | ✅ 已修复 |
| 🔴 P0 | P0-5 | execute 变量映射错配 | ✅ 已修复 |
| 🟡 P1 | P1-1 | 只读约束未执行 | ✅ 已修复 |
| 🟡 P1 | P1-2 | UNION ALL 语义覆盖 | ✅ 已修复 |
| 🟢 P2 | P2-1 | 线程池未参与执行调度 | ✅ 已修复 |
| 🟢 P2 | P2-2 | 测试门禁与主入口覆盖不足 | ✅ 已修复 |

---

**文档维护人**: AI Assistant  
**创建日期**: 2026-04-08  
**完成日期**: 2026-04-08
