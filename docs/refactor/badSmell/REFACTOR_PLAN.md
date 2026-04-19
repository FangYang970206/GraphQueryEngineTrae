# GraphQueryEngine 重构计划

> 版本: 2.0
> 日期: 2026-04-16
> 计划阶段: 规划阶段
> 基线文档: `badSmell.md`

---

## 1. 计划目标

本计划只围绕已确认的坏味道制定，不再把普通风格问题混入主计划。

当前目标不是“全面重写”，而是分阶段降低以下问题：

1. 过大的类与发散式变化
2. 过长函数与过长参数列表
3. 结果协议相关的重复代码、霰弹式修改、内幕交易
4. 纯数据类与可变数据导致的边界模糊
5. 测试与 mock 支撑代码中的重复实现

---

## 2. 计划边界

### 2.1 本计划明确纳入

- `GraphQuerySDK`
- `FederatedExecutor`
- `QueryRewriter`
- `VirtualEdgeDetector`
- `RecordConverter`
- `ResultStitcher`
- `MetadataRegistryImpl`
- `AlarmDataHandler`
- `KpiDataHandler`
- 部分测试支撑类与 E2E 巨石测试

### 2.2 本计划明确不作为主目标

- `Repeated Switches`
- `Comments`
- `Speculative Generality`
- `Refused Bequest`
- `Temporary Field`
- 工具类 `final`、导入整理、日志级别等常规风格项

---

## 3. 设计约束

任何后续重构都必须遵守以下约束：

1. 不能破坏虚拟边只允许首跳或末跳的规则。
2. 不能把虚拟条件错误下推到 TuGraph。
3. 不能把全局排序、分页再次错误下推。
4. 不能引入新的 N+1 外部调用。
5. 不能把元数据驱动规则重新写回硬编码 if/switch 表。
6. 不能在没有测试保护的前提下做大规模搬迁。

---

## 4. 分阶段计划

### 阶段 0：统一事实基线

**目标**

- 固化坏味道判定口径，避免“边做边改结论”。

**输出**

- `badSmell.md` 作为 24 条坏味道唯一基线。
- `CODE_SMELLS.md` 只保留文件级索引。
- 本文档只保留计划，不再重复统计数字。

**完成标准**

- 四份文档对 `GraphQuerySDK`、`Repeated Switches`、`Comments`、`Temporary Field` 的结论一致。

---

### 阶段 1：拆解 `GraphQuerySDK`

**对应坏味道**

- 过大的类
- 发散式变化
- 过长函数
- 过长参数列表
- 依恋情结
- 过长消息链
- 循环语句

**计划重点**

1. 先切职责边界，不先追求包结构完美。
2. 优先识别以下子职责：
   - API 入口与异常边界
   - 参数替换
   - 结果转换
   - 路径构建
   - 全局排序/分页
3. 优先抽离“结果协议相关逻辑”，因为它与 `RecordConverter`、`ResultStitcher` 强耦合。

**建议重构手法**

- Extract Method
- Extract Class
- Move Function
- Introduce Parameter Object

**风险**

- 路径结果与 provenance 规则极易回归。

---

### 阶段 2：拆解执行与重写主流程

#### 2.1 `FederatedExecutor`

**对应坏味道**

- 过大的类
- 发散式变化
- 过长函数
- 过长参数列表
- 循环语句
- 内幕交易

**计划重点**

1. 区分“查询调度”“依赖输入收集”“batch/unbatch”“结果 enrichment”。
2. 先减少一个方法跨越的阶段数量，再考虑线程池策略抽象。
3. 对 `ExecutionResult`、`QueryResult` 的可变协作边界做约束。

#### 2.2 `QueryRewriter`

**对应坏味道**

- 过大的类
- 发散式变化
- 长函数
- 依恋情结
- 数据泥团

**计划重点**

1. 优先按“单查询 / Union / 混合图样 / 条件下推”拆逻辑。
2. 把 `VirtualEdgePart` 相关外部查询构建知识收拢，避免散落在重写主流程中。

---

### 阶段 3：统一结果协议与转换职责

**目标对象**

- `GraphQuerySDK`
- `RecordConverter`
- `ResultStitcher`

**对应坏味道**

- 重复代码
- 霰弹式修改
- 异曲同工的类
- 内幕交易
- 基本类型偏执

**计划重点**

1. 先明确“对外结果协议”的唯一事实来源。
2. 再决定哪些转换逻辑应保留在 SDK，哪些应归属转换器。
3. 减少裸 `Map<String, Object>` 协议散落。

**阶段产出**

- 统一节点/边/路径输出协议
- 明确一个主转换入口
- 删掉重复实现或把它们降级为简单适配器

---

### 阶段 4：治理纯数据类与可变数据

**目标对象**

- `VirtualEdgeDetector` 内部类
- `ExecutionPlan`
- `ExternalQuery`
- `GraphEntity`
- `QueryResult`
- `ExecutionResult`
- `LabelMetadata`

**对应坏味道**

- 纯数据类
- 可变数据
- 基本类型偏执

**计划重点**

1. 不是一律“充血化”，而是按价值引入最小行为。
2. 优先把真正反复散落在外部的行为搬回对象边界。
3. 对仍必须保持 DTO 性质的类，至少要限制可变入口和裸类型扩散。

**不建议**

- 仅为了“面向对象更漂亮”而把大量服务依赖塞进数据对象。

---

### 阶段 5：测试与 mock 支撑代码收敛

**目标对象**

- `AlarmDataHandler`
- `KpiDataHandler`
- `GraphQueryMetaFactory`
- `E2ETest`
- `MissingCoverageE2ETest`

**对应坏味道**

- 重复代码
- 异曲同工的类
- 中间人
- 过大的类
- 数据泥团

**计划重点**

1. 合并 handler 模板逻辑。
2. 收敛 metadata 注册样板。
3. 按场景拆分 E2E 巨石测试。

---

## 5. 优先级排序

### P1：必须优先规划

1. `GraphQuerySDK`
2. `FederatedExecutor`
3. `QueryRewriter`

### P2：在主流程稳定后推进

1. `RecordConverter`
2. `ResultStitcher`
3. `VirtualEdgeDetector`
4. `AlarmDataHandler`
5. `KpiDataHandler`

### P3：作为收尾或顺手治理

1. `MetadataRegistryImpl`
2. `GraphQueryMetaFactory`
3. 数据对象类与大测试类

---

## 6. 坏味道到重构手法的映射

| 坏味道 | 优先手法 | 备注 |
|--------|----------|------|
| 过大的类 | Extract Class / Move Function | 先分职责，再分包 |
| 过长函数 | Extract Method / Decompose Conditional | 避免一次性重写 |
| 重复代码 | Extract Function / Form Template Method | 先找稳定共性 |
| 发散式变化 | Extract Class | 让变化原因按模块收口 |
| 霰弹式修改 | Move Function / Consolidate Protocol | 重点治理输出协议 |
| 依恋情结 | Move Function | 行为应更靠近它操作的数据 |
| 数据泥团 | Introduce Parameter Object | 只在一起变化时再抽对象 |
| 基本类型偏执 | Replace Primitive with Value Object | 先从协议字段和值对象开始 |
| 纯数据类 | Move Function / Encapsulate Collection | 不追求机械“充血” |
| 可变数据 | Encapsulate Collection / Remove Setting Method | 优先限制写入口 |

---

## 7. 风险与回避策略

### 高风险

- `GraphQuerySDK` 路径构建重构
- `FederatedExecutor` 的 batch / provenance / dependency 流程
- `QueryRewriter` 的混合图样拆分

### 中风险

- 统一结果协议
- 纯数据类引入行为

### 低风险

- 测试 handler 模板化
- cache 创建逻辑收敛

### 回避策略

1. 单次重构只覆盖一个变化轴。
2. 先补充或复用现有测试夹具，再搬代码。
3. 行为迁移优先，小范围结构迁移其次。
4. 先抽协议和 helper，再抽组件。

---

## 8. 规划完成标志

满足以下条件后，说明规划阶段完成，可以进入实施：

1. 四份文档口径统一。
2. 每个 P1 目标都有明确的拆分边界。
3. 每个 P1 目标都列出行为保护点。
4. 已经排除本期不做的误判项。
5. 团队可以按阶段独立实施，而不需要一次性大改。
