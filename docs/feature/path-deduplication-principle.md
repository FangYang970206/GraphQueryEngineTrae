# 路径去重原理说明

**文档日期**: 2026-04-12  
**适用范围**: `RETURN p`、`UNION`、虚拟边、批量外部查询  
**关联实现**: `GraphQuerySDK`、`FederatedExecutor`、`ExecutionResult`

---

## 一、问题背景

在路径查询场景中，执行器当前输出的不是“路径”，而是多个来源的实体集合：

- `physicalResults`
- `externalResults`
- `batchResults`
- `unionResults`

这些实体会在 `GraphQuerySDK.buildTuGraphFormatResults` 中按变量名重新归并，再由 `buildPathResults` 递归拼装为最终路径。

因此，重复问题分为两层：

- **实体级重复**：同一实体被执行链路重复保存、重复汇总
- **路径级重复**：不同来源的实体最终拼出完全相同的路径

---

## 二、为什么不能只在执行端去重

### 2.1 执行端看到的是实体，不是路径

执行器只知道某个变量下有哪些 `GraphEntity`，但不知道：

- 它最终会接到哪一条路径分支上
- 它与当前路径末节点是否真正可连接
- 两组实体最终是否会拼成同一条路径

因此执行端最多能做**实体规范化**，无法独立完成**路径等价判定**。

### 2.2 路径是否重复，要等路径成型后才能判断

例如两个子查询可能各自产生一组实体，单看实体集合不同，但最终构造出的路径文本完全一致。  
这种情况只有在路径完整展开后，才能确定应当合并为 1 条结果。

---

## 三、执行链路与重复来源

```mermaid
flowchart TD
    A[Cypher / AST] --> B[QueryRewriter]
    B --> C[FederatedExecutor]
    C --> D1[physicalResults]
    C --> D2[externalResults]
    C --> D3[batchResults]
    C --> D4[unionResults]
    D1 --> E[buildTuGraphFormatResults]
    D2 --> E
    D3 --> E
    D4 --> E
    E --> F[entitiesByVarName]
    F --> G[buildPathResults]
    G --> H[最终路径数组]
```

### 3.1 已知的执行侧重复来源

- 批量查询结果先写入 `batchResults`
- 同一批结果再经过 `unbatch` 写入 `externalResults`
- `UNION` 汇总时再次把子计划中的实体扁平拷贝到 `unionResults`
- 最终渲染层同时消费多个结果桶，导致重复实体再次进入路径构造

---

## 四、为什么最终仍要保留路径去重

```mermaid
flowchart LR
    A1[实体集合 A] --> P[路径构造]
    A2[实体集合 B] --> P
    P --> R1[Path-1: NE002 → LTP003 → KPI2003]
    P --> R2[Path-1: NE002 → LTP003 → KPI2003]
    P --> R3[Path-2: NE002 → LTP004 → KPI2004]
```

上图说明：

- 不同来源的实体集合可能在最终拼装后得到同一条路径
- 执行端无法提前知道 `R1` 与 `R2` 是同一条路径
- 只有在 `buildPathResults` 形成完整 path 后，才能对路径本身进行去重

因此最终层必须承担两件事：

- 对参与路径拼装的实体先做一次规范化去重
- 对生成后的完整路径再做一次路径级去重

---

## 五、执行端为什么无法“完全”去重

```mermaid
flowchart TD
    A[同一节点 NE002] --> B1[路径 1: NE002 → LTP003]
    A --> B2[路径 2: NE002 → LTP004]
    A --> B3[路径 3: NE002 → KPI001]
```

这里的 `NE002` 在三条路径中都合法出现。

如果执行端简单按实体 ID 全局去重，只保留一次实体出现信息，会丢失：

- 节点在不同路径中的合法复用
- 同一终点在不同父节点下的连接语义
- 虚拟边基于 `parentResId` 与当前路径末节点的挂接关系

所以执行端只能去掉**工程性重复**，不能消掉**路径语义上的重复出现**。

---

## 六、当前合理的职责边界

### 6.1 执行端负责

- 避免同一批结果同时以多种形式暴露给下游
- 避免 `UNION` 对同源实体做重复汇总
- 尽量减少无意义的实体重复进入渲染层

### 6.2 路径构造端负责

- 根据当前路径上下文判断实体是否真的可连接
- 按完整路径内容判断两条 path 是否等价
- 对最终输出的路径数组进行去重

---

## 七、可做的优化点

```mermaid
flowchart TD
    A[优化目标: 减少重复输入] --> B1[批量结果只保留一种对下游可见形态]
    A --> B2[收紧 UNION 汇总逻辑]
    A --> B3[为 ExecutionResult 定义唯一消费出口]
    A --> B4[渲染层只消费规范化后的结果集合]
    A --> C[保留最终路径级去重作为语义兜底]
```

建议按以下顺序优化：

1. **批量结果单出口**  
   不再让 `batchResults` 与 `externalResults` 同时参与最终渲染。

2. **UNION 汇总收紧**  
   避免把子计划中的多来源实体无差别再次扁平复制。

3. **ExecutionResult 结果语义收敛**  
   明确哪些结果桶是“内部中间态”，哪些结果桶才允许进入最终结果构造。

4. **保留最终路径去重**  
   即使执行端已优化，路径层仍应保留最终去重，作为路径语义正确性的最后保障。

---

## 八、结论

- 在当前架构下，**只靠执行端无法完全消除路径重复**
- 执行端可以减少实体级重复，但不能替代路径级判等
- 在 `buildPathResults` 中保留最终路径去重是必要且合理的
- 最优方案不是“只在一端去重”，而是：
  - **执行端减少工程性重复**
  - **路径构造端完成最终语义去重**

---

## 九、相关文件

- `src/main/java/com/federatedquery/executor/ExecutionResult.java`
- `src/main/java/com/federatedquery/executor/FederatedExecutor.java`
- `src/main/java/com/federatedquery/executor/BatchingStrategy.java`
- `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java`
- `src/test/java/com/federatedquery/e2e/VirtualGraphCaseE2ETest.java`

---

## 十、record-based path reconstruction 演进说明

### 10.1 初始状态：全局变量池重建路径

最初的 `RETURN p` 路径构造方式是：

1. 把 `physicalResults / externalResults / batchResults / unionResults` 全部汇总
2. 按变量名生成全局 `entitiesByVarName`
3. 根据 AST pattern 从起点开始递归枚举路径

这种方式的优点是实现简单，但缺点也很明显：

- 容易把不同来源、不同分支的实体混在一起
- 对 `UNION` 场景容易产生跨分支误拼接
- 对 batch 外部结果只能做弱归属，容易出现重复或错误挂接

```mermaid
flowchart LR
    A[physical/external/batch/union] --> B[全局 entitiesByVarName]
    B --> C[按 AST pattern 递归枚举]
    C --> D[路径结果]
```

### 10.2 第一阶段：保留路径层最终去重

第一步并没有改变路径模型，而是在路径构造完成后增加两道兜底：

- 先对参与拼装的实体做规范化去重
- 再对最终完整 path 做路径级去重

这一步主要解决的是**结果爆炸**，例如 `testCase2` 中“语义上只有 5 条路径，但实现层输出很多条”。

### 10.3 第二阶段：按 UNION 分支重建路径

第二步开始引入 provenance：

- `executeUnion` 保留每个子计划的 `ExecutionResult`
- `GraphQuerySDK` 在 `UNION RETURN p` 场景下，优先按子分支执行结果分别构造路径
- 每个分支先形成自己的 path 集，再进行结果合并

这样做之后，路径构造不再依赖单一的全局变量池，`UNION` 的路径语义显著变干净。

```mermaid
flowchart TD
    A[UnionPart]
    A --> B1[SubPlan 1 ExecutionResult]
    A --> B2[SubPlan 2 ExecutionResult]
    B1 --> C1[分支1独立构造路径]
    B2 --> C2[分支2独立构造路径]
    C1 --> D[合并路径结果]
    C2 --> D
```

### 10.4 第三阶段：batch provenance 精确回填

第三步收紧的是外部查询批处理链路：

- `BatchRequest` 显式携带 `outputIdField`
- `unbatch` 从“平均切片”改为“按 outputIdField 精确归属”
- 最终渲染层不再把 `batchResults` 直接当成路径输入再次消费

这一步解决的是：  
**同一批外部结果必须准确回到原始查询所属的 source row，而不是靠位置猜测。**

### 10.5 第四阶段：row-aware 路径构造

第四步开始向真正的 record-based path reconstruction 靠拢：

- `QueryResult` 新增 `rows`
- `TuGraphAdapterImpl` 保留物理查询的行级变量映射
- external 结果在执行侧会按 `sourceVariableName + inputIdField + outputIdField` 与 physical rows 合并
- `GraphQuerySDK` 在构造路径时，优先使用 rows 进行记录感知的连接

此时路径构造逻辑变成：

1. 如果当前 row 已经包含下一跳需要的变量，则优先按 row 直接连接
2. 如果 row 暂时缺少下一跳实体，且该边是虚拟边，则再回退到 provenance 过滤后的全局候选集
3. 若 row-based 构造产生不完整 path，则丢弃该 path，不把半路径当成最终结果

```mermaid
flowchart TD
    A[physical rows] --> C[row-aware path builder]
    B[external rows with source provenance] --> C
    C --> D{当前 row 是否已有下一跳变量?}
    D -- 是 --> E[按 row 直接连接]
    D -- 否且为虚拟边 --> F[按 linkage/provenance 补全]
    E --> G[完整路径]
    F --> G
```

### 10.6 当前状态

当前实现已经具备以下能力：

- `UNION` 路径优先按分支执行结果重建
- batch 外部结果可按 `outputIdField` 精确回填
- mixed pattern 的普通路径开始优先按 rows 构造
- external rows 可以带上 source row 的变量上下文，减少交叉拼接
- 不完整 path 不会再被错误地当作最终结果输出

### 10.7 还没有完全做到的部分

当前实现已经明显朝 record-based 演进，但还不是 100% full record-based，主要仍有两点边界：

- external 查询的 rows 还不是统一、强约束的数据模型
- 某些复杂虚拟边场景仍需要回退到全局候选集做补全

也就是说，当前系统处于：

- **主路径优先按 record/provenance 构造**
- **复杂缺失场景允许有限 fallback**

这是一个兼顾兼容性与长期演进的中间稳定形态。

### 10.8 后续建议

如果继续往最干净的方向推进，建议下一步聚焦两件事：

1. **统一 external row schema**  
   明确 sourceVar、targetVar、join key、row provenance 的标准表达。

2. **进一步缩小 fallback 面积**  
   让更多 mixed pattern 场景在 rows 完整时完全不依赖全局变量池。
