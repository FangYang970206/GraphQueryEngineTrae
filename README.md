# Graph Query Engine - 图联邦查询引擎

[![Java](https://img.shields.io/badge/Java-17-blue.svg)](https://openjdk.org/)
[![Spring](https://img.shields.io/badge/Spring-6.1.6-green.svg)](https://spring.io/)
[![ANTLR4](https://img.shields.io/badge/ANTLR4-4.13.1-orange.svg)](https://www.antlr.org/)
[![Tests](https://img.shields.io/badge/Tests-37%20passing-brightgreen.svg)]()

一个用于联邦图查询的 Java SDK，将 Cypher 查询分解为物理图查询和外部数据源查询，在内存中聚合异构数据源的结果，以统一图结构返回。

## 特性

- **ANTLR4 解析器**: 完整的 Cypher 语法解析，支持 TuGraph 扩展
- **虚拟边支持**: 自动检测和分离虚拟边查询
- **联邦执行**: 并行执行物理查询和外部数据源请求
- **批量请求**: 防止 N+1 查询风暴
- **计划缓存**: Caffeine 缓存执行计划，提升性能
- **Path 支持**: 完整的路径返回格式

## 快速开始

### 环境要求

- Java 17+
- Maven 3.6+

### 安装

```bash
git clone https://github.com/FangYang970206/GraphQueryEngineTrae.git
cd GraphQueryEngineTrae
mvn install
```

### 基本使用

```java
import com.federatedquery.sdk.GraphQuerySDK;
import com.federatedquery.parser.CypherParserFacade;
import com.federatedquery.rewriter.QueryRewriter;
import com.federatedquery.executor.FederatedExecutor;
import com.federatedquery.aggregator.*;

// 创建组件
CypherParserFacade parser = new CypherParserFacade();
QueryRewriter rewriter = new QueryRewriter(registry, detector);
FederatedExecutor executor = new FederatedExecutor(registry);
ResultStitcher stitcher = new ResultStitcher();
GlobalSorter sorter = new GlobalSorter();
UnionDeduplicator deduplicator = new UnionDeduplicator();

// 创建 SDK
GraphQuerySDK sdk = new GraphQuerySDK(parser, rewriter, executor, stitcher, sorter, deduplicator);

// 执行查询
String result = sdk.executeRaw("MATCH (n:NetworkElement) RETURN n");

// 结果为 JSON 格式
```

## Cypher 语法扩展

本 SDK 基于 OpenCypher 标准，扩展了以下 TuGraph 特有的语法：

### USING SNAPSHOT - 时间快照查询

用于指定外部数据源的时间条件：

```cypher
USING SNAPSHOT('latest', 1) ON ['Card'] 
MATCH path = (p:Person)-[r:BORN_IN]->(c:Card) 
RETURN path
```

**语法说明**：
- `SNAPSHOT('latest', 1)`: 指定快照版本，`latest` 表示最新版本，`1` 表示版本号
- `ON ['Card']`: 指定应用快照的标签列表

### PROJECT BY - 字段投影

用于指定返回字段的投影规则：

```cypher
MATCH path = (p:Person)-[r:BORN_IN]->(c:Card) 
RETURN path PROJECT BY {'Card': ['name', 'id']}
```

**语法说明**：
- `PROJECT BY {'Label': ['field1', 'field2']}`: 指定每个标签需要返回的字段列表
- 仅对指定标签的节点进行字段过滤

### 多关系类型匹配

支持在单条边上匹配多种关系类型：

```cypher
MATCH (ne:NetworkElement)-[r:NEHasLtps|NEHasAlarms|NEHasKPI]->(target)
RETURN ne, target
```

**语法说明**：
- 使用 `|` 分隔多个关系类型
- 系统会自动分离物理边和虚拟边的查询

## 支持的查询场景

### 1. 多关系类型混合查询

```cypher
MATCH (ne:NetworkElement {name: 'NE001'})-[r:NEHasLtps|NEHasAlarms|NEHasKPI]->(target) 
RETURN ne, target
```

返回格式：
```json
[
  {"ne": {"id": "ne1", "label": "NetworkElement"}, "target": {"id": "ltp1", "label": "LTP"}},
  {"ne": {"id": "ne1", "label": "NetworkElement"}, "target": {"id": "kpi1", "label": "KPI"}},
  {"ne": {"id": "ne1", "label": "NetworkElement"}, "target": {"id": "alarm1", "label": "Alarm"}}
]
```

### 2. UNION + Path 返回

```cypher
MATCH p=(ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2]->(target) RETURN p 
UNION 
MATCH p=(ne:NetworkElement {name: 'NE001'})-[:NEHasLtps|NEHasKPI]->(target) RETURN p
```

返回格式：
```json
{
  "paths": [
    [
      {"type": "node", "label": "NetworkElement", "props": {"name": "NE001"}},
      {"type": "edge", "label": "NEHasLtps", "props": {}},
      {"type": "node", "label": "LTP", "props": {"name": "LTP1"}},
      {"type": "edge", "label": "LTPHasKPI2", "props": {}},
      {"type": "node", "label": "KPI", "props": {"name": "cpu_usage"}}
    ]
  ]
}
```

### 3. 多跳路径查询

```cypher
MATCH path = (p:Person)-[r1:HAS_CHILD]->(p1)-[r2:HAS_CHILD]->(p2)-[r3:BORN_IN]->(c:Card) 
RETURN path
```

### 4. 纯外部数据源查询

```cypher
MATCH (card:Card {name: 'card001'}) WHERE card.resId='2131221' 
RETURN card
```

### 5. 外部到内部关联查询

```cypher
MATCH (card:Card {name: 'card001'})-[r1:CardLocateInNe]->(ne) WHERE card.resId='2131221' 
RETURN ne
```

## 项目结构

```
src/main/java/com/federatedquery/
├── adapter/          # 数据源适配器
├── aggregator/       # 结果聚合
├── ast/              # AST 模型
├── executor/         # 执行器
├── grammar/          # ANTLR4 语法
├── metadata/         # 元数据
├── parser/           # 解析器
├── plan/             # 执行计划
├── rewriter/         # 查询重写
└── sdk/              # SDK 主入口
```

## 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 运行时 |
| Spring Framework | 6.1.6 | 依赖注入 |
| ANTLR4 | 4.13.1 | Cypher 解析 |
| Caffeine | 3.1.8 | 计划缓存 |
| Jackson | 2.17.0 | JSON 序列化 |
| JUnit 5 | 5.10.2 | 单元测试 |

## 核心约束

1. **边界约束**: 虚拟边/节点仅允许出现在图遍历路径的第一跳或最后一跳
2. **读写分离**: 仅支持只读查询 (MATCH, RETURN, WITH 等)
3. **批量请求**: 防止 N+1 查询风暴
4. **计划缓存**: Caffeine 缓存执行计划

## 文档

- [SPEC.md](docs/SPEC.md) - SDK 规范与约束文档

## 构建

```bash
# 编译
mvn compile

# 运行测试
mvn test

# 打包
mvn package
```

## 测试

项目包含 37 个单元测试和端到端测试：

```bash
mvn test
```

| 测试类 | 测试数 |
|--------|--------|
| ParserTest | 10 |
| RewriterTest | 5 |
| ExecutorTest | 5 |
| AggregatorTest | 7 |
| E2ETest | 10 |

## 许可证

MIT License
