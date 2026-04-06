# 图联邦查询引擎 - 实现状态报告

## 项目概述

图联邦查询引擎 SDK，将 Cypher 查询分解为物理图查询和外部数据源查询，在内存中聚合异构数据源的结果，以统一图结构返回。

## 实现状态：✅ 已完成

### 核心功能

| 功能模块 | 状态 | 说明 |
|---------|------|------|
| ANTLR4 解析器 | ✅ 完成 | 支持 TuGraph 扩展语法 |
| AST 模型 | ✅ 完成 | 完整的 Cypher AST 表示 |
| 元数据注册 | ✅ 完成 | Caffeine 缓存支持 |
| 虚拟边检测 | ✅ 完成 | 边界约束验证 |
| 查询重写 | ✅ 完成 | 物理查询与外部查询分离 |
| 联邦执行器 | ✅ 完成 | 并行执行支持 |
| 结果聚合 | ✅ 完成 | Path 拼接、排序、分页 |
| SDK 主接口 | ✅ 完成 | execute(cypher) → JSON |

### 项目结构

```
src/main/java/com/federatedquery/
├── adapter/                    # 数据源适配器
│   ├── DataSourceAdapter.java  # 适配器接口
│   ├── TuGraphAdapter.java     # TuGraph 适配器
│   ├── QueryResult.java        # 查询结果
│   └── GraphEntity.java        # 图实体
├── aggregator/                 # 结果聚合
│   ├── ResultStitcher.java     # 结果拼接
│   ├── PathBuilder.java        # Path 构建
│   ├── GlobalSorter.java       # 全局排序
│   └── UnionDeduplicator.java  # UNION 去重
├── ast/                        # AST 模型
│   ├── AstNode.java           # AST 节点接口
│   ├── Program.java           # 程序
│   ├── Statement.java         # 语句
│   ├── MatchClause.java       # MATCH 子句
│   ├── ReturnClause.java      # RETURN 子句
│   ├── Pattern.java           # 模式
│   └── ...
├── executor/                   # 执行器
│   ├── FederatedExecutor.java # 联邦执行器
│   ├── ExecutionResult.java   # 执行结果
│   └── BatchingStrategy.java  # 批量策略
├── grammar/                    # ANTLR4 语法
│   └── Lcypher.g4             # Cypher 语法文件
├── metadata/                   # 元数据
│   ├── MetadataRegistry.java  # 元数据注册接口
│   ├── MetadataRegistryImpl.java
│   ├── VirtualEdgeBinding.java
│   ├── DataSourceMetadata.java
│   └── LabelMetadata.java
├── parser/                     # 解析器
│   ├── CypherParserFacade.java
│   ├── CypherASTVisitor.java
│   └── SyntaxErrorException.java
├── plan/                       # 执行计划
│   ├── ExecutionPlan.java
│   ├── PhysicalQuery.java
│   ├── ExternalQuery.java
│   └── GlobalContext.java
├── rewriter/                   # 查询重写
│   ├── QueryRewriter.java
│   ├── VirtualEdgeDetector.java
│   └── VirtualEdgeConstraintException.java
├── sdk/                        # SDK
│   └── GraphQuerySDK.java     # 主入口
└── GraphQueryEngineConfiguration.java

src/test/java/com/federatedquery/
├── adapter/
│   └── MockExternalAdapter.java # Mock 适配器（测试专用）
├── parser/
│   └── ParserTest.java
├── rewriter/
│   └── RewriterTest.java
├── executor/
│   └── ExecutorTest.java
├── aggregator/
│   └── AggregatorTest.java
└── e2e/
    └── E2ETest.java            # 端到端测试
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
| Mockito | 5.11.0 | Mock 测试 |

## 测试覆盖

| 测试类 | 测试数 | 状态 |
|--------|--------|------|
| ParserTest | 10 | ✅ |
| RewriterTest | 5 | ✅ |
| ExecutorTest | 5 | ✅ |
| AggregatorTest | 7 | ✅ |
| E2ETest | 10 | ✅ |
| **总计** | **37** | **✅ 全部通过** |

## 支持的查询场景

### 示例1: 多关系类型混合查询
```cypher
MATCH (ne:NetworkElement {name: 'NE001'})-[r:NEHasLtps|NEHasAlarms|NEHasKPI]->(target) 
RETURN ne, target
```

### 示例2: UNION + Path 返回
```cypher
MATCH p=(ne:NetworkElement {name: 'NE001'})-[:NEHasLtps]->(ltp)-[:LTPHasKPI2]->(target) RETURN p 
UNION 
MATCH p=(ne:NetworkElement {name: 'NE001'})-[:NEHasLtps|NEHasKPI]->(target) RETURN p
```

### 示例3: 多跳路径查询
```cypher
MATCH path = (p:Person)-[r1:HAS_CHILD]->(p1)-[r2:HAS_CHILD]->(p2)-[r3:BORN_IN]->(c:Card) 
RETURN path
```

### 示例4: 纯外部数据源查询
```cypher
MATCH (card:Card {name: 'card001'}) WHERE card.resId='2131221' 
RETURN card
```

### 示例5: 外部到内部关联查询
```cypher
MATCH (card:Card {name: 'card001'})-[r1:CardLocateInNe]->(ne) WHERE card.resId='2131221' 
RETURN ne
```

## 返回格式

### 节点返回格式
```json
[
  {
    "ne": {"id": "ne1", "label": "NetworkElement", "name": "NE001"},
    "target": {"id": "kpi1", "label": "KPI", "name": "cpu_usage"}
  }
]
```

### Path 返回格式
```json
{
  "paths": [
    [
      {"type": "node", "label": "NetworkElement", "props": {"name": "NE001"}},
      {"type": "edge", "label": "NEHasLtps", "props": {}},
      {"type": "node", "label": "LTP", "props": {"name": "LTP1"}}
    ]
  ]
}
```

## 核心约束

1. **边界约束**: 虚拟边/节点仅允许出现在图遍历路径的第一跳或最后一跳
2. **读写分离**: 仅支持只读查询 (MATCH, RETURN, WITH 等)
3. **批量请求**: 防止 N+1 查询风暴
4. **计划缓存**: Caffeine 缓存执行计划

## 快速开始

```java
// 创建 SDK
GraphQuerySDK sdk = new GraphQuerySDK(parser, rewriter, executor, stitcher, sorter, deduplicator);

// 执行查询
String result = sdk.executeRaw("MATCH (n:NetworkElement) RETURN n");

// 结果为 JSON 格式
```

## 构建命令

```bash
# 编译
mvn compile

# 运行测试
mvn test

# 打包
mvn package
```
