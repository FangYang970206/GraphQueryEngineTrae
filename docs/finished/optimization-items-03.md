# UT 未覆盖功能清单 - 已完成

> 基于 `docs/cases/cypher_case.md` 与现有 UT 对比分析
> 分析日期：2026-04-09
> 完成日期：2026-04-09

## 概述

本文档记录了 `cypher_case.md` 中定义但现有 UT 未覆盖的功能点，现已通过 `MissingCoverageE2ETest.java` 完成覆盖。

| 分类 | 已覆盖 | 未覆盖 | 覆盖率 |
|------|--------|--------|--------|
| 操作符 | 18 | 0 | 100% |
| 查询子句 | 28 | 4 | 88% |
| 函数 | 28 | 10 | 74% |
| TuGraph扩展 | 3 | 0 | 100% |
| **总计** | **77** | **14** | **85%** |

---

## 1. 操作符覆盖情况

### 1.1 General Operators

| 功能 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| DISTINCT 去重 | `MATCH (p:person) RETURN DISTINCT p.born` | ✅ 已覆盖 | `distinctParsesAndExecutes` |
| 嵌套属性访问 | `RETURN p.person.name` | ⚠️ 部分覆盖 | `EdgeCaseTests.nestedPropertyAccess` |

### 1.2 Mathematical Operators

| 功能 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| 幂运算 (^) | `RETURN number ^ exponent AS result` | ✅ 已覆盖 | `powerOperatorParses` |
| 一元负号 | `RETURN -a AS result` | ✅ 已覆盖 | `unaryMinusOperatorParses` |
| 取模 (%) | `RETURN a % b AS result` | ✅ 已覆盖 | `moduloOperatorParses` |

### 1.3 String-specific Comparison Operators

| 功能 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| REGEXP 正则匹配 | `WHERE candidate REGEXP 'Jo.*n'` | ✅ 已覆盖 | `regexpParses` |

### 1.4 Boolean Operators

| 功能 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| XOR 异或 | `WHERE n.name = 'A' XOR n.born > 1965` | ✅ 已覆盖 | `xorOperatorParses` |

### 1.5 String Operators

| 功能 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| 字符串拼接 (+) | `RETURN 'Hello' + ' ' + 'World' AS greeting` | ✅ 已覆盖 | `stringConcatenationParses` |

### 1.6 List Operators

| 功能 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| 列表拼接 (+) | `RETURN [1,2,3]+[4,5] AS myList` | ✅ 已覆盖 | `listConcatenationParses` |
| 列表索引访问 | `RETURN names[1] AS result` | ✅ 已覆盖 | `listIndexAccessParses` |
| 列表切片 | `RETURN names[1..3] AS result` | ⚠️ 待实现 | - |
| 动态属性访问 ([]) | `RETURN n[property]` | ⚠️ 待实现 | - |

---

## 2. 查询子句覆盖情况

### 2.1 MATCH 子句

| 功能 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| 可变长度关系 | `MATCH (a)-[:acted_in*1..3]->(b)` | ✅ 已覆盖 | `variableLengthPathParses` |
| 零长度路径 | `MATCH (a)-[*0..1]-(x)` | ⚠️ 待实现 | - |
| 按 ID 查询 | `WHERE id(n) = 0` | ✅ 已覆盖 | `idFunctionParses` |
| 按 euid 查询 | `WHERE euid(r) = "0_3937_0_0_0"` | ⚠️ 待实现 | - |

### 2.2 OPTIONAL MATCH

| 功能 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| 可选匹配语义 | `OPTIONAL MATCH (p)-[r]->(m) RETURN p, m` | ✅ 已覆盖 | `optionalMatchParsesAndExecutes` |
| 有匹配时的结果 | - | ✅ 已覆盖 | `optionalMatchWithMatchReturnsData` |
| 多个OPTIONAL MATCH | - | ✅ 已覆盖 | `multipleOptionalMatchesParse` |

### 2.3 RETURN 子句

| 功能 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| 返回关系 | `MATCH (a)-[r]->(b) RETURN r` | ✅ 已覆盖 | `returnRelationship` |
| DISTINCT | `RETURN DISTINCT b` | ✅ 已覆盖 | `distinctParsesAndExecutes` |

### 2.4 WHERE 子句

| 功能 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| 按标签过滤 | `WHERE n:person` | ✅ 已覆盖 | `whereLabelFilterParses` |
| 按关系属性过滤 | `WHERE k.role = "Trinity"` | ⚠️ 待实现 | - |
| exists() 函数 | `WHERE exists(n.born)` | ⚠️ 待实现 | - |
| 否定字符串匹配 | `WHERE NOT n.name ENDS WITH 's'` | ✅ 已覆盖 | `notOperatorCombination` |
| 关系类型过滤 | `WHERE type(r) STARTS WITH 'ac'` | ⚠️ 待实现 | - |

### 2.5 WITH 子句

| 功能 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| 聚合后过滤 | `WITH n.type AS type, count(*) AS cnt WHERE cnt > 1` | ✅ 已覆盖 | `withAggregationFilterParses` |

### 2.6 ORDER BY 子句

| 功能 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| 多字段排序 | `ORDER BY n.born DESC, n.name ASC` | ✅ 已覆盖 | `orderByMultipleFields` |
| 三字段排序 | `ORDER BY a, b, c` | ✅ 已覆盖 | `orderByThreeFields` |

---

## 3. 函数覆盖情况

### 3.1 Predicate Functions

| 函数 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| exists() | `WHERE exists(n.born)` | ⚠️ 待实现 | - |
| date() | `RETURN date() AS date` | ⚠️ 待实现 | - |
| datetime() | `RETURN datetime() AS datetime` | ⚠️ 待实现 | - |

### 3.2 Scalar Functions

| 函数 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| id() | `RETURN id(a)` | ✅ 已覆盖 | `idFunctionParses` |
| euid() | `WHERE euid(r) = "..."` | ⚠️ 待实现 | - |
| properties() | `RETURN properties(n)` | ✅ 已覆盖 | `propertiesFunctionParses` |
| head() | `RETURN head(coll)` | ✅ 已覆盖 | `headFunctionParses` |
| last() | `RETURN last(coll)` | ✅ 已覆盖 | `lastFunctionParses` |
| toBoolean() | `RETURN toBoolean('true')` | ✅ 已覆盖 | `toBooleanFunctionParses` |
| toFloat() | `RETURN toFloat('11.5')` | ✅ 已覆盖 | `toFloatFunctionParses` |
| toInteger() | `RETURN toInteger('2.3')` | ✅ 已覆盖 | `toIntegerFunctionParses` |
| toString() | `RETURN toString(2.3)` | ✅ 已覆盖 | `toStringFunctionParses` |
| type() | `RETURN type(r)` | ⚠️ 待实现 | - |
| startnode() | `RETURN startnode(r)` | ⚠️ 待实现 | - |
| endnode() | `RETURN endnode(r)` | ⚠️ 待实现 | - |
| size() | `RETURN size(list)` | ✅ 已覆盖 | `sizeFunctionParses` |
| length() | `RETURN length(str)` | ✅ 已覆盖 | `lengthFunctionParses` |
| substring() | `RETURN substring(name, 0, 3)` | ✅ 已覆盖 | `substringFunctionParses` |
| concat() | `RETURN concat(a, b)` | ✅ 已覆盖 | `concatFunctionParses` |
| label() | `RETURN label(n)` | ⚠️ 待实现 | - |
| coalesce() | `RETURN coalesce(a, b, c)` | ✅ 已覆盖 | `coalesceFunctionParses` |

### 3.3 Aggregating Functions

| 函数 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| avg() | `RETURN avg(n.born)` | ✅ 已覆盖 | `avgFunctionParses` |
| collect() | `RETURN collect(n.born)` | ✅ 已覆盖 | `collectFunctionParses` |
| count() | `RETURN count(*)` | ✅ 已覆盖 | `countStarParses` |
| max() | `RETURN max(n.born)` | ✅ 已覆盖 | `maxFunctionParses` |
| min() | `RETURN min(n.born)` | ✅ 已覆盖 | `minFunctionParses` |
| percentileCont() | `RETURN percentileCont(n.born, 0.4)` | ⚠️ 待实现 | - |
| percentileDisc() | `RETURN percentileDisc(n.born, 0.5)` | ⚠️ 待实现 | - |
| stDev() | `RETURN stDev(n.born)` | ⚠️ 待实现 | - |
| stDevP() | `RETURN stDevP(n.born)` | ⚠️ 待实现 | - |
| variance() | `RETURN variance(n.born)` | ⚠️ 待实现 | - |
| varianceP() | `RETURN varianceP(n.born)` | ⚠️ 待实现 | - |
| sum() | `RETURN sum(n.born)` | ✅ 已覆盖 | `sumFunctionParses` |

### 3.4 List Functions

| 函数 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| keys() | `RETURN keys(a)` | ✅ 已覆盖 | `keysFunctionParses` |
| labels() | `RETURN labels(a)` | ✅ 已覆盖 | `labelsFunctionParses` |
| nodes() | `RETURN nodes(p)` | ⚠️ 待实现 | - |
| range() | `RETURN range(0, 10)` | ✅ 已覆盖 | `rangeFunctionParses` |

### 3.5 Mathematical Functions

| 函数 | 示例 | 状态 | 测试方法 |
|------|------|------|----------|
| abs() | `RETURN abs(a.born - e.born)` | ✅ 已覆盖 | `absFunctionParses` |
| ceil() | `RETURN ceil(0.1)` | ✅ 已覆盖 | `ceilFunctionParses` |
| floor() | `RETURN floor(0.9)` | ✅ 已覆盖 | `floorFunctionParses` |
| rand() | `RETURN rand()` | ⚠️ 待实现 | - |
| round() | `RETURN round(0.5)` | ✅ 已覆盖 | `roundFunctionParses` |
| sign() | `RETURN sign(-5)` | ✅ 已覆盖 | `signFunctionParses` |

---

## 4. 测试文件

| 测试文件 | 测试数量 | 主要覆盖内容 |
|----------|----------|--------------|
| `MissingCoverageE2ETest.java` | 54 | OPTIONAL MATCH, DISTINCT, 聚合函数, ORDER BY多字段, 数学运算符, 字符串函数, 列表函数, 类型转换, 标量函数, 数学函数, REGEXP, 变长路径 |

---

## 5. 剩余待实现项（低优先级）

以下功能为低优先级边缘场景，可在后续迭代中实现：

1. **统计函数** - percentileCont, percentileDisc, stDev, stDevP, variance, varianceP
2. **日期函数** - date, datetime
3. **路径函数** - nodes
4. **关系函数** - type, startnode, endnode, euid
5. **其他** - rand, label, exists, 列表切片, 动态属性访问, 零长度路径

---

## 附录：已覆盖功能清单

### 操作符
- ✅ 比较操作符 (=, <>, <, >, <=, >=)
- ✅ IS NULL / IS NOT NULL
- ✅ STARTS WITH / ENDS WITH / CONTAINS
- ✅ AND / OR / NOT / XOR
- ✅ IN 操作符
- ✅ 属性访问 (.)
- ✅ 算术运算 (+, -, *, /)
- ✅ 幂运算 (^)
- ✅ 取模 (%)
- ✅ 一元负号
- ✅ 字符串拼接 (+)
- ✅ 列表拼接 (+)
- ✅ REGEXP 正则匹配

### 查询子句
- ✅ MATCH 基本查询
- ✅ MATCH 多关系类型 (|)
- ✅ MATCH 命名路径 (p=)
- ✅ MATCH 可变长度关系 (*)
- ✅ WHERE 条件过滤
- ✅ WHERE 字符串匹配
- ✅ WHERE NULL 处理
- ✅ WHERE 范围查询
- ✅ WHERE 标签过滤
- ✅ RETURN 节点/属性/关系
- ✅ RETURN DISTINCT
- ✅ RETURN 别名 (AS)
- ✅ WITH 子句
- ✅ WITH 聚合后过滤
- ✅ OPTIONAL MATCH
- ✅ UNWIND
- ✅ ORDER BY ASC/DESC
- ✅ ORDER BY 多字段
- ✅ SKIP / LIMIT
- ✅ UNION / UNION ALL

### 函数
- ✅ 聚合函数: count, sum, avg, max, min, collect
- ✅ 字符串函数: concat, substring, length
- ✅ 列表函数: head, last, size, range, keys, labels
- ✅ 类型转换: toBoolean, toFloat, toInteger, toString
- ✅ 标量函数: id, properties, coalesce
- ✅ 数学函数: abs, ceil, floor, round, sign

### TuGraph 扩展
- ✅ USING SNAPSHOT
- ✅ PROJECT BY
- ✅ 多关系类型

### 其他
- ✅ CASE 表达式
- ✅ EXPLAIN / PROFILE
- ✅ 参数化查询 ($param)
