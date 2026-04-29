# Constraint Compliance Fix Plan

## TL;DR

> **Quick Summary**: Add validation logic for 3 constraint compliance gaps: read-only enforcement, cross-type WHERE AND rejection, multi-datasource ORDER BY/LIMIT validation.
> 
> **Deliverables**:
> - Read-only rejection in CypherASTVisitor (CREATE/MERGE/DELETE/SET/REMOVE/CALL)
> - Cross-type WHERE validation in QueryRewriter
> - Multi-datasource ORDER BY/LIMIT validation in QueryRewriter
> - JUnit 5 test coverage for each constraint
> 
> **Estimated Effort**: Medium (~2-3 hours)
> **Parallel Execution**: NO - Sequential validation layers
> **Critical Path**: Read-Only → Cross-Type WHERE → Multi-Datasource ORDER BY

---

## Context

### Original Request
深度审视代码实现是否符合 `docs/idea/Cypher语法范围与约束清单.md` 规范，发现3个合规性问题需要修复。

### Audit Findings
审计发现：
- ✅ 虚拟边边界约束：完全实现
- ✅ 执行顺序约束：完全实现
- ✅ 批量请求约束：完全实现
- ❌ 只读约束：未拒绝写操作
- ⚠️ WHERE Pushdown：跨类型AND组合未验证
- ⚠️ ORDER BY/LIMIT：多数据源验证缺失

### User Decisions
1. **CALL处理**: 拒绝所有CALL语句（严格只读）
2. **多数据源定义**: 最终返回结果集包含多数据源时，不能有ORDER BY/LIMIT
3. **异常类型**: 复用现有 `SyntaxErrorException`

### Metis Review
**Identified Gaps** (addressed):
- WhereConditionPushdown文件路径已确认：`com.fangyang.federatedquery.reliability.WhereConditionPushdown`
- 验证点位置确定：CypherASTVisitor（只读）、QueryRewriter（WHERE、ORDER BY）
- 边界情况已记录：UNWIND+SET、LIMIT 0、嵌套表达式

---

## Work Objectives

### Core Objective
添加约束验证逻辑，确保代码实现100%符合文档规范。

### Concrete Deliverables
- `CypherASTVisitor.java`: 添加 `visitOC_UpdatingClause` 和 `visitOC_InQueryCall` 拒绝逻辑
- `QueryRewriter.java`: 添加跨类型WHERE验证和多数据源ORDER BY验证
- JUnit测试：每项约束至少2个正向测试+1个负向测试

### Definition of Done
- [ ] `mvn test` 全部通过（无回归）
- [ ] 新测试覆盖所有约束场景
- [ ] 错误消息清晰可读

### Must Have
- 拒绝 CREATE, MERGE, DELETE, SET, REMOVE, CALL
- 拒绝物理+虚拟条件的AND组合
- 拒绝多数据源的 ORDER BY/LIMIT

### Must NOT Have (Guardrails)
- 不修改 `Lcypher.g4` 语法文件
- 不在 FederatedExecutor 层添加验证（太晚）
- 不改变现有有效查询的行为（零回归）
- 不新建异常类（复用 SyntaxErrorException）
- 不添加数据源计数跟踪（scope creep）

---

## Verification Strategy

### Test Decision
- **Infrastructure exists**: YES (JUnit 5, AssertJ)
- **Automated tests**: YES (TDD style)
- **Framework**: JUnit 5 + AssertJ

### QA Policy
每个任务必须包含Agent可执行的QA场景：
- 使用 AssertJ `assertThatThrownBy()` 验证异常
- 包含具体的查询字符串示例
- 验证异常消息包含正确的错误类型描述

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Sequential - AST Layer):
├── Task 1: Read-only constraint (visitOC_UpdatingClause) [quick]
└── Task 2: CALL rejection (visitOC_InQueryCall) [quick]

Wave 2 (Sequential - Rewriter Layer, depends Wave 1):
├── Task 3: Cross-type WHERE validation [quick]
└── Task 4: Multi-datasource ORDER BY/LIMIT validation [quick]

Wave 3 (Parallel - Test Coverage):
├── Task 5: Read-only tests [quick]
├── Task 6: Cross-type WHERE tests [quick]
└── Task 7: Multi-datasource ORDER BY tests [quick]

Wave 4 (Verification):
├── Task 8: Integration test run [quick]
└── Task 9: Regression test run [quick]

Wave FINAL (After ALL tasks):
├── Task F1: Plan compliance audit (oracle)
├── Task F2: Code quality review (unspecified-high)
└── Task F3: Scope fidelity check (deep)
```

### Critical Path
Task 1 → Task 2 → Task 3 → Task 4 → Task 8 → Task F1

---

## TODOs

- [ ] 1. **Add visitOC_UpdatingClause Override in CypherASTVisitor**

  **What to do**:
  - 在 `CypherASTVisitor.java` 中添加 `visitOC_UpdatingClause()` 方法覆盖
  - 检测具体clause类型（CREATE/MERGE/DELETE/SET/REMOVE）
  - 抛出 `SyntaxErrorException` 拒绝写操作

  **Must NOT do**:
  - 不修改语法文件 `Lcypher.g4`
  - 不在executor层添加验证

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 单文件修改，方法添加，明确的实现模式
  - **Skills**: []
    - 无特殊技能需求

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 1 (Sequential)
  - **Blocks**: Task 2, Task 3, Task 4
  - **Blocked By**: None

  **References**:
  - `CypherASTVisitor.java:27-32` - 现有的EXPLAIN/PROFILE拒绝模式（复制此模式）
  - `Lcypher.g4:88-93` - UpdatingClause语法定义（了解5种clause）
  - `SyntaxErrorException.java` - 异常类定义

  **Acceptance Criteria**:
  - [ ] `visitOC_UpdatingClause` 方法存在
  - [ ] CREATE/MERGE/DELETE/SET/REMOVE 查询被拒绝

  **QA Scenarios**:
  ```
  Scenario: CREATE clause rejection
    Tool: Bash (mvn test)
    Preconditions: 编译成功
    Steps:
      1. 运行测试：创建包含 "CREATE (n:Person)" 的Cypher
      2. 调用 sdk.execute(cypher)
      3. 验证抛出 SyntaxErrorException
    Expected Result: 异常消息包含 "Read-only engine: 'CREATE' clauses are not supported"
    Evidence: .sisyphus/evidence/task-01-create-rejection.log

  Scenario: MERGE clause rejection
    Tool: Bash (mvn test)
    Steps:
      1. 测试 "MERGE (n:Person {name: 'Alice'})"
      2. 验证抛出异常
    Expected Result: 异常消息包含 "'MERGE' clauses are not supported"
  ```

  **Commit**: YES (groups with Task 2)
  - Message: `fix(constraint): reject write operations in CypherASTVisitor`

- [ ] 2. **Add CALL Statement Rejection in CypherASTVisitor**

  **What to do**:
  - 在 `CypherASTVisitor.java` 中修改 `visitOC_InQueryCall` 或添加拒绝逻辑
  - 拒绝所有CALL语句（严格只读策略）

  **Must NOT do**:
  - 不添加procedure白名单（记录为future work）

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 1 (Sequential)
  - **Blocks**: Task 3, Task 4
  - **Blocked By**: Task 1

  **References**:
  - `CypherASTVisitor.java` - 找到 visitOC_InQueryCall 方法
  - `Lcypher.g4:150-156` - CALL语法定义

  **Acceptance Criteria**:
  - [ ] CALL语句被拒绝
  - [ ] 异常消息包含 "'CALL' clauses are not supported"

  **QA Scenarios**:
  ```
  Scenario: CALL rejection
    Tool: Bash (mvn test)
    Steps:
      1. 测试 "CALL db.createIndex()"
      2. 验证抛出 SyntaxErrorException
    Expected Result: 异常消息包含 "'CALL' clauses are not supported"
  ```

  **Commit**: YES (groups with Task 1)

- [ ] 3. **Add Cross-Type WHERE AND Validation in QueryRewriter**

  **What to do**:
  - 在 `QueryRewriter.rewriteMatchClause()` 中添加验证逻辑
  - 检查 `pushdownResult` 是否同时包含 physicalConditions 和 virtualConditions
  - 检查原始WHERE是否使用AND操作符（需要从WhereClause获取）
  - 若存在跨类型AND，抛出 `SyntaxErrorException`

  **Must NOT do**:
  - 不修改 WhereConditionPushdown 的split逻辑（已正确）
  - 不验证OR组合（已保守处理）
  - 不分析嵌套表达式（scope creep）

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 2 (Sequential)
  - **Blocks**: Task 4
  - **Blocked By**: Task 1, Task 2

  **References**:
  - `QueryRewriter.java:230-256` - rewriteMatchClause方法（在此添加验证）
  - `WhereConditionPushdown.java:263-300` - PushdownResult类结构
  - `WhereClause.java` - 需确认是否存储AND/OR操作符信息

  **Acceptance Criteria**:
  - [ ] 物理条件AND虚拟条件被拒绝
  - [ ] 单一类型条件（物理AND物理、虚拟AND虚拟）仍可正常工作

  **QA Scenarios**:
  ```
  Scenario: Cross-type AND rejection
    Tool: Bash (mvn test)
    Preconditions: 注册NE(物理)和KPI(虚拟)标签元数据
    Steps:
      1. 构造查询：MATCH (ne:NE), (kpi:KPI) WHERE ne.name='NE001' AND kpi.id='kpi1' RETURN ne, kpi
      2. 调用 sdk.execute(cypher)
      3. 验证抛出 SyntaxErrorException
    Expected Result: 异常消息包含 "Cross-type WHERE conditions with AND operator is ambiguous"
    Evidence: .sisyphus/evidence/task-03-cross-type-and.log

  Scenario: Single-type AND allowed (physical)
    Tool: Bash (mvn test)
    Steps:
      1. 测试：MATCH (ne:NE) WHERE ne.name='NE001' AND ne.status='active' RETURN ne
      2. 验证查询正常执行（不抛异常）
    Expected Result: 查询成功返回结果

  Scenario: Cross-type OR allowed
    Tool: Bash (mvn test)
    Steps:
      1. 测试：MATCH (ne:NE), (kpi:KPI) WHERE ne.name='NE001' OR kpi.id='kpi1' RETURN ne, kpi
      2. 验证查询正常执行
    Expected Result: 查询成功（OR组合被允许）
  ```

  **Commit**: YES (groups with Task 4)
  - Message: `fix(constraint): validate cross-type WHERE AND combinations`

- [ ] 4. **Add Multi-Datasource ORDER BY/LIMIT Validation in QueryRewriter**

  **What to do**:
  - 在 `QueryRewriter.rewriteReturnClause()` 中添加验证
  - 使用 `plan.hasVirtualElements()` 作为多数据源proxy
  - 若 hasVirtualElements=true 且存在ORDER BY/LIMIT，抛出异常
  - 用户定义："最终返回结果集包含多数据源"

  **Must NOT do**:
  - 不添加数据源计数跟踪（scope creep）
  - 不修改 GlobalSorter 逻辑（已正确）
  - 不处理 LIMIT 0 边界情况（记录为known issue）

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 2 (Sequential)
  - **Blocks**: Task 5-7
  - **Blocked By**: Task 3

  **References**:
  - `QueryRewriter.java:279-307` - rewriteReturnClause方法
  - `QueryRewriter.java:54` - hasVirtualElements标志
  - `GlobalSorter.java` - 了解ORDER BY/LIMIT如何被处理

  **Acceptance Criteria**:
  - [ ] hasVirtualElements=true时，ORDER BY被拒绝
  - [ ] hasVirtualElements=true时，LIMIT被拒绝
  - [ ] 纯物理查询ORDER BY/LIMIT仍正常工作

  **QA Scenarios**:
  ```
  Scenario: Multi-datasource ORDER BY rejection
    Tool: Bash (mvn test)
    Preconditions: 注册虚拟边元数据
    Steps:
      1. 构造查询：MATCH (ne:NE)-[:VirtualEdge]->(kpi:KPI) RETURN ne, kpi ORDER BY ne.name
      2. 调用 sdk.execute(cypher)
      3. 验证抛出 SyntaxErrorException
    Expected Result: 异常消息包含 "ORDER BY is not supported for multi-datasource queries"
    Evidence: .sisyphus/evidence/task-04-order-by-rejection.log

  Scenario: Single datasource ORDER BY allowed
    Tool: Bash (mvn test)
    Steps:
      1. 测试：MATCH (ne:NE) RETURN ne ORDER BY ne.name LIMIT 10
      2. 验证查询正常执行
    Expected Result: 查询成功返回排序结果

  Scenario: Multi-datasource LIMIT rejection
    Tool: Bash (mvn test)
    Steps:
      1. 测试包含虚拟边的查询 + LIMIT
      2. 验证抛出异常
    Expected Result: 异常消息包含 "LIMIT is not supported for multi-datasource queries"
  ```

  **Commit**: YES (groups with Task 3)

- [ ] 5. **Write Read-Only Constraint Tests**

  **What to do**:
  - 创建或扩展测试类 `ConstraintComplianceTest.java`
  - 使用 AssertJ `assertThatThrownBy()` 验证异常
  - 包含正向测试（MATCH/RETURN/WITH正常）和负向测试

  **Must NOT do**:
  - 不依赖用户手动测试

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: [`java-junit`]
    - java-junit: JUnit 5 + AssertJ最佳实践

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 6, 7)
  - **Blocks**: Task 8
  - **Blocked By**: Task 1, Task 2

  **References**:
  - `RewriterTest.java` - 现有测试模式参考
  - `E2ETest.java` - E2E测试模式参考

  **Acceptance Criteria**:
  - [ ] 6个负向测试通过（CREATE/MERGE/DELETE/SET/REMOVE/CALL）
  - [ ] 3个正向测试通过（MATCH/RETURN/WITH）

  **QA Scenarios**:
  ```
  Scenario: Test suite runs successfully
    Tool: Bash (mvn test)
    Steps:
      1. 运行 mvn test -Dtest=ConstraintComplianceTest
      2. 验证所有测试通过
    Expected Result: Tests run: 9, Failures: 0
    Evidence: .sisyphus/evidence/task-05-test-run.log
  ```

  **Commit**: YES
  - Message: `test(constraint): add read-only constraint tests`

- [ ] 6. **Write Cross-Type WHERE Tests**

  **What to do**:
  - 在测试类中添加跨类型WHERE验证测试
  - 测试物理+虚拟AND被拒绝
  - 测试单一类型AND/OR被允许

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: [`java-junit`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3

  **References**:
  - `WhereConditionPushdown.java` - 理解条件分类

  **Acceptance Criteria**:
  - [ ] 2个负向测试（跨类型AND）
  - [ ] 3个正向测试（单类型AND、单类型OR、跨类型OR）

  **Commit**: YES (groups with Task 5)

- [ ] 7. **Write Multi-Datasource ORDER BY Tests**

  **What to do**:
  - 在测试类中添加ORDER BY/LIMIT验证测试
  - 测试多数据源时被拒绝
  - 测试单数据源时正常工作

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: [`java-junit`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3

  **Acceptance Criteria**:
  - [ ] 2个负向测试（ORDER BY多源、LIMIT多源）
  - [ ] 2个正向测试（ORDER BY单源、LIMIT单源）

  **Commit**: YES (groups with Task 5)

- [ ] 8. **Run Full Integration Test Suite**

  **What to do**:
  - 运行完整测试套件验证无回归
  - 确保所有37个现有测试仍通过

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: []

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 4
  - **Blocked By**: Task 5, Task 6, Task 7

  **QA Scenarios**:
  ```
  Scenario: No regression in existing tests
    Tool: Bash (mvn test)
    Steps:
      1. 运行 mvn test
      2. 验证 Tests run: >= 37, Failures: 0
    Expected Result: All existing tests pass
    Evidence: .sisyphus/evidence/task-08-regression.log
  ```

  **Commit**: NO

- [ ] 9. **Document Known Limitations**

  **What to do**:
  - 更新 `docs/idea/Cypher语法范围与约束清单.md` 或 AGENTS.md
  - 记录已知边界情况：
    - CALL procedure写操作风险（已拒绝所有CALL）
    - LIMIT 0语义（已知问题）
    - 嵌套WHERE表达式（scope creep）
    - UNION跨数据源ORDER BY

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: [`writing-clearly-and-concisely`]

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 4 (with Task 8)

  **Commit**: YES
  - Message: `docs(constraint): document known limitations`

---

## Final Verification Wave

- [ ] F1. **Plan Compliance Audit** — `oracle`
  Verify all Must Have items implemented, all Must NOT Have items absent.

- [ ] F2. **Code Quality Review** — `unspecified-high`
  Run `tsc --noEmit` + linter + `mvn test`. Check for unused imports, console.log, commented code.

- [ ] F3. **Scope Fidelity Check** — `deep`
  Verify no scope creep: no grammar changes, no new exception classes, no datasource tracking.

---

## Commit Strategy

- **Wave 1-2**: `fix(constraint): add read-only and cross-type WHERE validation`
- **Wave 3**: `test(constraint): add constraint compliance test coverage`
- **Wave 4**: `verify(constraint): ensure no regression`

---

## Success Criteria

### Verification Commands
```bash
mvn compile  # Must succeed (ANTLR generated)
mvn test     # Must pass all tests (37 existing + new)
mvn test -Dtest=ConstraintComplianceTest  # New tests must pass
```

### Final Checklist
- [ ] CREATE/MERGE/DELETE/SET/REMOVE/CALL rejected
- [ ] Cross-type WHERE AND rejected
- [ ] Multi-datasource ORDER BY/LIMIT rejected
- [ ] All existing tests pass
- [ ] New tests cover all scenarios