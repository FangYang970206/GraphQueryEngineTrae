# 图联邦查询引擎 - 详细实现计划

## TL;DR

> **Quick Summary**: 构建一个 Java SDK，将 Cypher 查询分解为物理图查询和外部数据源查询，在内存中聚合异构数据源的结果，以统一图结构返回。
>
> **Deliverables**:
> - ANTLR4 解析器 + Cypher AST
> - 查询重写器（虚拟边识别与剥离）
> - 联邦执行器（并行查询 + 熔断降级）
> - 结果聚合器（Path 拼接 + 排序分页）
> - SDK 主接口 `execute(cypher) → JSON`
>
> **Estimated Effort**: Large (50+ TODOs)
> **Parallel Execution**: YES - 5 waves
> **Critical Path**: Grammar → Parser → Rewriter → Executor → Aggregator → SDK

---

## Context

### Original Request
用户需要实现一个图联邦查询引擎，将包含虚拟边的 Cypher 查询转换为物理查询 + 外部 API 调用，在内存中聚合异构数据源结果。

### 核心 Pipeline
```
Cypher → Parser (ANTLR4) → AST → Rewriter (虚拟边剥离) → ExecutionPlan
    ↓
Federated Executor (并行执行)
    ├── TuGraph 查询 (Cypher)
    └── 外部数据源 (Adapter)
    ↓
Result Stitcher (Path拼接/排序/分页)
    ↓
JSON Response
```

### 架构决策
| 决策点 | 选择 |
|--------|------|
| 元数据管理 | API 注册 + Caffeine 缓存 |
| 外部接口 | 插件化 DataSourceAdapter |
| 算子映射 | 显式 VirtualEdgeBinding 配置 |
| SDK API | 简单 execute(cypher) → JSON |

---

## Work Objectives

### Core Objective
构建一个生产级的图联邦查询引擎 SDK，能够：
1. 解析 Cypher 查询（支持扩展语法）
2. 识别并剥离虚拟边/节点
3. 并行执行物理查询和外部 API
4. 聚合结果并返回统一 JSON

### Concrete Deliverables
- [ ] Maven 项目结构（Spring Framework，非 Spring Boot）
- [ ] ANTLR4 解析器（基于 Lcypher.g4）
- [ ] Cypher AST 模型类
- [ ] 元数据注册 API
- [ ] 虚拟边识别与查询重写
- [ ] 外部数据源适配器接口
- [ ] 联邦执行器（并行 + 熔断）
- [ ] 结果聚合器
- [ ] SDK 主接口
- [ ] 完整 UT（Mock 外部数据源）

### Definition of Done
- [ ] `execute("MATCH (n)-[r]->(m) RETURN n,r,m")` 返回 JSON
- [ ] 虚拟边 NEHasKPI 被正确剥离并调用外部 API
- [ ] UNION 结果正确去重
- [ ] 全局 ORDER BY + LIMIT 语义正确
- [ ] 熔断触发时返回警告而非异常

### Must Have
- [ ] 虚拟边首/末跳约束校验
- [ ] WHERE 条件安全下推
- [ ] 批量请求（N+1 防护）
- [ ] Plan Cache（Caffeine）
- [ ] 隐式 LIMIT 5000 保护

### Must NOT Have (Guardrails)
- [ ] 禁止 [物理]->[虚拟]->[物理] 夹心结构
- [ ] 禁止写操作（C精ang CREATE, MERGE, DELETE）
- [ ] 禁止跨数据源事务
- [ ] 不实现完整图计算引擎（仅查询）

---

## Verification Strategy

### Test Decision
- **Infrastructure exists**: YES (JUnit 5 + Mockito + MockWebServer)
- **Automated tests**: Tests-after (UT for each module)
- **Framework**: JUnit 5 + Mockito + okhttp3 MockWebServer

### QA Policy
每个任务包含 Agent-Executed QA Scenarios：
- 单元测试验证核心逻辑
- MockWebServer 模拟外部 API
- 集成测试验证完整流程

---

## Execution Strategy

### 并行执行 Waves

```
Wave 1 (Foundation - 依赖最少，可最大并行):
├── T1: Maven 项目结构 + Spring 依赖配置
├── T2: ANTLR4 语法编译 + 生成代码
├── T3: AST 模型类设计
├── T4: 元数据模型 (VirtualEdgeBinding, DataSourceMetadata)
└── T5: MetadataRegistry API + Caffeine 缓存

Wave 2 (Parser + Rewriter - 核心逻辑):
├── T6: CypherParser Facade (解析入口)
├── T7: CypherASTVisitor (AST 构建)
├── T8: VirtualEdgeDetector (虚拟边识别)
├── T9: QueryRewriter (查询重写)
└── T10: ExecutionPlan 模型

Wave 3 (Executor + Adapter):
├── T11: DataSourceAdapter 接口
├── T12: TuGraphAdapter (物理图查询)
├── T13: MockExternalAdapter (测试用)
├── T14: FederatedExecutor (并行执行)
└── T15: BatchingStrategy (N+1 防护)

Wave 4 (Aggregator + SDK):
├── T16: ResultStitcher (结果聚合)
├── T17: PathBuilder (Path 拼接)
├── T18: GlobalSorter (全局排序分页)
├── T19: UnionDeduplicator (UNION 去重)
└── T20: GraphQuerySDK (SDK 主接口)

Wave 5 (Reliability + Tests):
├── T21: CircuitBreaker 配置 (Resilience4j)
├── T22: OOMProtection (隐式 LIMIT)
├── T23: WhereConditionPushdown (WHERE 下推)
├── T24: Unit Tests - Parser 模块
├── T25: Unit Tests - Rewriter 模块
├── T26: Unit Tests - Executor 模块
└── T27: Unit Tests - Aggregator 模块

Wave FINAL (验证):
├── F1: Plan Compliance Audit
├── F2: Integration Test (5个示例场景)
└── F3: Scope Fidelity Check
```

### 依赖矩阵

| Task | Blocks | Blocked By |
|------|--------|------------|
| T1 | T2-T5 | - |
| T2 | T6-T7 | T1 |
| T3 | T6-T10 | T1 |
| T4 | T5, T8 | T1 |
| T5 | T8, T14-T15, T20 | T1, T4 |
| T6 | T7 | T2, T3 |
| T7 | T8-T10 | T2, T3, T6 |
| T8 | T9 | T5, T7 |
| T9 | T10 | T8 |
| T10 | T11-T20 | T7, T9 |
| T11 | T12-T13 | T10 |
| T12 | T14 | T10, T11 |
| T13 | T14 | T10, T11 |
| T14 | T15, T20 | T5, T12, T13 |
| T15 | T20 | T5, T14 |
| T16 | T17-T19 | T10 |
| T17 | T20 | T16 |
| T18 | T20 | T16 |
| T19 | T20 | T16 |
| T20 | T21-T27 | T5, T14-T15, T17-T19 |
| T21 | - | T14 |
| T22 | - | T14 |
| T23 | - | T14 |
| T24 | T27 | T20 |
| T25 | T27 | T20 |
| T26 | T27 | T20 |
| T27 | F1-F3 | T24-T26 |

---

## TODOs

> 每个任务包含：实现步骤、测试用例、推荐 Agent Profile、QA Scenarios

---

- [ ] 1. Maven 项目结构 + Spring 依赖配置

  **What to do**:
  - 创建 Maven 项目结构
  - pom.xml 配置：
    - spring-context, spring-web (非 spring-boot)
    - antlr4-runtime
    - antlr4-maven-plugin
    - resilience4j-all
    - caffeine
    - jackson
    - junit5, mockito, mockwebserver
  - 目录结构：
    - `src/main/java/com/federatedquery/` (主代码)
    - `src/main/antlr4/` (ANTLR 语法)
    - `src/main/resources/` (配置)
    - `src/test/java/` (测试)

  **Must NOT do**:
  - 禁止引入 spring-boot 依赖
  - 禁止引入其他图数据库驱动（仅 Bolt 协议）

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Justification**: 标准 Maven 项目配置，常规任务

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with T2-T5)
  - **Blocks**: T2-T5
  - **Blocked By**: None

  **References**:
  - Lcypher.g4: 现有语法文件
  - Spring Framework 6.x 文档

  **Acceptance Criteria**:
  - [ ] `mvn compile` 成功
  - [ ] ANTLR 插件正确配置
  - [ ] 无 spring-boot 依赖

  **QA Scenarios**:
  ```
  Scenario: Maven 编译验证
    Tool: Bash
    Steps:
      1. cd D:\AI_Project\GraphQueryEngine
      2. mvn compile -q
    Expected Result: BUILD SUCCESS，无错误
    Evidence: .sisyphus/evidence/task-1-maven-compile.log
  ```

---

- [ ] 2. ANTLR4 语法编译 + 代码生成

  **What to do**:
  - 将 Lcypher.g4 移入 src/main/antlr4/com/federatedquery/grammar/
  - 配置 antlr4-maven-plugin (visitor 模式)
  - 运行 mvn generate-sources
  - 验证生成文件：
    - LcypherLexer.java
    - LcypherParser.java
    - LcypherVisitor.java (带 #label)
    - LcypherBaseVisitor.java

  **Must NOT do**:
  - 禁止修改 grammar 文件（除非语法缺失）

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Justification**: 工具配置任务

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: T6, T7
  - **Blocked By**: T1

  **References**:
  - Lcypher.g4: 现有语法文件
  - ANTLR4 Maven Plugin 文档

  **Acceptance Criteria**:
  - [ ] 生成代码包含 oC_Match, oC_Return, oC_Pattern 等规则
  - [ ] Visitor 接口方法签名正确

  **QA Scenarios**:
  ```
  Scenario: ANTLR 代码生成验证
    Tool: Bash
    Steps:
      1. mvn generate-sources -q
      2. ls target/generated-sources/antlr4/com/federatedquery/grammar/
    Expected Result: 包含 LcypherParser.java, LcypherBaseVisitor.java
    Evidence: .sisyphus/evidence/task-2-antlr-gen.log
  ```

---

- [ ] 3. AST 模型类设计

  **What to do**:
  - 设计核心 AST 节点接口/类：
    ```
    AstNode (interface)
    ├── Program
    ├── Statement
    ├── MatchClause
    ├── ReturnClause
    ├── Pattern
    │   ├── NodePattern
    │   └── RelationshipPattern
    ├── WhereClause
    ├── OrderByClause
    ├── LimitClause
    ├── UnionClause
    ├── UsingSnapshot (扩展)
    └── ProjectBy (扩展)
    ```
  - Expression 体系：
    ```
    Expression
    ├── Literal
    ├── Variable
    ├── PropertyAccess
    ├── Comparison
    ├── And/Or/Not
    └── FunctionCall
    ```

  **Must NOT do**:
  - 禁止实现求值逻辑（仅表示）

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Justification**: 需要设计良好的类型层次

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: T6, T7
  - **Blocked By**: T1

  **References**:
  - cytosm/cytosm: Cypher AST 模型参考
  - Neo4j cypher-dsl: AST 接口设计

  **Acceptance Criteria**:
  - [ ] 所有语法规则有对应 AST 类
  - [ ] 支持 Visitor 遍历

  **QA Scenarios**:
  ```
  Scenario: AST 模型编译验证
    Tool: Bash
    Steps:
      1. mvn compile -q
    Expected Result: BUILD SUCCESS
    Evidence: .sisyphus/evidence/task-3-ast-compile.log
  ```

---

- [ ] 4. 元数据模型设计

  **What to do**:
  - 设计元数据模型：
    ```java
    // 虚拟边绑定配置
    VirtualEdgeBinding {
        String edgeType;           // NEHasKPI
        String targetDataSource;   // kpi-service
        String operatorName;       // getKPIByNeIds
        Map<String, String> idMapping;  // neId → resId
        List<String> outputFields; // 返回字段
    }
    
    // 数据源元数据
    DataSourceMetadata {
        String name;               // kpi-service
        DataSourceType type;       // REST_API, GRPC, TUGRAH_BOLT
        String endpoint;           // http://...
        Map<String, Object> config;
    }
    
    // 节点标签元数据
    LabelMetadata {
        String label;              // Card
        boolean isVirtual;        // true
        String dataSource;         // card-service
        List<String> propertyMapping;
    }
    ```

  **Must NOT do**:
  - 禁止硬编码任何具体数据源

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Justification**: 核心数据结构设计

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: T5, T8
  - **Blocked By**: T1

  **References**:
  - Apollo Federation: 元数据设计参考

  **Acceptance Criteria**:
  - [ ] 支持虚拟边、数据源、标签元数据
  - [ ] 支持 JSON 序列化

  **QA Scenarios**:
  ```
  Scenario: 元数据模型编译
    Tool: Bash
    Steps:
      1. mvn compile -q
    Expected Result: BUILD SUCCESS
    Evidence: .sisyphus/evidence/task-4-metadata-compile.log
  ```

---

- [ ] 5. MetadataRegistry API + Caffeine 缓存

  **What to do**:
  - 设计 MetadataRegistry 接口：
    ```java
    public interface MetadataRegistry {
        void registerDataSource(DataSourceMetadata metadata);
        void registerVirtualEdge(VirtualEdgeBinding binding);
        void registerLabel(LabelMetadata label);
        
        Optional<DataSourceMetadata> getDataSource(String name);
        Optional<VirtualEdgeBinding> getVirtualEdgeBinding(String edgeType);
        Optional<LabelMetadata> getLabel(String label);
        
        boolean isVirtualEdge(String edgeType);
        boolean isVirtualLabel(String label);
        String getDataSourceForEdge(String edgeType);
    }
    ```
  - 实现类使用 Caffeine Cache
  - 提供 @Bean 配置

  **Must NOT do**:
  - 禁止单例模式（Spring Bean 管理）

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Justification**: 核心 API 设计

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1
  - **Blocks**: T8, T14-T15, T20
  - **Blocked By**: T1, T4

  **References**:
  - Caffeine Cache 文档
  - Spring Cache 集成

  **Acceptance Criteria**:
  - [ ] 注册/查询 API 正常工作
  - [ ] Caffeine 缓存生效

  **QA Scenarios**:
  ```
  Scenario: MetadataRegistry 注册与查询
    Tool: Bash (JUnit)
    Preconditions: Maven 项目已编译
    Steps:
      1. 运行 MetadataRegistryTest
      2. assert register → get 正常工作
      3. assert isVirtualEdge() 正确判断
    Expected Result: 所有测试通过
    Evidence: .sisyphus/evidence/task-5-registry-test.log
  ```

---

- [ ] 6. CypherParser Facade (解析入口)

  **What to do**:
  - 设计 ParserFacade：
    ```java
    public class CypherParserFacade {
        private final LcypherLexer lexer;
        private final LcypherParser parser;
        private final Cache<String, Program> planCache;
        
        public Program parse(String cypher) { ... }
        public Program parseCached(String cypher) { ... } // 带缓存
    }
    ```
  - 错误处理：SyntaxErrorException
  - 缓存 key：MD5(cypher)

  **Must NOT do**:
  - 禁止返回 ParseTree（必须转换为 AST）

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Justification**: 封装任务

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: T7
  - **Blocked By**: T2, T3

  **References**:
  - ANTLR4 最佳实践

  **Acceptance Criteria**:
  - [ ] 简单查询解析成功
  - [ ] 语法错误抛出异常

  **QA Scenarios**:
  ```
  Scenario: 简单 Cypher 解析
    Tool: Bash (JUnit)
    Steps:
      1. parse("MATCH (n) RETURN n")
      2. assert result instanceof Program
    Expected Result: 解析成功
    Evidence: .sisyphus/evidence/task-6-parse-test.log
  ```

---

- [ ] 7. CypherASTVisitor (AST 构建)

  **What to do**:
  - 实现 CypherASTVisitor 继承 LcypherBaseVisitor<AstNode>
  - 核心方法：
    - visitOC_Cypher → Program
    - visitOC_Match → MatchClause
    - visitOC_Return → ReturnClause
    - visitOC_Pattern → Pattern
    - visitOC_NodePattern → NodePattern
    - visitOC_RelationshipPattern → RelationshipPattern
    - visitOC_Where → WhereClause
    - visitOC_Expression → Expression
  - 处理 USING SNAPSHOT, PROJECT BY 扩展语法

  **Must NOT do**:
  - 禁止语义分析（仅语法树转换）

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Justification**: 核心解析逻辑

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: T8-T10
  - **Blocked By**: T2, T3, T6

  **References**:
  - Lcypher.g4 语法文件
  - ANTLR4 Visitor 模式

  **Acceptance Criteria**:
  - [ ] 5个示例查询全部解析成功
  - [ ] AST 结构正确

  **QA Scenarios**:
  ```
  Scenario: 示例1解析
    Tool: Bash (JUnit)
    Steps:
      1. parse("MATCH (ne:NetworkElement {name: 'NE001'})-[r:NEHasLtps|NEHasAlarms|NEHasKPI]->(target) return ne,target;")
      2. assert 解析成功
      3. assert 3个关系类型
    Expected Result: 解析成功，3个关系
    Evidence: .sisyphus/evidence/task-7-parse-sample1.log
  
  Scenario: 扩展语法解析
    Tool: Bash (JUnit)
    Steps:
      1. parse("USING SNAPSHOT('latest', 1) ON ['Card'] MATCH (c:Card) RETURN c")
    Expected Result: UsingSnapshot 节点存在
    Evidence: .sisyphus/evidence/task-7-parse-snapshot.log
  ```

---

- [ ] 8. VirtualEdgeDetector (虚拟边识别)

  **What to do**:
  - 设计 VirtualEdgeDetector：
    ```java
    public class VirtualEdgeDetector {
        private final MetadataRegistry registry;
        
        public DetectionResult detect(Pattern pattern) {
            // 遍历 Pattern，识别虚拟边
            // 检查边界约束：仅首/末跳
            // 返回 PhysicalParts 和 VirtualParts
        }
        
        public void validateNoSandwich(List<PatternPart> parts) {
            // 验证无 [物理]->[虚拟]->[物理] 夹心
        }
    }
    ```
  - 返回结构：
    ```java
    public class DetectionResult {
        List<PatternPart> physicalParts;
        List<VirtualEdgePart> virtualParts;
        List<String> errors; // 约束违反
    }
    ```

  **Must NOT do**:
  - 禁止修改 Pattern（仅分析）

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Justification**: 核心业务逻辑

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: T9
  - **Blocked By**: T5, T7

  **References**:
  - 设计文档边界约束

  **Acceptance Criteria**:
  - [ ] 正确识别 NEHasKPI/NEHasAlarms 为虚拟边
  - [ ] 检测夹心结构并报错

  **QA Scenarios**:
  ```
  Scenario: 虚拟边识别
    Tool: Bash (JUnit)
    Steps:
      1. 构造 Pattern: (ne)-[NEHasLtps|NEHasKPI]->(target)
      2. detect()
      3. assert physicalParts = [NEHasLtps]
      4. assert virtualParts = [NEHasKPI]
    Expected Result: 正确分离
    Evidence: .sisyphus/evidence/task-8-detect-test.log
  
  Scenario: 夹心结构检测
    Tool: Bash (JUnit)
    Steps:
      1. 构造 Pattern: (a)-[物理]->(b)-[虚拟]->(c)-[物理]->(d)
      2. detect()
    Expected Result: 抛出 VirtualEdgeConstraintException
    Evidence: .sisyphus/evidence/task-8-sandwich-test.log
  ```

---

- [ ] 9. QueryRewriter (查询重写)

  **What to do**:
  - 设计 QueryRewriter：
    ```java
    public class QueryRewriter {
        private final MetadataRegistry registry;
        private final VirtualEdgeDetector detector;
        
        public ExecutionPlan rewrite(Program program) {
            // 1. 分析 Program 结构
            // 2. 检测虚拟边
            // 3. 剥离虚拟边，生成子查询
            // 4. 构建执行计划
        }
    }
    ```
  - 执行计划结构：
    ```java
    public class ExecutionPlan {
        List<PhysicalQuery> physicalQueries;  // 物理 Cypher
        List<ExternalQuery> externalQueries; // 外部 API
        List<UnionPart> unionParts;          // UNION 分支
        GlobalContext globalContext;           // 全局变量
    }
    ```

  **Must NOT do**:
  - 禁止执行查询（仅生成计划）

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Justification**: 核心重写逻辑

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: T10
  - **Blocked By**: T8

  **References**:
  - cytosm/cytosm: Query rewriting 参考

  **Acceptance Criteria**:
  - [ ] 示例1重写正确（分离3个查询）
  - [ ] 示例2重写正确（Path + UNION）

  **QA Scenarios**:
  ```
  Scenario: 示例1重写
    Tool: Bash (JUnit)
    Steps:
      1. rewrite("MATCH (ne)-[NEHasLtps|NEHasKPI]->(t) RETURN ne,t")
      2. assert physicalQueries.size() = 1
      3. assert externalQueries.size() = 1
      4. assert physicalQueries[0].cypher 包含 NEHasLtps
    Expected Result: 重写正确
    Evidence: .sisyphus/evidence/task-9-rewrite-sample1.log
  
  Scenario: 示例2重写 (UNION)
    Tool: Bash (JUnit)
    Steps:
      1. rewrite(示例2的完整查询)
      2. assert unionParts.size() > 0
    Expected Result: UNION 分支正确拆分
    Evidence: .sisyphus/evidence/task-9-rewrite-sample2.log
  ```

---

- [ ] 10. ExecutionPlan 模型

  **What to do**:
  - 完善执行计划模型：
    ```java
    // 物理查询
    public class PhysicalQuery {
        String id;
        String cypher;
        Map<String, Object> parameters;
        List<String> outputVariables;
    }
    
    // 外部查询
    public class ExternalQuery {
        String id;
        String dataSource;
        String operator;
        Map<String, Object> inputMapping;  // 变量 → 参数
        List<String> outputVariables;
    }
    
    // 全局上下文（用于结果关联）
    public class GlobalContext {
        Map<String, Object> bindings;  // 变量绑定
        List<WhereCondition> pendingFilters;  // 待下推条件
        OrderSpec globalOrder;
        LimitSpec globalLimit;
    }
    ```

  **Must NOT do**:
  - 禁止实现执行逻辑

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Justification**: 数据模型

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: T11-T20
  - **Blocked By**: T7, T9

  **References**:
  - Query Plan 结构设计

  **Acceptance Criteria**:
  - [ ] 支持物理查询、外部查询、UNION
  - [ ] 支持全局上下文传递

  **QA Scenarios**:
  ```
  Scenario: ExecutionPlan 结构验证
    Tool: Bash (JUnit)
    Steps:
      1. rewrite(示例查询)
      2. assert ExecutionPlan 包含所有子查询
    Expected Result: 结构完整
    Evidence: .sisyphus/evidence/task-10-plan-test.log
  ```

---

- [ ] 11. DataSourceAdapter 接口

  **What to do**:
  - 设计适配器接口：
    ```java
    public interface DataSourceAdapter {
        String getDataSourceType();  // REST_API, GRPC, TUGRAH
        
        CompletableFuture<QueryResult> execute(ExternalQuery query);
        
        QueryResult executeSync(ExternalQuery query);
        
        boolean isHealthy();
    }
    
    public class QueryResult {
        boolean success;
        Object data;
        List<GraphEntity> entities;
        String error;
        Map<String, String> warnings;
    }
    
    public class GraphEntity {
        String id;
        String type;  // node 或 edge
        Map<String, Object> properties;
    }
    ```

  **Must NOT do**:
  - 禁止硬编码实现

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Justification**: 接口定义

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: T12, T13
  - **Blocked By**: T10

  **References**:
  - 插件模式参考

  **Acceptance Criteria**:
  - [ ] 接口定义完整
  - [ ] 支持同步/异步

  **QA Scenarios**:
  ```
  Scenario: 接口编译验证
    Tool: Bash
    Steps:
      1. mvn compile -q
    Expected Result: BUILD SUCCESS
    Evidence: .sisyphus/evidence/task-11-interface-compile.log
  ```

---

- [ ] 12. TuGraphAdapter (物理图查询)

  **What to do**:
  - 实现 TuGraph 适配器：
    ```java
    @Component
    public class TuGraphAdapter implements DataSourceAdapter {
        private final WebClient webClient;  // Bolt over HTTP
        
        @Override
        public String getDataSourceType() {
            return "TUGRAPH";
        }
        
        @Override
        public CompletableFuture<QueryResult> execute(ExternalQuery query) {
            // 发送 Cypher 到 TuGraph
            // 解析结果为 GraphEntity 列表
        }
    }
    ```
  - 配置：endpoint, auth token

  **Must NOT do**:
  - 禁止实现完整 Bolt 协议

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Justification**: 适配器实现

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: T14
  - **Blocked By**: T10, T11

  **References**:
  - TuGraph REST API 文档

  **Acceptance Criteria**:
  - [ ] 发送 Cypher 并解析结果
  - [ ] 支持超时配置

  **QA Scenarios**:
  ```
  Scenario: TuGraphAdapter Mock 测试
    Tool: Bash (JUnit + MockWebServer)
    Preconditions: MockWebServer 配置
    Steps:
      1. 启动 MockWebServer 返回示例数据
      2. execute(PhysicalQuery)
      3. assert result.entities.size() > 0
    Expected Result: 结果正确解析
    Evidence: .sisyphus/evidence/task-12-tugraph-mock.log
  ```

---

- [ ] 13. MockExternalAdapter (测试用)

  **What to do**:
  - 实现模拟外部适配器：
    ```java
    public class MockExternalAdapter implements DataSourceAdapter {
        private final Map<String, MockResponse> responses = new HashMap<>();
        
        public void registerResponse(String operator, MockResponse response) {
            responses.put(operator, response);
        }
        
        @Override
        public CompletableFuture<QueryResult> execute(ExternalQuery query) {
            MockResponse mock = responses.get(query.operator);
            return CompletableFuture.completedFuture(mock.execute());
        }
    }
    
    public class MockResponse {
        List<GraphEntity> entities;
        int delayMs = 0;
        
        public QueryResult execute() { ... }
    }
    ```

  **Must NOT do**:
  - 禁止用于生产环境

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Justification**: 测试工具

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: T14
  - **Blocked By**: T10, T11

  **References**:
  - Mock 模式

  **Acceptance Criteria**:
  - [ ] 支持延迟模拟
  - [ ] 支持错误模拟

  **QA Scenarios**:
  ```
  Scenario: MockAdapter 延迟测试
    Tool: Bash (JUnit)
    Steps:
      1. registerResponse("getKPI", MockResponse.withDelay(100))
      2. measure execute() 时间
    Expected Result: 延迟约 100ms
    Evidence: .sisyphus/evidence/task-13-mock-delay.log
  ```

---

- [ ] 14. FederatedExecutor (并行执行)

  **What to do**:
  - 实现联邦执行器：
    ```java
    @Service
    public class FederatedExecutor {
        private final Map<String, DataSourceAdapter> adapters;
        private final ExecutorService executor;
        private final CircuitBreakerRegistry circuitBreakers;
        
        public CompletableFuture<ExecutionResult> execute(ExecutionPlan plan) {
            // 1. 并行执行所有物理查询
            // 2. 并行执行所有外部查询
            // 3. 处理 UNION 分支
            // 4. 收集结果
        }
        
        private CompletableFuture<QueryResult> executePhysical(PhysicalQuery query) {
            // 使用 TuGraphAdapter
        }
        
        private CompletableFuture<QueryResult> executeExternal(ExternalQuery query) {
            // 使用对应 DataSourceAdapter
            // 熔断保护
        }
    }
    ```
  - Resilience4j 熔断配置

  **Must NOT do**:
  - 禁止串行执行独立查询

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Justification**: 核心执行逻辑

  **Parallelization**:
  - **Can Run In Parallel**: NO (关键路径)
  - **Blocks**: T15, T20, T21, T22, T23
  - **Blocked By**: T5, T12, T13

  **References**:
  - Resilience4j 文档
  - ExecutorService 并行模式

  **Acceptance Criteria**:
  - [ ] 并行执行多个查询
  - [ ] 熔断器生效
  - [ ] 超时保护

  **QA Scenarios**:
  ```
  Scenario: 并行执行验证
    Tool: Bash (JUnit)
    Preconditions: MockAdapter 延迟 100ms
    Steps:
      1. execute(包含3个独立查询的计划)
      2. measure 总时间
    Expected Result: 总时间约 100-150ms（并行），非 300ms（串行）
    Evidence: .sisyphus/evidence/task-14-parallel-test.log
  
  Scenario: 熔断触发
    Tool: Bash (JUnit + MockWebServer)
    Steps:
      1. MockWebServer 返回 500 错误
      2. 连续调用 50 次
      3. assert 熔断器打开
    Expected Result: CircuitBreaker 状态 = OPEN
    Evidence: .sisyphus/evidence/task-14-circuit-test.log
  ```

---

- [ ] 15. BatchingStrategy (N+1 防护)

  **What to do**:
  - 实现批量请求策略：
    ```java
    public class BatchingStrategy {
        public BatchRequest batch(List<ExternalQuery> queries) {
            // 按 dataSource + operator 分组
            // 提取 ID 集合
            // 生成批量请求
        }
        
        public List<ExternalQuery> unbatch(BatchResponse response) {
            // 将批量响应拆分回各查询结果
        }
    }
    ```
  - 示例：
    ```
    输入: [Query(kpi, neId=1), Query(kpi, neId=2), Query(kpi, neId=3)]
    输出: BatchRequest(ids=[1,2,3])
    
    响应拆分回3个独立结果
    ```

  **Must NOT do**:
  - 禁止逐个请求

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Justification**: 优化策略

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3
  - **Blocks**: T20
  - **Blocked By**: T5, T14

  **References**:
  - Batching 模式

  **Acceptance Criteria**:
  - [ ] 正确分组和拆分
  - [ ] 1000 个 ID 一次请求

  **QA Scenarios**:
  ```
  Scenario: 批量分组
    Tool: Bash (JUnit)
    Steps:
      1. 构造1000个查询（相同 dataSource/operator，不同 ID）
      2. batch()
      3. assert 1个批量请求
    Expected Result: 分组正确
    Evidence: .sisyphus/evidence/task-15-batch-test.log
  ```

---

- [ ] 16. ResultStitcher (结果聚合)

  **What to do**:
  - 实现结果聚合器：
    ```java
    public class ResultStitcher {
        public StitchedResult stitch(ExecutionResult executionResult) {
            // 1. 关联物理结果和外部结果
            // 2. 合并实体
            // 3. 处理全局上下文
        }
        
        private Map<String, Object> buildEntityMapping(ExecutionResult result) {
            // 构建 ID → Entity 映射
        }
    }
    ```
  - 处理变量绑定传递

  **Must NOT do**:
  - 禁止丢失数据

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Justification**: 核心聚合逻辑

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Blocks**: T17-T19
  - **Blocked By**: T10

  **References**:
  - Entity Key Stitching 模式

  **Acceptance Criteria**:
  - [ ] 物理和外部结果正确关联
  - [ ] 无数据丢失

  **QA Scenarios**:
  ```
  Scenario: 结果关联
    Tool: Bash (JUnit)
    Preconditions: 物理查询返回 ne，外部返回 kpi
    Steps:
      1. stitch([ne_result, kpi_result])
      2. assert ne.kpi 包含对应 KPI
    Expected Result: 关联正确
    Evidence: .sisyphus/evidence/task-16-stitch-test.log
  ```

---

- [ ] 17. PathBuilder (Path 拼接)

  **What to do**:
  - 实现 Path 拼接：
    ```java
    public class PathBuilder {
        public List<Path> buildPaths(List<QueryResult> results, Pattern pattern) {
            // 1. 识别 Path 变量
            // 2. 按顺序拼接节点和边
            // 3. 处理 UNION 的 Path 合并
        }
        
        public Path buildSinglePath(List<GraphEntity> entities) {
            // 从节点-边-节点序列构建 Path
        }
    }
    
    public class Path {
        List<PathElement> elements;  // Node → Edge → Node → ...
        Map<String, Object> properties;
    }
    ```

  **Must NOT do**:
  - 禁止乱序拼接

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Justification**: Path 语义处理

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4
  - **Blocks**: T20
  - **Blocked By**: T16

  **References**:
  - Neo4j Path 格式

  **Acceptance Criteria**:
  - [ ] 示例2 Path 正确拼接
  - [ ] UNION Path 合并

  **QA Scenarios**:
  ```
  Scenario: 示例2 Path 拼接
    Tool: Bash (JUnit)
    Steps:
      1. stitch(示例2结果)
      2. assert Paths 包含完整 ne→ltp→kpi2
    Expected Result: Path 完整
    Evidence: .sisyphus/evidence/task-17-path-test.log
  ```

---

- [ ] 18. GlobalSorter (全局排序分页)

  **What to do**:
  - 实现全局排序分页：
    ```java
    public class GlobalSorter {
        public PagedResult sortAndPaginate(
            List<Path> paths,
            OrderSpec order,
            LimitSpec limit
        ) {
            // 1. 提取所有结果（带 sort key）
            // 2. 全局排序
            // 3. 应用 LIMIT + SKIP
            // 4. 返回分页结果
        }
        
        public List<Path> extractSortable(List<Path> paths, OrderSpec order) {
            // 提取可排序字段
        }
    }
    ```

  **Must NOT do**:
  - 禁止在子查询层排序

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Justification**: 排序逻辑

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4
  - **Blocks**: T20
  - **Blocked By**: T16

  **References**:
  - Top-k 排序算法

  **Acceptance Criteria**:
  - [ ] 全局 ORDER BY 正确
  - [ ] LIMIT/SKIP 正确

  **QA Scenarios**:
  ```
  Scenario: 全局排序
    Tool: Bash (JUnit)
    Preconditions: 3个子查询各返回10条
    Steps:
      1. sortAndPaginate(all_results, ORDER BY ne.name, LIMIT 5)
      2. assert 结果按 name 排序
      3. assert size = 5
    Expected Result: 全局排序 + 分页正确
    Evidence: .sisyphus/evidence/task-18-sort-test.log
  ```

---

- [ ] 19. UnionDeduplicator (UNION 去重)

  **What to do**:
  - 实现 UNION 去重：
    ```java
    public class UnionDeduplicator {
        public List<Path> deduplicate(List<Path> paths) {
            // 1. 计算每个 Path 的 Hash
            // 2. 使用 LinkedHashSet 去重（保持顺序）
            // 3. 返回去重后结果
        }
        
        public String computePathHash(Path path) {
            // 节点 ID + 边 ID + 顺序
        }
    }
    ```

  **Must NOT do**:
  - 禁止依赖数据库去重

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Justification**: 去重逻辑

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4
  - **Blocks**: T20
  - **Blocked By**: T16

  **References**:
  - Hash 去重算法

  **Acceptance Criteria**:
  - [ ] UNION DISTINCT 去重正确
  - [ ] UNION ALL 保留原样

  **QA Scenarios**:
  ```
  Scenario: UNION 去重
    Tool: Bash (JUnit)
    Preconditions: 3条 Path，其中2条相同
    Steps:
      1. deduplicate(paths, distinct=true)
      2. assert size = 2
    Expected Result: 去重正确
    Evidence: .sisyphus/evidence/task-19-dedup-test.log
  
  Scenario: UNION ALL 保留
    Tool: Bash (JUnit)
    Steps:
      1. deduplicate(paths, distinct=false)
      2. assert size = 3
    Expected Result: 全部保留
    Evidence: .sisyphus/evidence/task-19-union-all.log
  ```

---

- [ ] 20. GraphQuerySDK (SDK 主接口)

  **What to do**:
  - 实现 SDK 主接口：
    ```java
    @Component
    public class GraphQuerySDK {
        private final CypherParserFacade parser;
        private final QueryRewriter rewriter;
        private final FederatedExecutor executor;
        private final ResultStitcher stitcher;
        private final GlobalSorter sorter;
        
        public String execute(String cypher) {
            // 1. 解析
            Program ast = parser.parseCached(cypher);
            
            // 2. 重写
            ExecutionPlan plan = rewriter.rewrite(ast);
            
            // 3. 执行
            ExecutionResult result = executor.execute(plan).join();
            
            // 4. 聚合
            StitchedResult stitched = stitcher.stitch(result);
            
            // 5. 排序分页
            PagedResult finalResult = sorter.sortAndPaginate(stitched);
            
            // 6. JSON 序列化
            return toJson(finalResult);
        }
    }
    ```
  - 配置类 Configuration

  **Must NOT do**:
  - 禁止返回非 JSON 格式

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Justification**: 核心入口

  **Parallelization**:
  - **Can Run In Parallel**: NO (关键路径)
  - **Blocks**: T21-T27
  - **Blocked By**: T5, T14-T15, T17-T19

  **References**:
  - SDK 设计模式

  **Acceptance Criteria**:
  - [ ] 简单查询端到端成功
  - [ ] 返回合法 JSON

  **QA Scenarios**:
  ```
  Scenario: 端到端测试
    Tool: Bash (JUnit)
    Preconditions: Mock 所有外部依赖
    Steps:
      1. execute("MATCH (n) RETURN n")
      2. assert JSON 包含 data 字段
    Expected Result: 成功返回 JSON
    Evidence: .sisyphus/evidence/task-20-e2e-test.log
  ```

---

- [ ] 21. CircuitBreaker 配置 (Resilience4j)

  **What to do**:
  - 配置 Resilience4j：
    ```java
    @Configuration
    public class ResilienceConfig {
        @Bean
        public CircuitBreakerRegistry circuitBreakerRegistry() {
            return CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                    .slidingWindowSize(50)
                    .failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofSeconds(30))
                    .permittedNumberOfCallsInHalfOpenState(3)
                    .slowCallDurationThreshold(Duration.ofSeconds(3))
                    .slowCallRateThreshold(80)
                    .build()
            );
        }
        
        @Bean
        public RetryConfig retryConfig() {
            return RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(1))
                .retryExceptions(IOException.class)
                .build();
        }
    }
    ```
  - 降级策略：返回部分结果 + Warning

  **Must NOT do**:
  - 禁止静默失败

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Justification**: 配置任务

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5
  - **Blocks**: None
  - **Blocked By**: T14

  **References**:
  - Resilience4j 文档

  **Acceptance Criteria**:
  - [ ] 熔断器正确配置
  - [ ] 降级返回警告

  **QA Scenarios**:
  ```
  Scenario: 熔断降级
    Tool: Bash (JUnit + MockWebServer)
    Steps:
      1. MockWebServer 模拟慢响应
      2. 触发熔断
      3. assert 返回结果包含 Warning
    Expected Result: 降级成功
    Evidence: .sisyphus/evidence/task-21-fallback-test.log
  ```

---

- [ ] 22. OOMProtection (隐式 LIMIT)

  **What to do**:
  - 实现隐式 LIMIT 保护：
    ```java
    public class OOMProtectionInterceptor {
        private static final int DEFAULT_LIMIT = 5000;
        
        public ExecutionPlan enforceLimit(ExecutionPlan plan) {
            // 1. 检查是否有 LIMIT
            // 2. 如果没有，注入隐式 LIMIT
            // 3. 记录警告
        }
        
        public void validateResultSize(List<Path> paths) {
            // 4. 如果结果超限，截断并警告
            if (paths.size() > DEFAULT_LIMIT) {
                log.warn("Result truncated from {} to {}", paths.size(), DEFAULT_LIMIT);
                return paths.subList(0, DEFAULT_LIMIT);
            }
        }
    }
    ```
  - 可配置阈值

  **Must NOT do**:
  - 禁止无限返回结果

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Justification**: 保护机制

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5
  - **Blocks**: None
  - **Blocked By**: T14

  **References**:
  - 全表扫描防护

  **Acceptance Criteria**:
  - [ ] 无 LIMIT 查询自动添加
  - [ ] 超大结果截断

  **QA Scenarios**:
  ```
  Scenario: 隐式 LIMIT
    Tool: Bash (JUnit)
    Steps:
      1. rewrite("MATCH (n) RETURN n")
      2. assert 隐式 LIMIT 已注入
    Expected Result: LIMIT 5000 已添加
    Evidence: .sisyphus/evidence/task-22-limit-test.log
  ```

---

- [ ] 23. WhereConditionPushdown (WHERE 下推)

  **What to do**:
  - 实现 WHERE 条件下推：
    ```java
    public class WhereConditionPushdown {
        public PushdownResult analyze(WhereClause where, Pattern pattern) {
            // 1. 分离 Where 条件
            // 2. 识别每条条件涉及的实体
            // 3. 分类：
            //    - 物理实体条件 → 下推到物理查询
            //    - 虚拟实体条件 → 保留在 Java 层过滤
        }
        
        public List<WhereCondition> extractPhysicalConditions(WhereClause where) {
            // 提取仅涉及物理实体的条件
        }
        
        public List<WhereCondition> extractVirtualConditions(WhereClause where) {
            // 提取涉及虚拟实体的条件
        }
    }
    ```

  **Must NOT do**:
  - 禁止将虚拟条件推给 TuGraph

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: []
  - **Justification**: 核心语义处理

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5
  - **Blocks**: None
  - **Blocked By**: T14

  **References**:
  - 条件下推模式

  **Acceptance Criteria**:
  - [ ] 虚拟实体条件不下推
  - [ ] 物理实体条件下推

  **QA Scenarios**:
  ```
  Scenario: WHERE 下推
    Tool: Bash (JUnit)
    Preconditions: WHERE ne.name = 'x' AND target.value > 90
    Steps:
      1. analyze(where, pattern)
      2. assert physicalConditions = [ne.name = 'x']
      3. assert virtualConditions = [target.value > 90]
    Expected Result: 条件正确分离
    Evidence: .sisyphus/evidence/task-23-pushdown-test.log
  ```

---

- [ ] 24. Unit Tests - Parser 模块

  **What to do**:
  - Parser 模块 UT：
    ```java
    class ParserTest {
        @Test void matchClause();
        @Test void returnClause();
        @Test void patternWithMultipleRels();
        @Test void unionQuery();
        @Test void snapshotSyntax();
        @Test void projectBySyntax();
        @Test void syntaxError();
    }
    ```

  **Must NOT do**:
  - 禁止 Mock Parser

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Justification**: 测试任务

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5
  - **Blocks**: T27
  - **Blocked By**: T20

  **References**:
  - JUnit 5 最佳实践

  **Acceptance Criteria**:
  - [ ] 所有测试通过
  - [ ] 覆盖率 > 80%

  **QA Scenarios**:
  ```
  Scenario: Parser UT 运行
    Tool: Bash
    Steps:
      1. mvn test -Dtest=ParserTest
    Expected Result: BUILD SUCCESS
    Evidence: .sisyphus/evidence/task-24-parser-test.log
  ```

---

- [ ] 25. Unit Tests - Rewriter 模块

  **What to do**:
  - Rewriter 模块 UT：
    ```java
    class RewriterTest {
        @Test void detectVirtualEdges();
        @Test void rewriteSimpleQuery();
        @Test void rewriteUnion();
        @Test void rewritePath();
        @Test void rejectSandwich();
        @Test void pushdownWhere();
    }
    ```

  **Must NOT do**:
  - 禁止 Mock MetadataRegistry（用真实现）

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Justification**: 测试任务

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5
  - **Blocks**: T27
  - **Blocked By**: T20

  **References**:
  - JUnit 5 最佳实践

  **Acceptance Criteria**:
  - [ ] 所有测试通过
  - [ ] 覆盖率 > 80%

  **QA Scenarios**:
  ```
  Scenario: Rewriter UT 运行
    Tool: Bash
    Steps:
      1. mvn test -Dtest=RewriterTest
    Expected Result: BUILD SUCCESS
    Evidence: .sisyphus/evidence/task-25-rewriter-test.log
  ```

---

- [ ] 26. Unit Tests - Executor 模块

  **What to do**:
  - Executor 模块 UT：
    ```java
    class ExecutorTest {
        @Test void parallelExecution();
        @Test void circuitBreaker();
        @Test void batchRequests();
        @Test void timeoutHandling();
        @Test void fallbackOnFailure();
    }
    ```

  **Must NOT do**:
  - 禁止真实网络调用（MockWebServer）

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Justification**: 测试任务

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5
  - **Blocks**: T27
  - **Blocked By**: T20

  **References**:
  - MockWebServer 使用

  **Acceptance Criteria**:
  - [ ] 所有测试通过
  - [ ] 覆盖率 > 80%

  **QA Scenarios**:
  ```
  Scenario: Executor UT 运行
    Tool: Bash
    Steps:
      1. mvn test -Dtest=ExecutorTest
    Expected Result: BUILD SUCCESS
    Evidence: .sisyphus/evidence/task-26-executor-test.log
  ```

---

- [ ] 27. Unit Tests - Aggregator 模块

  **What to do**:
  - Aggregator 模块 UT：
    ```java
    class AggregatorTest {
        @Test void stitchResults();
        @Test void buildPaths();
        @Test void globalSort();
        @Test void unionDeduplicate();
        @Test void pagination();
    }
    ```

  **Must NOT do**:
  - 禁止 Mock 结果对象

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []
  - **Justification**: 测试任务

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 5
  - **Blocks**: T27
  - **Blocked By**: T20

  **References**:
  - JUnit 5 最佳实践

  **Acceptance Criteria**:
  - [ ] 所有测试通过
  - [ ] 覆盖率 > 80%

  **QA Scenarios**:
  ```
  Scenario: Aggregator UT 运行
    Tool: Bash
    Steps:
      1. mvn test -Dtest=AggregatorTest
    Expected Result: BUILD SUCCESS
    Evidence: .sisyphus/evidence/task-27-aggregator-test.log
  ```

---

## Final Verification Wave

- [ ] F1. **Plan Compliance Audit**
  - 读取完整计划
  - 验证每个 Must Have 存在
  - 验证每个 Must NOT Have 不存在
  - 检查 evidence 文件存在

- [ ] F2. **Integration Test (5个示例场景)**
  - 运行所有5个示例查询
  - 验证结果正确性

- [ ] F3. **Scope Fidelity Check**
  - 验证无任务遗漏
  - 验证无范围蔓延

---

## Commit Strategy

- **Wave 1**: `feat(core): project structure and AST models`
- **Wave 2**: `feat(parser): Cypher parser and rewriter`
- **Wave 3**: `feat(executor): federated executor and adapters`
- **Wave 4**: `feat(aggregator): result stitching and SDK`
- **Wave 5**: `feat(reliability): circuit breaker and tests`

---

## Success Criteria

### Verification Commands
```bash
# 编译
mvn compile

# 测试
mvn test

# 示例场景
mvn test -Dtest=IntegrationTest
```

### Final Checklist
- [ ] 所有 Must Have 满足
- [ ] 所有 Must NOT Have 不存在
- [ ] 所有测试通过
- [ ] 5个示例场景验证通过
- [ ] 无编译警告
- [ ] 代码覆盖率 > 70%
