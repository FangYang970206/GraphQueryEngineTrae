# GraphQueryEngine 坏味道清单

> 本文档按文件整理已确认的坏味道，供后续分批规划使用。
> 口径来源以 `badSmell.md` 为准；这里只收录“已确认”或“高价值候选”项，不再混入一般代码风格建议。

---

## 1. 核心生产代码

### 1.1 `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java`

**优先级**: P1

**已确认坏味道**

- 过大的类
  - 公共 API、执行编排、参数替换、结果构造、路径重建、排序分页、去重、Explain/Profile 全部集中在一个类。
- 过长函数
  - `buildTuGraphFormatResults`
  - `buildRowResults`
  - `buildPathVariantsRecursiveWithRow`
  - `buildPathVariantsRecursive`
- 过长参数列表
  - `buildPathVariantsRecursiveWithRow`
- 发散式变化
  - 查询执行规则、结果协议、路径规则、排序分页任一变化都容易改动同一类。
- 重复代码
  - 过滤、实体遍历、路径元素构造等模式在类内多处重复。
- 依恋情结
  - 深入访问 AST、`ExecutionResult`、行级 Map 结构与 `GraphEntity` 内部状态。
- 过长消息链
  - 多段 AST 链式读取、Map 级联读取。
- 循环语句
  - 存在 2-3 层嵌套遍历。
- 内幕交易
  - 对结果行结构、节点/边输出结构的了解过深。

**建议方向**

- 先按职责拆：API 协调、参数解析、结果转换、路径构建、排序分页。
- 先做“行为搬移 + 提取方法”，再考虑组件化。
- 重构时必须保住虚拟边边界规则、WHERE pushdown 规则和全局排序/分页语义。

---

### 1.2 `src/main/java/com/federatedquery/executor/FederatedExecutor.java`

**优先级**: P1

**已确认坏味道**

- 过大的类
- 过长函数
  - `executeWithDependencyAwareness`
  - `enrichExternalResultWithSourceRows`
- 过长参数列表
  - `executeWithDependencyAwareness`
- 发散式变化
  - 并发、依赖查询、批处理、Union、异常包装都在同类中。
- 循环语句
  - 多处 3 层嵌套遍历结果和行。
- 过长消息链
  - `row -> entitiesByVariable -> entity -> properties` 模式反复出现。
- 内幕交易
  - 深知 `ExternalQuery`、`ResultRow`、`GraphEntity` 的细节结构。
- 可变数据
  - 与 `ExecutionResult`、`QueryResult`、`ExternalQuery` 高度依赖可变状态协作。

**建议方向**

- 先拆“执行阶段”，再拆“结果 enrichment / provenance 回填”。
- 引入上下文对象前，要先明确哪些字段是真正一起变化的。

---

### 1.3 `src/main/java/com/federatedquery/rewriter/QueryRewriter.java`

**优先级**: P1

**已确认坏味道**

- 过大的类
- 发散式变化
  - 单查询、Union、多段查询、混合图样、条件下推、Snapshot 透传全部集中。
- 过长函数
  - `rewriteMixedPattern`
  - `createPhysicalQueryFromParts`
- 依恋情结
  - 深度依赖 `VirtualEdgeDetector.VirtualEdgePart`、AST 结构、`GlobalContext`。
- 数据泥团
  - `match + detection + pushdownResult + plan` 经常同时出现。
- 内幕交易
  - 了解虚拟边绑定和全局上下文的内部拼装细节。

**建议方向**

- 以“查询形态”而不是“语法节点”拆分职责。
- 优先分离 `rewriteMixedPattern` 对虚拟边规则和外部查询构建的责任。

---

### 1.4 `src/main/java/com/federatedquery/rewriter/VirtualEdgeDetector.java`

**优先级**: P2

**已确认坏味道**

- 纯数据类
  - `PhysicalNodePart`
  - `PhysicalEdgePart`
  - `VirtualNodePart`
  - `VirtualEdgePart`
- 可变数据
  - 这些部件类主要依赖 setter 组装。

**候选坏味道**

- 冗赘元素
  - 当前证据不强，先不单独立项。

**建议方向**

- 只把真正稳定的行为移动进去，例如“根据 binding 构造 external query 输入”或“表达路径边界能力”。
- 不要为了“充血模型”而把外部依赖反向耦合进这些类。

---

### 1.5 `src/main/java/com/federatedquery/connector/RecordConverter.java`

**优先级**: P2

**已确认坏味道**

- 霰弹式修改
  - 输出结构知识与 `GraphQuerySDK`、`ResultStitcher` 分散维护。
- 异曲同工的类
  - 与 `GraphQuerySDK` 中的节点/边/路径输出转换存在相似实现。
- 内幕交易
  - 深知节点 Map 的 `id`、`label`、`type`、`_properties` 协议。
- 基本类型偏执
  - 大量通过裸 `Map<String, Object>` 承载领域结构。

**建议方向**

- 规划统一的“结果协议层”，再决定保留谁、删除谁。

---

### 1.6 `src/main/java/com/federatedquery/aggregator/ResultStitcher.java`

**优先级**: P2

**已确认坏味道**

- 重复代码
  - 多段相似的结果遍历与行构建逻辑。
- 循环语句
  - 多处 2-3 层遍历。
- 霰弹式修改
  - 与其他结果转换点共享输出协议知识。

---

### 1.7 `src/main/java/com/federatedquery/metadata/MetadataRegistryImpl.java`

**优先级**: P3

**已确认坏味道**

- 全局数据
  - 作为共享组件维护全局缓存状态。
- 重复代码
  - 三个 cache 的创建方式一致。

**明确撤回**

- 不再把“接口只有一个实现”认定为 `Speculative Generality`。

---

### 1.8 数据对象类

**涉及文件**

- `src/main/java/com/federatedquery/adapter/GraphEntity.java`
- `src/main/java/com/federatedquery/adapter/QueryResult.java`
- `src/main/java/com/federatedquery/executor/ExecutionResult.java`
- `src/main/java/com/federatedquery/plan/ExecutionPlan.java`
- `src/main/java/com/federatedquery/plan/ExternalQuery.java`
- `src/main/java/com/federatedquery/metadata/LabelMetadata.java`

**已确认坏味道**

- 纯数据类
- 可变数据
- 基本类型偏执

**备注**

- 这些类并不一定都要“充血化”。
- 更现实的目标是先收紧边界：减少裸 `Map/Object`、减少到处直接 `put/add/set`。

---

## 2. 测试与支撑代码

### 2.1 `src/test/java/com/federatedquery/mockserver/AlarmDataHandler.java`
### 2.2 `src/test/java/com/federatedquery/mockserver/KpiDataHandler.java`

**优先级**: P2

**已确认坏味道**

- 重复代码
  - 请求方法校验、参数解析、时间策略解析、字段投影、JSON 响应发送几乎完全一致。
- 异曲同工的类
  - 两个类只在关键参数名和 repository 调用上不同。
- 数据泥团
  - `timestamp + strategy + fields` 总是一起出现。

**建议方向**

- 规划一个可配置的基础 handler 或抽象查询模板。

---

### 2.3 `src/test/java/com/federatedquery/testutil/GraphQueryMetaFactory.java`

**优先级**: P3

**已确认坏味道**

- 重复代码
  - 注册 datasource / label / virtual edge 的样板逻辑很多。
- 中间人
  - 实例方法与静态方法之间有明显转手注册行为。

**说明**

- 这是测试工具类，优先级低于主流程代码。

---

### 2.4 E2E 测试大类

**涉及文件**

- `src/test/java/com/federatedquery/e2e/E2ETest.java`
- `src/test/java/com/federatedquery/e2e/MissingCoverageE2ETest.java`

**已确认坏味道**

- 过大的类
- 重复代码
- 长测试方法

**建议方向**

- 后续按场景拆类，而不是继续往同一测试巨石中追加用例。

---

## 3. 已撤回或不作为本期重点的条目

以下问题不再作为当前规划阶段的主结论：

- `Repeated Switches`
  - 当前只确认 1 处 `switch`。
- `Comments`
  - 未发现误导性注释、过期注释、注释掉的代码、TODO/FIXME。
- `Speculative Generality`
  - 当前接口抽象仍有实际用途。
- `Temporary Field`
  - 原先点名字段存在实际读写路径。
- “工具类未 final”“缺少 @Override”“日志级别不当”“导入优化”
  - 这些可以留给常规代码整理，不作为 Fowler 坏味道主项。

---

## 4. 文件级规划建议

### P1

- `GraphQuerySDK.java`
- `FederatedExecutor.java`
- `QueryRewriter.java`

### P2

- `VirtualEdgeDetector.java`
- `RecordConverter.java`
- `ResultStitcher.java`
- `AlarmDataHandler.java`
- `KpiDataHandler.java`

### P3

- `MetadataRegistryImpl.java`
- `GraphQueryMetaFactory.java`
- `ExecutionPlan.java`
- `ExternalQuery.java`
- `GraphEntity.java`
- `QueryResult.java`
- `ExecutionResult.java`
- `LabelMetadata.java`
