# 图联邦查询引擎 SDK 规范文档

## 1. 概述

本 SDK 位于应用层与底层数据源之间，把受限 Cypher 作为统一查询协议，完成解析、重写、联邦执行和结果聚合。

核心处理流程：

```text
Cypher
  -> Parser / AST
  -> Rewriter / ExecutionPlan
  -> Federated Executor
  -> Result Stitcher / Sort / Limit / Union
  -> JSON
```

## 2. 语法范围

### 2.1 支持的子句

- `MATCH`
- `RETURN`
- `WITH`
- `WHERE`
- `ORDER BY`
- `LIMIT`
- `UNION`
- `UNION ALL`
- `USING SNAPSHOT`
- `PROJECT BY`

### 2.2 不支持的子句

- `OPTIONAL MATCH`
- `UNWIND`
- `CALL`
- `SKIP`
- 所有写语句：`CREATE`、`MERGE`、`DELETE`、`DETACH DELETE`、`SET`、`REMOVE`

### 2.3 模式与表达式约束

- `MATCH` 的起点节点必须显式声明 `label`
- 支持命名路径、多关系类型、属性字面量过滤
- 不支持变长路径
- 支持比较、`AND/OR/NOT`、`IS NULL`、`IS NOT NULL`、`IN`、`STARTS WITH`、`ENDS WITH`、`CONTAINS`
- 聚合函数仅支持 `count`、`sum`、`avg`、`min`、`max`
- 跨数据源聚合字段不允许

## 3. 联邦边界约束

### 3.1 虚拟边与虚拟点

- 虚拟边仅允许出现在多跳路径的第一跳或最后一跳
- 单跳虚拟边不允许
- 纯虚拟点不允许
- 虚拟到虚拟路径不允许
- `[物理] -> [虚拟] -> [物理]` 三明治结构不允许

### 3.2 执行顺序

| 场景 | 执行顺序 |
|------|----------|
| 第一跳虚拟 | 外部数据源 -> 提取映射字段 -> TuGraph |
| 最后一跳虚拟 | TuGraph -> 提取映射字段 -> 外部数据源 |
| 混合边 `|` | 物理边与虚拟边拆分执行，最终合并 |

`VirtualEdgeBinding.idMapping` 统一按“物理字段 -> 外部字段”定义；第一跳虚拟边按反向关联使用该映射。

## 4. WHERE、排序与限制

### 4.1 WHERE 下沉

- `WHERE` 按变量归属统一下沉到对应数据源
- 物理条件进入 TuGraph 查询
- 虚拟条件进入外部查询条件
- 不再保留旧的“只允许部分下沉”的限制口径

### 4.2 排序与结果限制

- `SKIP` 不支持，解析阶段直接报错
- 支持 `ORDER BY` 和 `LIMIT`
- 默认有效限制：
  - 含外部查询的计划：`1000`
  - 纯 TuGraph 计划：`5000`
- 最终总结果集统一截断到 `8000`

## 5. 批量执行约束

- 禁止按单个 ID 循环请求外部数据源
- `BatchingStrategy` 必须按同源同操作同过滤条件合并请求
- 同一批次一次性传递完整 ID 集合

## 6. 元数据要求

### 6.1 数据源元数据

| 字段 | 说明 |
|------|------|
| `name` | 数据源名称 |
| `type` | 数据源类型 |
| `endpoint` | 服务地址 |

### 6.2 标签元数据

| 字段 | 说明 |
|------|------|
| `label` | 标签名 |
| `virtual` | 是否虚拟标签 |
| `dataSource` | 所属数据源 |

### 6.3 虚拟边绑定

| 字段 | 说明 |
|------|------|
| `edgeType` | 边类型 |
| `targetDataSource` | 目标数据源 |
| `targetLabel` | 目标标签 |
| `operatorName` | 外部算子名 |
| `idMapping` | 物理字段 -> 外部字段 |
| `firstHopOnly` | 仅允许第一跳 |
| `lastHopOnly` | 仅允许最后一跳 |

## 7. 返回格式

### 7.1 行结果

```json
[
  {
    "n": {
      "id": "node-id",
      "label": "Label",
      "name": "value"
    }
  }
]
```

### 7.2 路径结果

```json
[
  {
    "p": [
      [
        {"type": "node", "label": "A", "props": {"id": "1"}},
        {"type": "edge", "label": "REL", "props": {}},
        {"type": "node", "label": "B", "props": {"id": "2"}}
      ]
    ]
  }
]
```

## 8. 测试要求

- Parser/rewriter/E2E 必须以 `docs/rules/scope.md` 为唯一准入口径
- 旧语义测试不得再把 `OPTIONAL MATCH`、`UNWIND`、`CALL`、`SKIP`、变长路径、纯虚拟点等视为成功能力
- 新增标签、虚拟边和映射关系时，测试必须先注册完整元数据
