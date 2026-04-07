# GraphQueryEngine 功能问题清单（深度代码审查）

## 审查范围
- 解析层：`parser`、ANTLR 语法定义
- 改写层：`rewriter`
- 执行层：`executor`
- 聚合与输出层：`aggregator`、`sdk`
- 测试与验证：`src/test`、`mvn compile`、`mvn test`

## 问题清单

### 1) 虚拟边“夹心结构”校验顺序错误（高）
- **现象**：`[physical]->[virtual]->[physical]` 约束检测依赖边序，但实现中先拼接全部物理边，再拼接全部虚拟边，丢失原路径顺序。
- **证据**：`VirtualEdgeDetector.validateConstraints` 将 `physicalEdgeParts` 与 `virtualEdgeParts` 分别追加到 `allEdges`，而不是按模式链路顺序构建。
- **代码位置**：`src/main/java/com/federatedquery/rewriter/VirtualEdgeDetector.java:127-154`
- **影响**：可能误判或漏判非法路径，导致约束失效。

### 2) 混合关系类型下物理查询会错误包含虚拟边类型（高）
- **现象**：当关系类型包含多个值（如 `[:A|B]`）时，循环检测到某个类型为物理后，`PhysicalEdgePart` 却写入 `rel.getRelationshipTypes()` 全量列表。
- **证据**：`checkRelationshipVirtual` 的物理分支使用 `part.setEdgeTypes(rel.getRelationshipTypes())`，而非当前 `relType`。
- **代码位置**：`src/main/java/com/federatedquery/rewriter/VirtualEdgeDetector.java:84-124`
- **影响**：改写生成的物理 Cypher 可能混入虚拟边类型，导致 TuGraph 执行语义错误。

### 3) 只读约束未被显式执行（高）
- **现象**：语法允许 `CREATE/MERGE/DELETE/SET/REMOVE`，但 AST 构建与重写流程未见“写语句拒绝”逻辑。
- **证据**：
  - 语法层定义了 `oC_UpdatingClause` 及写语句关键字；
  - `CypherASTVisitor.visitOC_SinglePartQuery` 仅处理 `oC_ReadingClause` + `oC_Return` 路径。
- **代码位置**：
  - `src/main/antlr4/com/federatedquery/grammar/Lcypher.g4:81-90,112-144`
  - `src/main/java/com/federatedquery/parser/CypherASTVisitor.java:94-109`
- **影响**：与“只读引擎”约束不一致，写语句可能被静默忽略或产生不确定行为。

### 4) 外部查询批处理丢失过滤条件与快照信息（高）
- **现象**：重写阶段为 `ExternalQuery` 注入 `filters`/snapshot，但批处理组合时只复制基础字段，不复制过滤与快照。
- **证据**：
  - 重写注入过滤：`applyVirtualConditionsToExternalQuery`;
  - `BatchRequest` 无 `filters/snapshot` 字段；
  - `executeBatch` 创建 `combinedQuery` 时未携带上述信息。
- **代码位置**：
  - `src/main/java/com/federatedquery/rewriter/QueryRewriter.java:230-237,391-399`
  - `src/main/java/com/federatedquery/executor/BatchRequest.java:10-19`
  - `src/main/java/com/federatedquery/executor/FederatedExecutor.java:182-191`
- **影响**：虚拟条件过滤失效、快照语义丢失，查询结果可能偏大或不一致。

### 5) 外部查询输入 ID 未构建，批处理可能退化为空批（高）
- **现象**：改写阶段只设置 `inputIdField`，未填 `inputIds`；批策略按 `inputIds` 聚合。
- **证据**：
  - `createExternalQuery` 仅设置 `setInputIdField`；
  - `BatchingStrategy.batch` 使用 `allInputIds.addAll(q.getInputIds())`，为空则构造空批次请求。
- **代码位置**：
  - `src/main/java/com/federatedquery/rewriter/QueryRewriter.java:351-360`
  - `src/main/java/com/federatedquery/executor/BatchingStrategy.java:29-58`
- **影响**：外部查询调用缺失关键输入，导致返回空结果或错误结果。

### 6) `execute()` 与 `executeRaw()` 结果构建逻辑不一致，`execute()` 可能错配返回变量（高）
- **现象**：
  - `execute()` 路径使用 `stitched.getEntityById()`，再按 `return variable` 取值；
  - `entityById` 的 key 是实体 ID，不是返回变量名。
- **证据**：
  - `ResultStitcher` 以 `entity.getId()` 建索引；
  - `GraphQuerySDK.buildTuGraphFormatResults(Program, StitchedResult, ExecutionResult)` 以 `entityById.get(varName)` 取值。
- **代码位置**：
  - `src/main/java/com/federatedquery/aggregator/ResultStitcher.java:18-37`
  - `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java:474-483`
- **影响**：`execute()` 可能出现返回变量与实体错配、回退分支结果不稳定；且当前测试主要走 `executeRaw()`，该问题容易被遗漏。

### 7) 多 UNION 场景下 `UNION ALL` 语义可能被覆盖（中）
- **现象**：循环中反复 `unionPart.setAll(union.isAll())`，最终只保留最后一次值。
- **证据**：`rewriteUnionQuery` 处理多个 union 子句时使用单一布尔字段覆盖。
- **代码位置**：`src/main/java/com/federatedquery/rewriter/QueryRewriter.java:59-74`
- **影响**：混合 `UNION`/`UNION ALL` 时去重策略可能错误。

### 8) 自定义线程池未参与实际异步执行（中）
- **现象**：`executorService` 被创建并在 shutdown 时释放，但执行链未将任务提交到该线程池。
- **证据**：`executorService` 仅出现于构造与关闭逻辑。
- **代码位置**：`src/main/java/com/federatedquery/executor/FederatedExecutor.java:28-44,260-269`
- **影响**：并发调度策略与配置意图不一致，可能造成吞吐与资源控制偏差。

### 9) 测试质量门禁存在缺口：`mvn test` 当前失败且关键路径覆盖不足（中）
- **现象**：
  - 本地执行 `mvn compile -q` 成功后，`mvn test -q` 仍在 `StringOperatorParseTest` 处编译失败（`SingleQuery` 解析失败）；
  - 测试中未见 `sdk.execute(...)` 覆盖，主要覆盖 `executeRaw(...)`。
- **证据**：
  - 命令输出：`src/test/java/com/federatedquery/parser/StringOperatorParseTest.java` 报编译错误；
  - 搜索结果：`src/test` 无 `sdk.execute(` 调用。
- **代码位置**：
  - `src/test/java/com/federatedquery/parser/StringOperatorParseTest.java`
  - `src/test/java/**`（`sdk.execute(` 无命中）
- **影响**：主入口行为缺少回归保障，且测试集当前不可作为稳定质量门禁。

## 验证记录
- `mvn compile -q`：通过
- `mvn test -q`：失败（`StringOperatorParseTest` 编译错误）

## 建议修复优先级
- **P0**：问题 1 / 2 / 4 / 5 / 6（直接影响查询正确性）
- **P1**：问题 3 / 7（约束一致性与 UNION 语义）
- **P2**：问题 8 / 9（执行稳定性与测试门禁）
