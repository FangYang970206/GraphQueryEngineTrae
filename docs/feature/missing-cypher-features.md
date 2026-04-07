# Cypher 功能缺失分析报告

**分析日期**: 2026-04-07  
**分析范围**: Lcypher.g4 语法定义 vs 项目实现  
**最后更新**: 2026-04-07

---

## 一、已实现功能

### 1.1 核心查询功能 ✅

| 功能 | 语法示例 | 实现状态 |
|------|---------|---------|
| MATCH | `MATCH (n:Label) RETURN n` | ✅ 已实现 |
| OPTIONAL MATCH | `OPTIONAL MATCH (n:Label) RETURN n` | ✅ 已实现 |
| WHERE | `MATCH (n) WHERE n.name = 'x' RETURN n` | ✅ 已实现 |
| RETURN | `RETURN n.name, n.age` | ✅ 已实现 |
| ORDER BY | `ORDER BY n.name DESC` | ✅ 已实现 |
| LIMIT | `LIMIT 10` | ✅ 已实现 |
| SKIP | `SKIP 5` | ✅ 已实现 |
| UNION | `MATCH ... UNION MATCH ...` | ✅ 已实现 |
| UNION ALL | `MATCH ... UNION ALL MATCH ...` | ✅ 已实现 |
| WITH | `WITH n ORDER BY n.name LIMIT 5` | ✅ 已实现 |
| UNWIND | `UNWIND [1,2, 3] AS x RETURN x` | ✅ 已实现 |

### 1.2 TuGraph 扩展语法 ✅

| 功能 | 语法示例 | 实现状态 |
|------|---------|---------|
| USING SNAPSHOT | `USING SNAPSHOT('name', 1704067200) ON [Label]` | ✅ 已实现 |
| PROJECT BY | `PROJECT BY {'Label': ['field1', 'field2']}` | ✅ 已实现 |

### 1.3 模式匹配 ✅

| 功能 | 语法示例 | 实现状态 |
|------|---------|---------|
| 节点模式 | `(n:Label {prop: value})` | ✅ 已实现 |
| 关系模式 | `-[r:TYPE]->` | ✅ 已实现 |
| 多关系类型 | `-[r:TYPE1|TYPE2]->` | ✅ 已实现 |
| 变长路径 | `-[r*1..3]->` | ⚠️ 解析存在，执行待验证 |
| 命名路径 | `p = (a)-[r]->(b)` | ⚠️ 解析存在，执行待验证 |

---

## 二、表达式功能

### 2.1 已实现 ✅

| 功能 | 语法示例 | 实现状态 |
|------|---------|---------|
| 属性访问 | `n.name` | ✅ 已实现 |
| 比较运算 | `n.age > 18`, `n.name = 'x'` | ✅ 已实现 |
| 逻辑运算 | `AND`, `OR`, `NOT` | ✅ 已实现 |
| 参数 | `$paramName` | ✅ 已实现 |
| IN 操作符 | `n.id IN [1, 2, 3]` | ✅ 已实现 |
| NULL 判断 | `n.prop IS NULL`, `n.prop IS NOT NULL` | ✅ 已实现 |
| 字符串匹配 | `n.name STARTS WITH 'A'` | ✅ 已实现 |
| 字符串匹配 | `n.name ENDS WITH 'A'` | ✅ 已实现 |
| 字符串匹配 | `n.name CONTAINS 'A'` | ✅ 已实现 |

### 2.2 聚合函数 ✅

| 功能 | 语法示例 | 实现状态 |
|------|---------|---------|
| COUNT | `count(n)` | ✅ 已实现 |
| COUNT(*) | `count(*)` | ✅ 已实现 |
| SUM | `sum(n.age)` | ✅ 已实现 |
| AVG | `avg(n.age)` | ✅ 已实现 |
| MIN | `min(n.age)` | ✅ 已实现 |
| MAX | `max(n.age)` | ✅ 已实现 |
| COLLECT | `collect(n.name)` | ✅ 已实现 |

### 2.3 CASE 表达式 ✅

| 功能 | 语法示例 | 实现状态 |
|------|---------|---------|
| CASE WHEN | `CASE WHEN n.age > 18 THEN 'adult' ELSE 'minor' END` | ✅ 已实现 |
| CASE 简单形式 | `CASE n.type WHEN 'Router' THEN 1 WHEN 'Switch' THEN 2 ELSE 0 END` | ✅ 已实现 |

---

## 三、待实现功能

### 3.1 读取子句 (P2 优先级)

| 功能 | 语法示例 | 实现状态 | 影响范围 |
|------|---------|---------|---------|
| CALL (存储过程) | `CALL db.labels() YIELD label` | ❌ 未实现 | 存储过程调用 |

### 3.2 查询提示 (P3 优先级)

| 功能 | 语法示例 | 实现状态 | 影响范围 |
|------|---------|---------|---------|
| USING JOIN ON | `USING JOIN ON n` | ❌ 未实现 | 查询优化提示 |
| USING START ON | `USING START ON n` | ❌ 未实现 | 起始点提示 |

### 3.3 列表推导式 (P3 优先级)

| 功能 | 语法示例 | 实现状态 |
|------|---------|---------|
| 列表推导式 | `[x IN [1,2,3] WHERE x > 1 \| x * 2]` | ⚠️ 解析存在 |
| 模式推导式 | `[(n)-[r]->(m) \| m.name]` | ⚠️ 解析存在 |

### 3.4 正则匹配 (P3 优先级)

| 功能 | 语法示例 | 实现状态 |
|------|---------|---------|
| 正则匹配 | `n.name REGEXP '^A.*'` | ⚠️ 解析存在 |

---

## 四、更新操作 (只读限制)

| 功能 | 语法示例 | 实现状态 | 备注 |
|------|---------|---------|------|
| CREATE | `CREATE (n:Label)` | ❌ 不支持 | 只读设计 |
| MERGE | `MERGE (n:Label)` | ❌ 不支持 | 只读设计 |
| DELETE | `DELETE n` | ❌ 不支持 | 只读设计 |
| SET | `SET n.prop = value` | ❌ 不支持 | 只读设计 |
| REMOVE | `REMOVE n.prop` | ❌ 不支持 | 只读设计 |

---

## 五、实现状态汇总

| 优先级 | 功能 | 状态 |
|--------|------|------|
| P1 | OPTIONAL MATCH | ✅ 已实现 |
| P1 | UNWIND | ✅ 已实现 |
| P1 | 聚合函数 (count, sum, avg, min, max, collect) | ✅ 已实现 |
| P2 | IN 操作符 | ✅ 已实现 |
| P2 | NULL 判断 | ✅ 已实现 |
| P2 | 字符串匹配 (STARTS/ENDS/CONTAINS) | ✅ 已实现 |
| P2 | CASE 表达式 | ✅ 已实现 |
| P3 | CALL 存储过程 | ❌ 未实现 |
| P3 | 查询提示 | ❌ 未实现 |
| P3 | 列表推导式 | ⚠️ 解析存在 |
| P3 | 正则匹配 | ⚠️ 解析存在 |

---

## 六、相关文件

| 文件 | 说明 |
|------|------|
| `src/main/antlr4/com/federatedquery/grammar/Lcypher.g4` | ANTLR4 语法定义 |
| `src/main/java/com/federatedquery/parser/CypherASTVisitor.java` | AST 访问器 |
| `src/main/java/com/federatedquery/ast/` | AST 节点定义 |
| `src/main/java/com/federatedquery/sdk/GraphQuerySDK.java` | SDK 执行引擎 |
| `docs/SPEC.md` | SDK 规范文档 |

---

**分析人**: AI Assistant  
**状态**: ✅ 大部分已实现
