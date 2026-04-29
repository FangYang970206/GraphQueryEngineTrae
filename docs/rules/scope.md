# Cypher语法范围与约束清单

## 一、语法范围

### 1.1 支持的子句

支持标准子句：`MATCH`、`RETURN`、`WITH`、`WHERE`、`ORDER BY`、`LIMIT`、`UNION`、`UNION ALL`。

支持扩展子句：

| 语法 | 示例 | 作用 |
|------|------|------|
| `USING SNAPSHOT` | `USING SNAPSHOT('latest', 1) ON ['Card'] MATCH ...` | 时间快照查询 |
| `PROJECT BY` | `RETURN path PROJECT BY {'Card': ['name', 'id']}` | 字段投影过滤 |

除上述语法外，其他子句应直接报错。

### 1.2 支持的模式

支持节点、关系、多关系类型、命名路径和属性过滤：

| 模式 | 示例 |
|------|------|
| 节点模式 | `(n:Label {prop: value})` |
| 关系模式 | `-[r:TYPE]->`、`<-[r]-`、`-[r]-` |
| 多关系类型 | `-[r:TYPE1|TYPE2|TYPE3]->` |
| 命名路径 | `p = (a)-[r]->(b)` |
| 属性过滤 | `{name: 'NE001', status: 'active'}` |

不支持变长路径。

### 1.3 支持的表达式和函数

支持表达式操作符：

| 类别 | 操作符 |
|------|--------|
| 比较 | `=`、`<>`、`<`、`>`、`<=`、`>=` |
| 逻辑 | `AND`、`OR`、`NOT` |
| NULL判断 | `IS NULL`、`IS NOT NULL` |
| 字符串匹配 | `STARTS WITH`、`ENDS WITH`、`CONTAINS` |
| 列表操作 | `IN` |

支持聚合函数：`count(n)`、`count(*)`、`sum(n.age)`、`avg(n.age)`、`min(n.age)`、`max(n.age)`。

聚合字段来自单一数据源时允许聚合；聚合字段来自多个数据源时应报错。

### 1.4 只读范围

SDK仅支持只读查询。禁止 `CREATE`、`MERGE`、`DELETE`、`DETACH DELETE`、`SET`、`REMOVE` 和存储过程；除明确支持的语法外，其他语法应直接报错。

## 二、约束清单

### 2.1 虚拟边约束

虚拟边只允许出现在多跳路径的第一跳或最后一跳，单跳虚拟边不允许。违反约束时抛出 `VirtualEdgeConstraintException`。

| 类型 | 模式 | 说明 |
|------|------|------|
| 允许 | `(kpi:KPI)-[:VirtualEdge]->(ltp:LTP)-[...]-(x)` | 第一跳虚拟：外部数据源 → TuGraph |
| 允许 | `(ne:NE)-[:Physical]->(ltp)-[:Virtual]->(kpi)` | 最后一跳虚拟：TuGraph → 外部数据源 |
| 禁止 | `[物理节点] → [虚拟节点] → [物理节点]` | 三明治结构 |
| 禁止 | `[虚拟节点] → [虚拟节点] → [虚拟节点]` | 不支持虚拟到虚拟 |
| 禁止 | `[虚拟节点]` | 不支持纯虚拟点 |

`firstHopOnly` 和 `lastHopOnly` 由 `VirtualEdgeDetector.checkRelationshipVirtual()` 检查。

### 2.2 下沉与执行约束

所有 `WHERE` 条件统一下沉到所有数据源，结果由各数据源行为决定。

`ORDER BY` 和 `LIMIT` 统一下沉到各数据源；各数据源使用相同的 `LIMIT` 后再汇聚。`SKIP` 分页不支持。

执行顺序：

| 场景 | 执行顺序 |
|------|----------|
| 虚拟边在第一跳 | 外部数据源 → 提取ID → TuGraph查询 |
| 虚拟边在最后一跳 | TuGraph查询 → 提取ID → 外部数据源 |
| 混合边 `|` 组合 | 物理边走TuGraph，虚拟边走依赖执行，最终结果做UNION |

### 2.3 批量请求约束

外部查询必须批量执行，禁止按ID循环调用外部API。`BatchingStrategy` 负责合并外部查询请求，一次请求传递完整ID集合。

### 2.4 结果集约束

默认限制：`druid`、`alarm` 数据源默认 `LIMIT 1000`；`tugraph` 数据源默认 `LIMIT 5000`。

用户显式 `LIMIT` 可以覆盖默认限制，但最终总结果集不得超过 `8000`。

### 2.5 起点约束

`MATCH` 模式里的第一个节点必须写 `label`，例如 `MATCH (n:NE)`；不支持从无 `label` 节点开始做全类型遍历。除第一度外，其他点和边可以不写类型。
