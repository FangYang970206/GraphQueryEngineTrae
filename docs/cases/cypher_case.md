# TuGraph Cypher 查询语法示例

> 来源：TuGraph DB 4.5.2 官方文档
> 地址：https://tugraph.tech/docs/tugraph-db/zh/4.5.2/query/cypher

> **注意**：本文档仅包含**只读查询**语法，不包含增删改操作。

## 目录

1. [操作符 (Operators)](#1-操作符-operators)
2. [查询子句 (Query Clauses)](#2-查询子句-query-clauses)
3. [函数 (Functions)](#3-函数-functions)
4. [TuGraph 特有扩展](#4-tugraph-特有扩展)

---

## 1. 操作符 (Operators)

### 1.1 操作符支持一览

| 类别 | 支持 | 待支持 |
|------|------|--------|
| General operators | `DISTINCT`, `.` for property access | `[]` for dynamic property access |
| Mathematical operators | `+`, `-`, `*`, `/`, `%`, `^` | |
| Comparison operators | `=`, `<>`, `<`, `>`, `<=`, `>=`, `IS NULL`, `IS NOT NULL` | |
| String-specific comparison operators | `STARTS WITH`, `ENDS WITH`, `CONTAINS`, `REGEXP` | |
| Boolean operators | `AND`, `OR`, `XOR`, `NOT` | |
| String operators | `+` for concatenation | |
| List operators | `+` for concatenation, `IN`, `[]` for accessing element(s) | |

### 1.2 General Operators

#### DISTINCT 去重

```cypher
MATCH (p:person) RETURN DISTINCT p.born
```

#### 嵌套属性访问

```cypher
WITH {person: {name: 'Anne', age: 25}} AS p
RETURN p.person.name
```

### 1.3 Mathematical Operators

#### 幂运算

```cypher
WITH 2 AS number, 3 AS exponent
RETURN number ^ exponent AS result
```

#### 一元负号

```cypher
WITH -3 AS a, 4 AS b
RETURN b - a AS result
```

### 1.4 Comparison Operators

```cypher
WITH 4 AS one, 3 AS two
RETURN one > two AS result
```

### 1.5 String-specific Comparison Operators

#### STARTS WITH

```cypher
WITH ['John', 'Mark', 'Jonathan', 'Bill'] AS somenames
UNWIND somenames AS names
WITH names AS candidate
WHERE candidate STARTS WITH 'Jo'
RETURN candidate
```

#### ENDS WITH

```cypher
MATCH (n)
WHERE n.name ENDS WITH 'ter'
RETURN n.name, n.born
```

#### CONTAINS

```cypher
MATCH (n)
WHERE n.name CONTAINS 'ete'
RETURN n.name, n.born
```

#### REGEXP 正则匹配

```cypher
WITH ['John', 'Mark', 'Jonathan', 'Bill'] AS somenames
UNWIND somenames AS names
WITH names AS candidate
WHERE candidate REGEXP 'Jo.*n'
RETURN candidate
```

### 1.6 Boolean Operators

```cypher
WITH [2, 4, 7, 9, 12] AS numberlist
UNWIND numberlist AS number
WITH number
WHERE number = 4 OR (number > 6 AND number < 10)
RETURN number
```

### 1.7 String Operators

字符串拼接：

```cypher
RETURN 'Hello' + ' ' + 'World' AS greeting
```

### 1.8 List Operators

#### 列表拼接

```cypher
RETURN [1,2,3,4,5]+[6,7] AS myList
```

#### IN 操作符

```cypher
WITH [2, 3, 4, 5] AS numberlist
UNWIND numberlist AS number
WITH number
WHERE number IN [2, 3, 8]
RETURN number
```

#### 列表切片

```cypher
WITH ['Anne', 'John', 'Bill', 'Diane', 'Eve'] AS names
RETURN names[1..3] AS result
```

---

## 2. 查询子句 (Query Clauses)

### 2.1 子句支持一览

| 类别 | 语法 | 备注 |
|------|------|------|
| Reading clauses | `MATCH` | 支持 |
| | `OPTIONAL MATCH` | 支持 |
| Projecting clauses | `RETURN … [AS]` | 支持 |
| | `WITH … [AS]` | 支持 |
| | `UNWIND … [AS]` | 支持 |
| Reading sub-clauses | `WHERE` | 支持 |
| | `ORDER BY [ASC/DESC]` | 支持 |
| | `SKIP` | 支持 |
| | `LIMIT` | 支持 |
| Set operations | `UNION ALL` | 支持 |
| | `UNION` | 待支持 |

### 2.2 MATCH

#### 基本节点查询

```cypher
// 获取所有节点
MATCH (n)
RETURN n

// 获取指定标签的节点
MATCH (movie:movie)
RETURN movie.title

// 关联节点查询
MATCH (person {name: 'Laurence Fishburne'})-[]-(movie)
RETURN movie.title

// 带标签的关联查询
MATCH (:person {name: 'Laurence Fishburne'})-[]-(movie:movie)
RETURN movie.title
```

#### 关系查询

```cypher
// 出边关系
MATCH (:person {name: 'Laurence Fishburne'})-[]->(movie)
RETURN movie.title

// 有向关系和变量
MATCH (:person {name: 'Laurence Fishburne'})-[r]->(movie)
RETURN type(r)

// 匹配关系类型
MATCH (matrix:movie {title: 'The Matrix'})<-[:acted_in]-(actor)
RETURN actor.name

// 匹配多种关系类型
MATCH (matrix {title: 'The Matrix'})<-[:acted_in|:directed]-(person)
RETURN person.name

// 匹配关系类型并使用变量
MATCH (matrix {title: 'The Matrix'})<-[r:acted_in]-(actor)
RETURN r.role
```

#### 深度关系查询

```cypher
// 多跳关系
MATCH (laurence {name: 'Laurence Fishburne'})-[:acted_in]->(movie)<-[:directed]-(director)
RETURN movie.title, director.name

// 可变长度关系
MATCH (laurence {name: 'Laurence Fishburne'})-[:acted_in*1..3]-(movie:movie)
RETURN movie.title

// 可变长度关系变量
MATCH p = (laurence {name: 'Laurence Fishburne'})-[:acted_in*2]-(co_actor)
RETURN p

// 零长度路径
MATCH (matrix:movie {title: 'The Matrix'})-[*0..1]-(x)
RETURN x

// 命名路径
MATCH p = (michael {name: 'Michael Douglas'})-[]->() 
RETURN p
```

#### 按 ID 查询

```cypher
// 按节点 ID 查询
MATCH (n)
WHERE id(n)= 0
RETURN n

// 按关系 ID 查询
MATCH ()-[r]->()
WHERE euid(r) = "0_3937_0_0_0"
RETURN r

// 多个节点 ID
MATCH (n)
WHERE id(n) IN [0, 3, 5]
RETURN n
```

### 2.3 OPTIONAL MATCH

```cypher
// 可选匹配 - 如果没有匹配也不会过滤掉结果
MATCH (p:person)
OPTIONAL MATCH (p)-[r:acted_in]->(m:movie)
RETURN p.name, m.title
```

### 2.4 RETURN

```cypher
// 返回节点
MATCH (n {name: 'Carrie-Anne Moss'}) RETURN n

// 返回关系
MATCH (n {name: 'Carrie-Anne Moss'})-[r:acted_in]->(c)
RETURN r

// 返回属性
MATCH (n {name: 'Carrie-Anne Moss'}) RETURN n.born

// 别名
MATCH (a {name: 'Carrie-Anne Moss'})
RETURN a.born AS SomethingTotallyDifferent

// 可选属性
MATCH (n)
RETURN n.age

// 去重结果
MATCH (a {name: 'Carrie-Anne Moss'})-[]->(b)
RETURN DISTINCT b
```

### 2.5 WHERE

#### 基本用法

```cypher
// 布尔操作
MATCH (n)
WHERE n.name = 'Laurence Fishburne' XOR (n.born > 1965 AND n.name = 'Carrie-Anne Moss')
RETURN n.name, n.born

// 按标签过滤
MATCH (n)
WHERE n:person
RETURN n.name, n.born

// 按属性过滤
MATCH (n)
WHERE n.born > 2000
RETURN n.name, n.born

// 按关系属性过滤
MATCH (n)-[k:acted_in]->(f)
WHERE k.role = "Trinity"
RETURN f.title

// 属性存在检查
MATCH (n)
WHERE exists(n.born)
RETURN n.name, n.born
```

#### 字符串匹配

```cypher
// 前缀匹配
MATCH (n)
WHERE n.name STARTS WITH 'Pet'
RETURN n.name, n.born

// 后缀匹配
MATCH (n)
WHERE n.name ENDS WITH 'ter'
RETURN n.name, n.born

// 包含匹配
MATCH (n)
WHERE n.name CONTAINS 'ete'
RETURN n.name, n.born

// 否定匹配
MATCH (n)
WHERE NOT n.name ENDS WITH 's'
RETURN n.name, n.born

// 关系类型过滤
MATCH (n)-[r]->()
WHERE n.name='Laurence Fishburne' AND type(r) STARTS WITH 'ac'
RETURN type(r), r.role
```

#### 列表操作

```cypher
// IN 操作符
MATCH (a)
WHERE a.name IN ['Laurence Fishburne', 'Tobias']
RETURN a.name, a.born
```

#### NULL 处理

```cypher
// 属性缺失时默认为 false
MATCH (n)
WHERE n.belt = 'white'
RETURN n.name, n.age, n.belt

// 属性缺失时默认为 true
MATCH (n)
WHERE n.belt = 'white' OR n.belt IS NULL 
RETURN n.name, n.age, n.belt
ORDER BY n.name

// 过滤 NULL
MATCH (person)
WHERE person.name = 'Peter' AND person.belt IS NULL 
RETURN person.name, person.age, person.belt
```

#### 范围查询

```cypher
// 简单范围
MATCH (a)
WHERE a.name >= 'Peter'
RETURN a.name, a.born

// 复合范围
MATCH (a)
WHERE a.name > 'Andres' AND a.name < 'Tobias'
RETURN a.name, a.born
```

### 2.6 WITH

```cypher
// 传递中间结果
MATCH (n:person)
WITH n
WHERE n.born > 1960
RETURN n.name, n.born

// 聚合后过滤
MATCH (n:person)
WITH n.type AS type, count(*) AS cnt
WHERE cnt > 1
RETURN type, cnt

// ORDER BY + LIMIT
MATCH (n:person)
WITH n
ORDER BY n.name
LIMIT 10
RETURN n
```

### 2.7 UNWIND

```cypher
// 展开列表
UNWIND [1, 2, 3] AS x
RETURN x

// 展开并过滤
UNWIND ['John', 'Mark', 'Jonathan', 'Bill'] AS names
WITH names AS candidate
WHERE candidate STARTS WITH 'Jo'
RETURN candidate
```

### 2.8 ORDER BY

```cypher
// 升序排序
MATCH (n:person)
RETURN n.name, n.born
ORDER BY n.born ASC

// 降序排序
MATCH (n:person)
RETURN n.name, n.born
ORDER BY n.born DESC

// 多字段排序
MATCH (n:person)
RETURN n.name, n.born
ORDER BY n.born DESC, n.name ASC
```

### 2.9 SKIP / LIMIT

```cypher
// 跳过前3条记录
MATCH (n:person)
RETURN n.name
ORDER BY n.name
SKIP 3

// 限制返回数量
MATCH (n:person)
RETURN n.name
LIMIT 3

// 分页查询
MATCH (n:person)
RETURN n.name
ORDER BY n.name
SKIP 10
LIMIT 10
```

### 2.10 UNION

```cypher
// UNION ALL - 保留重复
MATCH (n:person)
RETURN n.name AS name
UNION ALL 
MATCH (n:movie)
RETURN n.title AS name
```

---

## 3. 函数 (Functions)

### 3.1 函数支持一览

| 种类 | 功能 | 备注 |
|------|------|------|
| Predicate functions | `exists()` | |
| | `date()` | |
| | `datetime()` | |
| Scalar functions | `id()` | |
| | `euid()` | |
| | `properties()` | |
| | `head()` | |
| | `last()` | |
| | `toBoolean()` | |
| | `toFloat()` | |
| | `toInteger()` | |
| | `toString()` | |
| | `type()` | |
| | `startnode()` | |
| | `endnode()` | |
| | `size()` | |
| | `length()` | |
| | `substring()` | |
| | `concat()` | |
| | `label()` | OpenCypher扩展方法 |
| Aggregating functions | `avg()` | |
| | `collect()` | |
| | `count()` | |
| | `max()` | |
| | `min()` | |
| | `percentileCont()` | |
| | `percentileDisc()` | |
| | `stDev()` | |
| | `stDevP()` | |
| | `variance()` | |
| | `varianceP()` | |
| | `sum()` | |
| List functions | `keys()` | |
| | `labels()` | 返回结果有且只有一个label |
| | `nodes()` | |
| | `range()` | |
| Mathematical functions | `abs()` | |
| | `ceil()` | |
| | `floor()` | |
| | `rand()` | |
| | `round()` | |
| | `sign()` | |

### 3.2 Predicate Functions

#### exists()

```cypher
MATCH (n)
WHERE exists(n.born)
RETURN n.name, n.born
```

#### date()

```cypher
RETURN date() AS date
// 输出: 2025-02-10
```

#### datetime()

```cypher
RETURN datetime() AS datetime
// 输出: 2025-02-10 02:35:30.095486000

// 日期时间类型过滤
MATCH (n:person)
WHERE n.born >= '2023-12-31 23:00:00'
RETURN n
```

### 3.3 Scalar Functions

#### id()

```cypher
MATCH (a)
RETURN id(a)
```

#### properties()

```cypher
MATCH (n:person {name: 'Laurence Fishburne'})
RETURN n
```

#### head() / last()

```cypher
WITH ['one','two','three'] AS coll 
RETURN coll, head(coll), last(coll)
```

#### toFloat() / toInteger() / toString()

```cypher
RETURN toFloat('11.5')
RETURN toInteger('2.3') AS integer
RETURN toString(2.3)
```

#### type()

```cypher
MATCH (n)-[r]->()
WHERE n.name = 'Laurence Fishburne'
RETURN type(r)
```

### 3.4 Aggregating Functions

#### avg()

```cypher
MATCH (n:person)
RETURN avg(n.born)
```

#### collect()

```cypher
MATCH (n:person)
RETURN collect(n.born)
```

#### count()

```cypher
MATCH (n {name: 'Laurence Fishburne'})-[]->(x)
RETURN labels(n), n.born, count(*)
```

#### max() / min()

```cypher
MATCH (n:person)
RETURN max(n.born), min(n.born)
```

#### percentileCont() / percentileDisc()

```cypher
MATCH (n:person)
RETURN percentileCont(n.born, 0.4), percentileDisc(n.born, 0.5)
```

#### stDev() / stDevP()

```cypher
MATCH (n)
RETURN stDev(n.born), stDevP(n.born)
```

#### variance() / varianceP()

```cypher
MATCH (n)
RETURN variance(n.born), varianceP(n.born)
```

#### sum()

```cypher
MATCH (n:person)
RETURN sum(n.born)
```

### 3.5 List Functions

#### keys()

```cypher
MATCH (a)
RETURN keys(a) LIMIT 1
// 输出: ["name","age","eyes"]
```

#### labels()

```cypher
MATCH (a)
RETURN labels(a) LIMIT 1
// 输出: ["Person"]
```

#### nodes()

```cypher
MATCH p = (from {name: 'Bob'})-[*1..]->(to {name: 'Alice'})
RETURN nodes(p)
```

### 3.6 Mathematical Functions

#### abs()

```cypher
MATCH (a:person {name: 'Laurence Fishburne'}),(e:person {name: 'Carrie-Anne Moss'})
RETURN a.born, e.born, abs(a.born-e.born)
```

#### ceil() / floor() / round()

```cypher
RETURN ceil(0.1)
RETURN floor(0.9)
RETURN round(0.5)
```

---

## 4. TuGraph 特有扩展

### 4.1 USING SNAPSHOT

TuGraph 支持快照查询：

```cypher
MATCH (n:person)
USING SNAPSHOT 'snapshot_name'
RETURN n
```

### 4.2 PROJECT BY

TuGraph 支持投影查询：

```cypher
MATCH (n:person)
PROJECT BY n.name, n.age
```

### 4.3 多关系类型

```cypher
MATCH (n)-[:TYPE1|:TYPE2|:TYPE3]->(m)
RETURN n, m
```

---

## 附录：不支持的操作

以下操作在图联邦查询引擎中**不支持**，因为 SDK 仅支持只读查询：

| 操作 | 说明 |
|------|------|
| `CREATE` | 创建节点/关系 |
| `MERGE` | 合并节点/关系 |
| `DELETE` | 删除节点/关系 |
| `DETACH DELETE` | 级联删除 |
| `SET` | 设置属性 |
| `REMOVE` | 删除属性 |
| `CALL ... YIELD` | 存储过程调用（需直接走原生 TuGraph，不经过联邦层） |

---

## 图例说明

- ✓ 已支持
- ❏ 待支持/部分支持
- ☒ 不支持
