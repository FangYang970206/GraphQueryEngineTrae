# UT 未覆盖功能清单

> 基于 `docs/cases/cypher_case.md` 与现有 UT 对比分析
> 分析日期：2026-04-09

## 概述

本文档记录了 `cypher_case.md` 中定义但现有 UT 未覆盖的功能点。

| 分类 | 已覆盖 | 未覆盖 | 覆盖率 |
|------|--------|--------|--------|
| 操作符 | 8 | 10 | 44% |
| 查询子句 | 18 | 12 | 60% |
| 函数 | 1 | 34 | 3% |
| TuGraph扩展 | 3 | 0 | 100% |
| **总计** | **30** | **56** | **35%** |

---

## 1. 操作符未覆盖项

### 1.1 General Operators

| 功能 | 示例 | 状态 | 备注 |
|------|------|------|------|
| DISTINCT 去重 | `MATCH (p:person) RETURN DISTINCT p.born` | ❌ 未覆盖 | RETURN DISTINCT 语义未测试 |
| 嵌套属性访问 | `RETURN p.person.name` | ❌ 未覆盖 | WITH 子句中的嵌套属性访问 |

### 1.2 Mathematical Operators

| 功能 | 示例 | 状态 | 备注 |
|------|------|------|------|
| 幂运算 (^) | `RETURN number ^ exponent AS result` | ❌ 未覆盖 | 数学幂运算 |
| 一元负号 | `RETURN -a AS result` | ❌ 未覆盖 | 负号运算 |
| 取模 (%) | `RETURN a % b AS result` | ❌ 未覆盖 | 模运算 |

### 1.3 String-specific Comparison Operators

| 功能 | 示例 | 状态 | 备注 |
|------|------|------|------|
| REGEXP 正则匹配 | `WHERE candidate REGEXP 'Jo.*n'` | ❌ 未覆盖 | 正则表达式匹配 |

### 1.4 Boolean Operators

| 功能 | 示例 | 状态 | 备注 |
|------|------|------|------|
| XOR 异或 | `WHERE n.name = 'A' XOR n.born > 1965` | ❌ 未覆盖 | 异或逻辑运算 |

### 1.5 String Operators

| 功能 | 示例 | 状态 | 备注 |
|------|------|------|------|
| 字符串拼接 (+) | `RETURN 'Hello' + ' ' + 'World' AS greeting` | ❌ 未覆盖 | 字符串连接运算 |

### 1.6 List Operators

| 功能 | 示例 | 状态 | 备注 |
|------|------|------|------|
| 列表拼接 (+) | `RETURN [1,2,3]+[4,5] AS myList` | ❌ 未覆盖 | 列表连接运算 |
| 列表切片 | `RETURN names[1..3] AS result` | ❌ 未覆盖 | 列表范围访问 |
| 动态属性访问 ([]) | `RETURN n[property]` | ❌ 未覆盖 | 动态属性名访问 |

---

## 2. 查询子句未覆盖项

### 2.1 MATCH 子句

| 功能 | 示例 | 状态 | 备注 |
|------|------|------|------|
| 可变长度关系 | `MATCH (a)-[:acted_in*1..3]->(b)` | ❌ 未覆盖 | 变长路径查询 |
| 零长度路径 | `MATCH (a)-[*0..1]-(x)` | ❌ 未覆盖 | 包含自身的路径 |
| 按 ID 查询 | `WHERE id(n) = 0` | ❌ 未覆盖 | 内置 id() 函数查询 |
| 按 euid 查询 | `WHERE euid(r) = "0_3937_0_0_0"` | ❌ 未覆盖 | 关系 ID 查询 |

### 2.2 OPTIONAL MATCH

| 功能 | 示例 | 状态 | 备注 |
|------|------|------|------|
| 可选匹配语义 | `OPTIONAL MATCH (p)-[r]->(m) RETURN p, m` | ❌ 未覆盖 | 仅解析测试，无语义测试 |

**说明**：OPTIONAL MATCH 的核心语义是"无匹配时返回 NULL 而非过滤行"，需要专门测试：
- 有匹配时的结果
- 无匹配时返回 NULL 值
- 与 WHERE 组合使用

### 2.3 RETURN 子句

| 功能 | 示例 | 状态 | 备注 |
|------|------|------|------|
| 返回关系 | `MATCH (a)-[r]->(b) RETURN r` | ❌ 未覆盖 | 关系对象返回 |
| DISTINCT | `RETURN DISTINCT b` | ❌ 未覆盖 | 结果去重 |

### 2.4 WHERE 子句

| 功能 | 示例 | 状态 | 备注 |
|------|------|------|------|
| 按标签过滤 | `WHERE n:person` | ❌ 未覆盖 | 标签存在性检查 |
| 按关系属性过滤 | `WHERE k.role = "Trinity"` | ❌ 未覆盖 | 关系属性条件 |
| exists() 函数 | `WHERE exists(n.born)` | ❌ 未覆盖 | 属性存在性检查 |
| 否定字符串匹配 | `WHERE NOT n.name ENDS WITH 's'` | ❌ 未覆盖 | NOT + 字符串操作符 |
| 关系类型过滤 | `WHERE type(r) STARTS WITH 'ac'` | ❌ 未覆盖 | type() 函数 + 字符串匹配 |

### 2.5 WITH 子句

| 功能 | 示例 | 状态 | 备注 |
|------|------|------|------|
| 聚合后过滤 | `WITH n.type AS type, count(*) AS cnt WHERE cnt > 1` | ❌ 未覆盖 | 聚合函数 + HAVING 语义 |

### 2.6 ORDER BY 子句

| 功能 | 示例 | 状态 | 备注 |
|------|------|------|------|
| 多字段排序 | `ORDER BY n.born DESC, n.name ASC` | ❌ 未覆盖 | 多字段组合排序 |

---

## 3. 函数未覆盖项

### 3.1 Predicate Functions

| 函数 | 示例 | 状态 |
|------|------|------|
| exists() | `WHERE exists(n.born)` | ❌ 未覆盖 |
| date() | `RETURN date() AS date` | ❌ 未覆盖 |
| datetime() | `RETURN datetime() AS datetime` | ❌ 未覆盖 |

### 3.2 Scalar Functions

| 函数 | 示例 | 状态 |
|------|------|------|
| id() | `RETURN id(a)` | ❌ 未覆盖 |
| euid() | `WHERE euid(r) = "..."` | ❌ 未覆盖 |
| properties() | `RETURN properties(n)` | ❌ 未覆盖 |
| head() | `RETURN head(coll)` | ❌ 未覆盖 |
| last() | `RETURN last(coll)` | ❌ 未覆盖 |
| toBoolean() | `RETURN toBoolean('true')` | ❌ 未覆盖 |
| toFloat() | `RETURN toFloat('11.5')` | ❌ 未覆盖 |
| toInteger() | `RETURN toInteger('2.3')` | ❌ 未覆盖 |
| toString() | `RETURN toString(2.3)` | ❌ 未覆盖 |
| type() | `RETURN type(r)` | ❌ 未覆盖 |
| startnode() | `RETURN startnode(r)` | ❌ 未覆盖 |
| endnode() | `RETURN endnode(r)` | ❌ 未覆盖 |
| size() | `RETURN size(list)` | ❌ 未覆盖 |
| length() | `RETURN length(str)` | ❌ 未覆盖 |
| substring() | `RETURN substring(name, 0, 3)` | ❌ 未覆盖 |
| concat() | `RETURN concat(a, b)` | ❌ 未覆盖 |
| label() | `RETURN label(n)` | ❌ 未覆盖 |

### 3.3 Aggregating Functions

| 函数 | 示例 | 状态 |
|------|------|------|
| avg() | `RETURN avg(n.born)` | ❌ 未覆盖 |
| collect() | `RETURN collect(n.born)` | ❌ 未覆盖 |
| count() | `RETURN count(*)` | ✅ 已覆盖 |
| max() | `RETURN max(n.born)` | ❌ 未覆盖 |
| min() | `RETURN min(n.born)` | ❌ 未覆盖 |
| percentileCont() | `RETURN percentileCont(n.born, 0.4)` | ❌ 未覆盖 |
| percentileDisc() | `RETURN percentileDisc(n.born, 0.5)` | ❌ 未覆盖 |
| stDev() | `RETURN stDev(n.born)` | ❌ 未覆盖 |
| stDevP() | `RETURN stDevP(n.born)` | ❌ 未覆盖 |
| variance() | `RETURN variance(n.born)` | ❌ 未覆盖 |
| varianceP() | `RETURN varianceP(n.born)` | ❌ 未覆盖 |
| sum() | `RETURN sum(n.born)` | ❌ 未覆盖 |

### 3.4 List Functions

| 函数 | 示例 | 状态 |
|------|------|------|
| keys() | `RETURN keys(a)` | ❌ 未覆盖 |
| labels() | `RETURN labels(a)` | ❌ 未覆盖 |
| nodes() | `RETURN nodes(p)` | ❌ 未覆盖 |
| range() | `RETURN range(0, 10)` | ❌ 未覆盖 |

### 3.5 Mathematical Functions

| 函数 | 示例 | 状态 |
|------|------|------|
| abs() | `RETURN abs(a.born - e.born)` | ❌ 未覆盖 |
| ceil() | `RETURN ceil(0.1)` | ❌ 未覆盖 |
| floor() | `RETURN floor(0.9)` | ❌ 未覆盖 |
| rand() | `RETURN rand()` | ❌ 未覆盖 |
| round() | `RETURN round(0.5)` | ❌ 未覆盖 |
| sign() | `RETURN sign(-5)` | ❌ 未覆盖 |

---

## 4. 优先级建议

### 高优先级（核心功能）

1. **OPTIONAL MATCH 语义测试** - 影响查询结果正确性
2. **可变长度关系** - 常用查询模式
3. **DISTINCT 去重** - 结果正确性关键
4. **聚合函数** (avg, sum, max, min, collect) - 数据分析核心
5. **多字段排序** - 常用排序需求

### 中优先级（常用功能）

1. **字符串函数** (concat, substring, length)
2. **类型转换函数** (toBoolean, toFloat, toInteger, toString)
3. **列表操作** (列表拼接, 切片, head, last)
4. **exists() 函数** - 属性存在性检查
5. **按 ID 查询** - 精确查询场景

### 低优先级（边缘场景）

1. **数学函数** (abs, ceil, floor, round, sign, rand)
2. **统计函数** (stDev, variance, percentileCont/Disc)
3. **日期函数** (date, datetime)
4. **REGEXP 正则匹配**
5. **XOR 异或运算**

---

## 5. 测试文件参考

| 测试文件 | 测试数量 | 主要覆盖内容 |
|----------|----------|--------------|
| `E2ETest.java` | 30+ | MATCH, WHERE, ORDER BY, LIMIT, UNION, WITH, USING SNAPSHOT |
| `LcypherFullCoverageE2ETest.java` | 11 | MATCH, WHERE, UNION, WITH, EXPLAIN, PROFILE, SNAPSHOT, PROJECT BY |
| `ParserTest.java` | 11 | 解析层测试 |
| `RewriterTest.java` | 8 | 查询重写测试 |
| `ExecutorTest.java` | 5 | 执行器测试 |
| `AggregatorTest.java` | 7 | 结果聚合测试 |

---

## 6. 建议的测试用例模板

### OPTIONAL MATCH 语义测试示例

```java
@Test
@DisplayName("OPTIONAL MATCH: 无匹配时返回 NULL")
void optionalMatchNoMatchReturnsNull() throws Exception {
    GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
    ne.setVariableName("n");
    
    tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
            .addEntity(ne));
    
    // 无匹配的 KPI，应返回 NULL
    kpiAdapter.registerResponse("getKPIByNeIds", MockExternalAdapter.MockResponse.create());
    
    String cypher = "MATCH (n:NetworkElement) OPTIONAL MATCH (n)-[:NEHasKPI]->(kpi) RETURN n, kpi";
    
    JsonNode json = objectMapper.readTree(sdk.executeRaw(cypher));
    assertEquals(1, json.size(), "结果应有1条记录");
    JsonNode row = json.get(0);
    assertTrue(row.has("n"), "应有 n 字段");
    assertTrue(row.has("kpi"), "应有 kpi 字段");
    assertTrue(row.get("kpi").isNull(), "无匹配时 kpi 应为 NULL");
}
```

### 可变长度关系测试示例

```java
@Test
@DisplayName("可变长度关系: *1..3 范围查询")
void variableLengthPathRange() throws Exception {
    GraphEntity ne = createNEEntity("ne1", "NE001", "Router");
    GraphEntity ltp1 = createLTPEntity("ltp1", "LTP1", "Port");
    GraphEntity ltp2 = createLTPEntity("ltp2", "LTP2", "Port");
    
    tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
            .addEntity(ne).addEntity(ltp1).addEntity(ltp2));
    
    String cypher = "MATCH (ne:NetworkElement)-[:NEHasLtps*1..2]->(ltp) RETURN ne, ltp";
    
    JsonNode json = objectMapper.readTree(sdk.executeRaw(cypher));
    assertTrue(json.isArray(), "结果必须是数组");
    // 验证路径长度语义
}
```

### DISTINCT 测试示例

```java
@Test
@DisplayName("DISTINCT: 结果去重")
void returnDistinct() throws Exception {
    GraphEntity ne1 = createNEEntity("ne1", "NE001", "Router");
    GraphEntity ne2 = createNEEntity("ne2", "NE002", "Router");
    GraphEntity ne3 = createNEEntity("ne3", "NE001", "Switch"); // 相同 name
    
    tugraphAdapter.registerResponse("cypher", MockExternalAdapter.MockResponse.create()
            .addEntity(ne1).addEntity(ne2).addEntity(ne3));
    
    String cypher = "MATCH (n:NetworkElement) RETURN DISTINCT n.name AS name";
    
    JsonNode json = objectMapper.readTree(sdk.executeRaw(cypher));
    Set<String> names = new HashSet<>();
    for (JsonNode row : json) {
        names.add(row.get("name").asText());
    }
    assertEquals(json.size(), names.size(), "DISTINCT 应去除重复值");
}
```

---

## 附录：已覆盖功能清单

### 操作符
- ✅ 比较操作符 (=, <>, <, >, <=, >=)
- ✅ IS NULL / IS NOT NULL
- ✅ STARTS WITH / ENDS WITH / CONTAINS
- ✅ AND / OR / NOT
- ✅ IN 操作符
- ✅ 属性访问 (.)
- ✅ 算术运算 (+, -, *, /)

### 查询子句
- ✅ MATCH 基本查询
- ✅ MATCH 多关系类型 (|)
- ✅ MATCH 命名路径 (p=)
- ✅ WHERE 条件过滤
- ✅ WHERE 字符串匹配
- ✅ WHERE NULL 处理
- ✅ WHERE 范围查询
- ✅ RETURN 节点/属性
- ✅ RETURN 别名 (AS)
- ✅ WITH 子句
- ✅ WITH ORDER BY + LIMIT
- ✅ UNWIND (解析)
- ✅ ORDER BY ASC/DESC
- ✅ SKIP / LIMIT
- ✅ UNION / UNION ALL

### TuGraph 扩展
- ✅ USING SNAPSHOT
- ✅ PROJECT BY
- ✅ 多关系类型

### 其他
- ✅ CASE 表达式
- ✅ EXPLAIN / PROFILE
- ✅ 参数化查询 ($param)
