# GraphQueryEngine 坏味道总报告

> 分析日期: 2026-04-16
> 分析范围: `src/main/java` 与 `src/test/java`
> 判定标准: 《重构：改善既有代码的设计》列出的 24 条坏味道
> 当前阶段: 规划阶段，只修正文档，不修改代码

---

## 1. 结论摘要

本目录下原有文档的主方向大体合理，但存在三个明显问题：

1. 统计口径不统一
   - `GraphQuerySDK` 行数、坏味道总数、魔法字符串数量、是否纳入测试代码，四份文档都不一致。
2. 把“风格建议”混入“坏味道”
   - 例如“工具类未声明 final”“缺少 Javadoc”“静态常量过多”等，不能直接等同于 Fowler 书中的坏味道。
3. 个别条目证据不足或误判
   - `Repeated Switches`：全库仅确认 1 处 `switch`。
   - `Comments`：未发现 TODO/FIXME、误导性注释、注释掉的代码；“注释少”不等于该坏味道成立。
   - `Speculative Generality`：仅凭“接口实现数少”不足以下结论。
   - `Temporary Field`：原文点名的 `sourceEdgeType`、`snapshotName`、`snapshotTime` 均存在实际使用。

本次修订采用三档判定：

- `已确认`：能给出直接代码证据，适合作为重构计划输入。
- `候选`：有迹象，但证据还不足以作为当前阶段重点。
- `未观察到`：本轮扫描未找到足够证据，不建议写进当前主计划。

---

## 2. 最重要的热点

### 2.1 已确认的核心问题

1. `GraphQuerySDK`
   - 同时承担公共 API、执行编排、参数解析、结果转换、路径构建、排序分页、去重、EXPLAIN/PROFILE 等职责。
   - 同时命中过大的类、过长函数、发散式变化、消息链、依恋情结、循环语句、长参数列表。

2. `FederatedExecutor`
   - 同时承担线程池配置、物理查询调度、外部查询调度、依赖查询、批处理、Union、结果回填。
   - 命中过大的类、过长函数、发散式变化、循环语句、消息链、内幕交易、可变数据协作复杂。

3. `QueryRewriter`
   - 同时处理单查询、Union、多段查询、虚拟边识别、WHERE 下推、Physical/External Query 构建、Snapshot 传递。
   - 命中过大的类、发散式变化、依恋情结、数据泥团、长函数。

4. `VirtualEdgeDetector` 的内部部件类
   - `PhysicalNodePart`、`PhysicalEdgePart`、`VirtualNodePart`、`VirtualEdgePart` 基本都是数据容器。
   - 属于比较典型的纯数据类。

5. 测试与支撑代码中的重复实现
   - `AlarmDataHandler` 与 `KpiDataHandler` 结构几乎同构。
   - 多个 E2E 测试反复注册 datasource、label、virtual edge。

### 2.2 新增且原文覆盖不足的问题

1. `Alternative Classes with Different Interfaces`
   - `AlarmDataHandler` 与 `KpiDataHandler` 逻辑几乎一致，只是关键参数和 repository 调用不同。

2. `Shotgun Surgery`
   - 结果输出协议散落在 `GraphQuerySDK`、`RecordConverter`、`ResultStitcher`，一旦输出结构变化，往往需要多点修改。

3. `Global Data`
   - 真正值得关注的不是 `private static final` 常量，而是共享可写状态：
   - `MetadataRegistryImpl` 维护全局缓存。
   - `JsonUtil` 暴露静态 `ObjectMapper`。

---

## 3. 24 条坏味道逐项判定

| 坏味道 | 判定 | 主要证据 | 说明 |
|--------|------|----------|------|
| 神秘命名 | 已确认 | `GraphQuerySDK`、`FederatedExecutor`、`QueryRewriter` 中大量 `sq`、`ve`、`qr`、`varName` | 以局部命名质量问题为主，存在但不是当前第一优先级 |
| 重复代码 | 已确认 | `AlarmDataHandler` / `KpiDataHandler`；`GraphQuerySDK` / `FederatedExecutor` / `ResultStitcher` 的实体遍历模式；测试元数据注册样板 | 是当前最明确的横切问题之一 |
| 过长函数 | 已确认 | `GraphQuerySDK.buildTuGraphFormatResults`、`buildRowResults`、`buildPathVariantsRecursiveWithRow`；`FederatedExecutor.executeWithDependencyAwareness`；`TuGraphAdapterImpl.buildCypherQuery` | 证据充分 |
| 过长参数列表 | 已确认 | `GraphQuerySDK.buildPathVariantsRecursiveWithRow`；`FederatedExecutor.executeWithDependencyAwareness` | 明确存在，但规模没有原文写得那么普遍 |
| 全局数据 | 已确认 | `MetadataRegistryImpl` 的共享缓存；`JsonUtil` 的静态 `ObjectMapper` | 原文将静态常量视为主问题，判断不准确 |
| 可变数据 | 已确认 | `GraphEntity`、`ExternalQuery`、`QueryResult`、`ExecutionResult` 大量可变集合与 setter | 与并发执行和结果回填耦合后成本上升 |
| 发散式变化 | 已确认 | `GraphQuerySDK`、`FederatedExecutor`、`QueryRewriter` | 核心类职责过宽，任何规则变更都易波及单类多处 |
| 霰弹式修改 | 已确认 | 输出协议分散于 `GraphQuerySDK`、`RecordConverter`、`ResultStitcher`；测试元数据初始化散落多个 E2E | 当前文档低估了这一类 |
| 依恋情结 | 已确认 | `GraphQuerySDK` 深度读取 AST/结果 Map；`QueryRewriter.createExternalQuery` 深度消费 `VirtualEdgePart`；`FederatedExecutor` 深入操作 `ResultRow` / `GraphEntity` | 典型“行为不在数据边界内” |
| 数据泥团 | 已确认 | 测试与重写逻辑中反复一起出现的 `edgeType`、`targetDataSource`、`operatorName`、`idMapping`；处理请求时反复一起出现的 `timestamp`、`strategy`、`fields` | 有证据，但主要集中在支撑代码和构建流程 |
| 基本类型偏执 | 已确认 | `ExternalQuery`、`GraphEntity`、输出协议大量依赖裸 `String` / `Map` / `Object` 表达领域概念 | 元数据与输出协议都还缺少值对象边界 |
| 重复 switch | 未观察到 | 全库仅确认 `GraphQuerySDK.evaluateCondition` 一处 `switch` | 原文判定不成立，不能写入主计划 |
| 循环语句 | 已确认 | `FederatedExecutor`、`ResultStitcher`、`GraphQuerySDK` 的 2-3 层嵌套循环 | 成立，但应作为长函数/重复代码的伴生问题看待 |
| 冗赘元素 | 候选 | `GraphQuerySDK` 内部 `PathInfo`、`ReturnInfo` 较轻薄 | 目前不足以排进前序计划，先不作为重点 |
| 夸夸其谈通用性 | 未观察到 | `MetadataRegistry` 和 `DataSourceAdapter` 目前仍有真实抽象价值 | “实现少”不是充分证据 |
| 临时字段 | 未观察到 | 原文点名字段均有实际使用：`sourceEdgeType` 在执行与路径匹配中使用，`snapshotName` / `snapshotTime` 在重写阶段使用 | 原文判定撤回 |
| 过长的消息链 | 已确认 | `GraphQuerySDK` 中多段 AST 链式访问、Map 链式读取；`FederatedExecutor` 中 `row -> entitiesByVariable -> entity -> properties` | 与内幕交易一起出现 |
| 中间人 | 候选 | `GraphQueryMetaFactory` 有部分“转手注册”行为 | 主要存在于测试工具，不是主代码主矛盾 |
| 内幕交易 | 已确认 | `GraphQuerySDK`、`FederatedExecutor`、`RecordConverter` 对 `GraphEntity`、`ResultRow`、裸 Map 结构约定了解过深 | 建议通过协议对象或专职转换器收口 |
| 过大的类 | 已确认 | `GraphQuerySDK`、`FederatedExecutor`、`QueryRewriter`；测试侧 `E2ETest`、`MissingCoverageE2ETest` 也偏大 | 当前最稳定的高优先级结论 |
| 异曲同工的类 | 已确认 | `AlarmDataHandler` / `KpiDataHandler`；结果转换知识同时存在于 `GraphQuerySDK` 与 `RecordConverter` | 这是本轮比原文新增的重要发现 |
| 纯数据类 | 已确认 | `VirtualEdgeDetector` 内部部件类；`ExecutionPlan`、`ExternalQuery`、`LabelMetadata`、`QueryResult` | 以“数据承载过强、行为分散在外部服务”最明显 |
| 被拒绝的遗赠 | 未观察到 | 主代码继承层次较浅，未发现明显“继承但拒绝使用父类能力”的子类 | 当前不进入计划 |
| 注释 | 未观察到 | 未找到 TODO/FIXME/XXX、误导性注释、注释掉的代码 | “注释少”不是本书该坏味道的判定标准 |

---

## 4. 原有文档的合理部分与需修正部分

### 4.1 合理

- 把 `GraphQuerySDK` 视为第一重构目标是合理的。
- 把 `FederatedExecutor`、`QueryRewriter` 视为第二梯队重点是合理的。
- 把 `VirtualEdgeDetector` 的内部纯数据类纳入治理范围是合理的。
- 把测试支撑代码纳入扫描范围是合理的。

### 4.2 需要修正

- 不再使用 `87`、`19` 这类互相冲突的总数统计。
- 不再把 `private static final` 常量认定为 `Global Data` 主问题。
- 不再把 `Comments` 写成“缺少 Javadoc”。
- 不再把 `Repeated Switches` 写成已成立坏味道。
- 不再把 `Speculative Generality` 建立在“接口实现个数少”上。
- 不再把 `sourceEdgeType`、`snapshotName`、`snapshotTime` 认定为 `Temporary Field`。
- `Unknown` 默认标签不再作为主要魔法字符串问题；真正需要治理的是跨模块共享的协议字段名。

---

## 5. 当前阶段建议的优先级

### P0：必须先统一事实基线

1. 以本文档作为“24 条坏味道总判定”唯一基线。
2. 其余文档只保留各自职责：
   - `CODE_SMELLS.md`：按文件列问题索引。
   - `REFACTOR_PLAN.md`：按阶段列计划。
   - `REFACTOR_GUIDE.md`：列实施前检查与决策准则。

### P1：最值得优先规划的重构对象

1. `GraphQuerySDK`
2. `FederatedExecutor`
3. `QueryRewriter`
4. `VirtualEdgeDetector` 内部纯数据类
5. `RecordConverter` / `ResultStitcher` / 输出协议
6. `AlarmDataHandler` / `KpiDataHandler`

### P2：当前不建议优先投入的条目

1. `Comments`
2. `Repeated Switches`
3. `Speculative Generality`
4. `Refused Bequest`
5. `Temporary Field`
6. `Lazy Element`

---

## 6. 计划约束

后续任何重构计划都必须同时满足仓库已有约束：

1. 不得破坏虚拟边只允许首跳或末跳的规则。
2. 不得把虚拟节点条件错误下推到 TuGraph。
3. 不得把全局排序和分页再次下推到物理查询。
4. 不得引入新的 N+1 外部查询。
5. 不得把图语义重新硬编码回 `GraphQuerySDK`、`QueryRewriter`、执行器或测试。

---

## 7. 结论

综合本轮扫描，现有文档“方向对、口径乱、个别误判明显”。

最稳妥的结论不是“项目里有多少个坏味道实例”，而是：

1. 已确认的高价值问题集中在大类、长函数、重复代码、发散式变化、纯数据类、消息链、内幕交易、可变数据。
2. `GraphQuerySDK` 仍是绝对优先级第一。
3. 测试和支撑代码中还存在原文未充分覆盖的 `Alternative Classes`、`Duplicated Code`、`Shotgun Surgery`。
4. `Repeated Switches`、`Comments`、`Speculative Generality`、`Temporary Field` 现阶段不应作为主计划依据。
