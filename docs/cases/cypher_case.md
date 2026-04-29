# 图联邦查询引擎 Cypher 示例

> 本文档只保留 `docs/rules/scope.md` 范围内的正向示例；超范围语法统一放到“拒绝示例”。

## 1. 支持示例

### 1.1 基础 MATCH

```cypher
MATCH (n:NetworkElement)
RETURN n
```

```cypher
MATCH (ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp:LTP)
RETURN ne, ltp
```

### 1.2 多关系类型与命名路径

```cypher
MATCH p = (ne:NetworkElement)-[:NEHasLtps|NEHasAlarms]->(target)
RETURN p
```

### 1.3 WHERE

```cypher
MATCH (n:NetworkElement)
WHERE n.name = 'NE001'
RETURN n
```

```cypher
MATCH (n:NetworkElement)
WHERE n.name STARTS WITH 'NE' AND n.type IS NOT NULL
RETURN n
```

```cypher
MATCH (n:NetworkElement)
WHERE n.name IN ['NE001', 'NE002']
RETURN n
```

### 1.4 WITH / ORDER BY / LIMIT

```cypher
MATCH (n:NetworkElement)
WITH n
WHERE n.type = 'Router'
RETURN n
ORDER BY n.name DESC
LIMIT 10
```

```cypher
MATCH (n:NetworkElement)
WITH n.type AS type, count(*) AS cnt
WHERE cnt > 1
RETURN type, cnt
```

### 1.5 UNION

```cypher
MATCH (ne:NetworkElement)
RETURN ne.name AS name
UNION ALL
MATCH (ltp:LTP)
RETURN ltp.name AS name
```

### 1.6 聚合

```cypher
MATCH (n:NetworkElement)
RETURN count(*) AS total
```

```cypher
MATCH (n:NetworkElement)
RETURN max(n.priority) AS maxPriority, min(n.priority) AS minPriority
```

### 1.7 首跳/末跳虚拟边

```cypher
MATCH (card:Card {name: 'card001'})-[:CardLocateInNe]->(ne:NetworkElement)
RETURN ne
```

```cypher
MATCH (ne:NetworkElement)-[:NEHasLtps]->(ltp:LTP)-[:LTPHasKPI2]->(kpi:KPI)
RETURN ne, ltp, kpi
```

### 1.8 TuGraph 扩展

```cypher
USING SNAPSHOT('latest', 1704067200) ON [Card]
MATCH (card:Card)
RETURN card
```

```cypher
MATCH p = (ne:NetworkElement)-[:NEHasLtps]->(ltp:LTP)
RETURN p PROJECT BY {'LTP': ['name', 'resId']}
```

## 2. 拒绝示例

以下语句应在解析或范围校验阶段直接失败：

```cypher
MATCH (n)
RETURN n
```

```cypher
OPTIONAL MATCH (n:NetworkElement)
RETURN n
```

```cypher
UNWIND [1, 2, 3] AS x
RETURN x
```

```cypher
MATCH (n:NetworkElement)
RETURN n
SKIP 10
```

```cypher
CALL db.labels()
```

```cypher
MATCH p = (ne:NetworkElement)-[:NEHasLtps*1..3]->(ltp:LTP)
RETURN p
```

```cypher
MATCH (ne:NetworkElement)-[:NEHasKPI]->(kpi:KPI)
RETURN ne, kpi
```

```cypher
MATCH (kpi:KPI)
RETURN kpi
```

## 3. 说明

- 起点必须带 `label`
- `SKIP`、`OPTIONAL MATCH`、`UNWIND`、`CALL`、变长路径、单跳虚拟边、纯虚拟点均不在当前支持范围内
- `WHERE` 会按变量归属分别下沉到 TuGraph 或外部数据源
