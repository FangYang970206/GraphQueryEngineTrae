# Cypher 功能缺失分析报告

**分析日期**: 2026-04-07  
**分析范围**: Lcypher.g4 语法定义 vs 项目实现

---

## 一、已实现功能

### 1.1 核心查询功能 ✅

| 功能 | 语法示例 | 实现状态 |
|------|---------|---------|
| MATCH | `MATCH (n:Label) RETURN n` | ✅ 已实现 |
| WHERE | `MATCH (n) WHERE n.name = 'x' RETURN n` | ✅ 已实现 |
| RETURN | `RETURN n.name, n.age` | ✅ 已实现 |
| ORDER BY | `ORDER BY n.name DESC` | ✅ 已实现 |
| LIMIT | `LIMIT 10` | ✅ 已实现 |
| SKIP | `SKIP 5` | ✅ 已实现 |
| UNION | `MATCH ... UNION MATCH ...` | ✅ 已实现 |
| UNION ALL | `MATCH ... UNION ALL MATCH ...` | ✅ 已实现 |
| WITH | `WITH n ORDER BY n.name LIMIT 5` | ✅ 已实现 |

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

## 二、未实现功能

### 2.1 读取子句 (P1 优先级)

| 功能 | 语法示例 | 实现状态 | 影响范围 |
|------|---------|---------|---------|
| OPTIONAL MATCH | `OPTIONAL MATCH (n:Label) RETURN n` | ❌ 未实现 | 左外连接场景 |
| UNWIND | `UNWIND [1,2,3] AS x RETURN x` | ❌ 未实现 | 数组展开场景 |
| CALL (存储过程) | `CALL db.labels() YIELD label` | ❌ 未实现 | 存储过程调用 |

### 2.2 查询提示 (P2 优先级)

| 功能 | 语法示例 | 实现状态 | 影响范围 |
|------|---------|---------|---------|
| USING JOIN ON | `USING JOIN ON n` | ❌ 未实现 | 查询优化提示 |
| USING START ON | `USING START ON n` | ❌ 未实现 | 起始点提示 |

### 2.3 查询分析 (P2 优先级)

| 功能 | 语法示例 | 实现状态 | 影响范围 |
|------|---------|---------|---------|
| EXPLAIN | `EXPLAIN MATCH ...` | ❌ 未实现 | 查询计划分析 |
| PROFILE | `PROFILE MATCH ...` | ❌ 未实现 | 查询执行分析 |

### 2.4 更新操作 (P3 优先级 - 只读限制)

| 功能 | 语法示例 | 实现状态 | 备注 |
|------|---------|---------|------|
| CREATE | `CREATE (n:Label)` | ❌ 不支持 | 只读设计 |
| MERGE | `MERGE (n:Label)` | ❌ 不支持 | 只读设计 |
| DELETE | `DELETE n` | ❌ 不支持 | 只读设计 |
| SET | `SET n.prop = value` | ❌ 不支持 | 只读设计 |
| REMOVE | `REMOVE n.prop` | ❌ 不支持 | 只读设计 |

---

## 三、表达式功能

### 3.1 已实现 ✅

| 功能 | 语法示例 | 实现状态 |
|------|---------|---------|
| 属性访问 | `n.name` | ✅ 已实现 |
| 比较运算 | `n.age > 18`, `n.name = 'x'` | ✅ 已实现 |
| 逻辑运算 | `AND`, `OR`, `NOT` | ✅ 已实现 |
| 参数 | `$paramName` | ✅ 已实现 |

### 3.2 待验证 ⚠️

| 功能 | 语法示例 | 实现状态 |
|------|---------|---------|
| CASE 表达式 | `CASE WHEN n.age > 18 THEN 'adult' ELSE 'minor' END` | ⚠️ 解析存在 |
| 列表推导式 | `[x IN [1,2,3] WHERE x > 1 \| x * 2]` | ⚠️ 解析存在 |
| 模式推导式 | `[(n)-[r]->(m) \| m.name]` | ⚠️ 解析存在 |
| 函数调用 | `count(n)`, `sum(n.age)` | ⚠️ 解析存在 |
| 字符串匹配 | `n.name STARTS WITH 'A'` | ⚠️ 解析存在 |
| 正则匹配 | `n.name REGEXP '^A.*'` | ⚠️ 解析存在 |
| IN 操作符 | `n.id IN [1,2,3]` | ⚠️ 解析存在 |
| NULL 判断 | `n.prop IS NULL` | ⚠️ 解析存在 |
| 列表切片 | `list[1..3]` | ⚠️ 解析存在 |

### 3.3 聚合函数

| 功能 | 语法示例 | 实现状态 |
|------|---------|---------|
| COUNT | `count(n)` | ⚠️ 解析存在 |
| COUNT(*) | `count(*)` | ⚠️ 解析存在 |
| ALL | `ALL(x IN list WHERE x > 0)` | ⚠️ 解析存在 |
| ANY | `ANY(x IN list WHERE x > 0)` | ⚠️ 解析存在 |
| NONE | `NONE(x IN list WHERE x > 0)` | ⚠️ 解析存在 |
| SINGLE | `SINGLE(x IN list WHERE x > 0)` | ⚠️ 解析存在 |

---

## 四、建议实现优先级

### P1 - 高优先级

1. **OPTIONAL MATCH** - 左外连接是常见需求
2. **UNWIND** - 数组展开是数据处理基础功能
3. **函数调用** - count, sum, avg 等聚合函数

### P2 - 中优先级

1. **IN 操作符** - 常用过滤条件
2. **NULL 判断** - 数据完整性检查
3. **字符串匹配** - STARTS WITH, ENDS WITH, CONTAINS
4. **CASE 表达式** - 条件逻辑

### P3 - 低优先级

1. **CALL 存储过程** - 特定场景使用
2. **查询提示** - 优化场景使用
3. **EXPLAIN/PROFILE** - 调试场景使用
4. **正则匹配** - 特定场景使用

---

## 五、相关文件

| 文件 | 说明 |
|------|------|
| `src/main/antlr4/com/federatedquery/grammar/Lcypher.g4` | ANTLR4 语法定义 |
| `src/main/java/com/federatedquery/parser/CypherASTVisitor.java` | AST 访问器 |
| `src/main/java/com/federatedquery/ast/` | AST 节点定义 |
| `docs/SPEC.md` | SDK 规范文档 |

---

**分析人**: AI Assistant  
**状态**: 待实现
