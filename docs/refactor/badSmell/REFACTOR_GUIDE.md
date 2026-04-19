# GraphQueryEngine 重构指南

> 本文档不是“立刻开改”的逐步教程，而是实施前的决策与验收指南。
> 当前仍处于规划阶段，目标是保证后续重构方向正确、边界清晰、验证充分。

---

## 1. 使用方式

在真正开始修改代码前，先用本指南回答三个问题：

1. 这次改动要解决的是哪一种坏味道？
2. 这次改动会不会触碰虚拟边、WHERE pushdown、全局排序/分页、batch provenance 等高风险边界？
3. 这次改动最小可验证的切分单元是什么？

如果这三个问题没有明确答案，不要进入实施。

---

## 2. 实施前检查

### 2.1 基线检查

- 先阅读 `badSmell.md`，确认该问题在 24 条坏味道中是“已确认”而不是“候选”或“未观察到”。
- 再阅读 `CODE_SMELLS.md`，确认对应文件是否属于 P1 / P2 / P3。
- 如果计划内容与这两份文档冲突，以 `badSmell.md` 为准。

### 2.2 行为保护点

对主流程代码，至少要确认以下行为不会被破坏：

- 虚拟边只能出现在首跳或末跳
- 虚拟节点条件不能错误下推到 TuGraph
- 全局排序和分页仍在聚合后执行
- batch / unbatch 的 provenance 仍然准确
- 外部查询不会退化成 N+1
- 对外 JSON 结构保持兼容，除非本轮目标就是协议调整

### 2.3 构建与测试前提

执行任何实施前，优先使用以下基线命令：

```bash
mvn compile
mvn test
```

说明：

- `mvn compile` 必须先运行，项目依赖 ANTLR 生成代码。
- 当前阶段先记录基线结果，不在本文档中预设“必须新增多少测试”。

---

## 3. 按坏味道选择手法

### 3.1 过大的类 / 发散式变化

**适用对象**

- `GraphQuerySDK`
- `FederatedExecutor`
- `QueryRewriter`

**推荐手法**

- Extract Class
- Move Function
- Split Phase

**实施原则**

- 先按“变化原因”拆，不按“技术层名字好听”拆。
- 先把最不稳定、最容易一起变化的代码收口。
- 先迁移私有方法，再迁移公共入口。

**不要这样做**

- 一次性创建大量新包和新类，再把代码整体搬过去。
- 把所有逻辑都变成纯委托，留下一个“更薄但同样臃肿”的中枢类。

---

### 3.2 过长函数 / 复杂条件

**典型对象**

- `GraphQuerySDK.buildTuGraphFormatResults`
- `GraphQuerySDK.buildPathVariantsRecursiveWithRow`
- `FederatedExecutor.executeWithDependencyAwareness`
- `QueryRewriter.rewriteMixedPattern`

**推荐手法**

- Extract Method
- Decompose Conditional
- Replace Nested Conditional with Guard Clauses

**实施原则**

- 先按阶段切块，再给每个阶段命名。
- 优先抽离“前置检查”“分支判定”“结果组装”。
- 抽出的子方法必须能单独命名出业务意图。

**不要这样做**

- 只是机械按 20 行一段拆方法。
- 把局部变量大量提升成类字段，制造新的可变状态。

---

### 3.3 重复代码 / 异曲同工的类

**典型对象**

- `AlarmDataHandler` 与 `KpiDataHandler`
- `GraphQuerySDK` / `RecordConverter` / `ResultStitcher`
- 测试中的元数据注册样板

**推荐手法**

- Extract Function
- Form Template Method
- Introduce Adapter

**实施原则**

- 只有在“变化方式也相同”时才抽公共模板。
- 对结果协议类逻辑，先定义唯一事实来源，再做合并。

**不要这样做**

- 看到相似就立刻抽象，结果把少量差异藏进一堆布尔开关。
- 为了复用而把测试代码和生产代码强行耦合。

---

### 3.4 纯数据类 / 可变数据 / 基本类型偏执

**典型对象**

- `VirtualEdgeDetector` 内部类
- `ExecutionPlan`
- `ExternalQuery`
- `model/GraphEntity` - 图实体领域模型
- `model/QueryResult` - 查询结果领域模型
- `ExecutionResult`

**推荐手法**

- Move Function
- Encapsulate Collection
- Introduce Value Object

**实施原则**

- 先找“反复被外部读取并拼装”的行为，再考虑搬回对象内部。
- 先压缩写入口，再增强行为。
- 值对象优先用于输出协议字段、查询参数、标识字段这类稳定概念。

**不要这样做**

- 机械追求“充血模型”，把服务依赖塞进 DTO。
- 一口气把所有 `String` / `Map` 都替换掉。

---

### 3.5 消息链 / 内幕交易 / 依恋情结

**典型对象**

- `GraphQuerySDK`
- `FederatedExecutor`
- `QueryRewriter`
- `RecordConverter`

**推荐手法**

- Hide Delegate
- Move Function
- Introduce Facade

**实施原则**

- 先识别“谁最懂这段数据”，再把行为移过去。
- 如果多个类都了解同一协议细节，优先新增一个协议边界对象或统一转换器。

**不要这样做**

- 仅把链式调用换成临时变量而不改变结构。
- 把更多 getter 暴露出去，反而扩大消息链。

---

## 4. 分对象实施建议

### 4.1 `GraphQuerySDK`

**先做**

- 梳理公共 API 和私有实现的边界
- 找出结果转换、路径构建、排序分页三大块
- 提取最容易独立验证的私有方法组

**后做**

- 组件化拆分
- 统一输出协议层

**重点验证**

- `RETURN p`
- 过滤后再排序分页
- explain/profile 输出

---

### 4.2 `FederatedExecutor`

**先做**

- 明确执行阶段：物理查询、独立外部查询、依赖外部查询、Union
- 把结果 enrichment 和调度逻辑区分开

**重点验证**

- dependent query 无输入时的空结果行为
- batch 执行后结果回填
- 异步异常传播

---

### 4.3 `QueryRewriter`

**先做**

- 区分单查询、Union、多段查询、混合 pattern
- 把外部查询构建和 physical query 构建边界拉开

**重点验证**

- 虚拟边首跳/末跳约束
- WHERE pushdown 结果
- snapshot 信息透传

---

### 4.4 `RecordConverter` / `ResultStitcher`

**先做**

- 盘点节点/边/路径输出协议的字段集合
- 明确谁是主转换器，谁是适配器

**重点验证**

- 节点/边 JSON 字段名
- 路径结构
- record 模式与 JSON 模式的一致性

---

### 4.5 `AlarmDataHandler` / `KpiDataHandler`

**先做**

- 抽取参数解析和响应发送模板
- 保留差异点：关键参数名、查询入口、日志语义

**重点验证**

- GET / POST 两种输入方式
- `timestamp` 解析异常
- `fields` 投影行为

---

## 5. 文档到实施的交接清单

真正进入编码前，应先补齐以下内容：

- 每个 P1 目标的“拆分前后职责草图”
- 每个 P1 目标的“行为保护点列表”
- 每个 P1 目标对应的最小测试集合
- 本次明确不处理的坏味道列表

如果以上内容缺失，建议继续停留在规划阶段。

---

## 6. 常见误区

### 误区 1：把“注释少”当成 Comments 坏味道

不是。
本书里的 `Comments` 更关注误导、过期、掩盖设计问题的注释，而不是 Javadoc 数量。

### 误区 2：把“接口实现少”当成夸夸其谈通用性

不充分。
只有当抽象明显提前设计且持续没有真实使用价值时，才更接近该坏味道。

### 误区 3：把 `private static final` 常量当成 Global Data 主问题

不准确。
更应关注共享可写状态和全局可访问对象。

### 误区 4：为了“消除重复”引入大而全抽象

高风险。
如果差异点还在变化，过早抽象常会制造新的复杂度。

---

## 7. 完成判定

一次重构任务可以视为完成，至少应满足：

1. 目标坏味道比改动前更少，而不是只是换了文件位置。
2. 关键业务约束仍然成立。
3. 相关测试仍然通过，或已用更好的测试替代。
4. 文档中的判定、计划和实际改动口径一致。
