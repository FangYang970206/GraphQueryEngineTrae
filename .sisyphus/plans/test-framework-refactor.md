# Test Framework Refactoring - HTTP Server with Rich Query Support

## TL;DR

> **Quick Summary**: 重构测试框架，将 MockExternalAdapter 改为真实 HTTP 调用，HTTP Server 支持过滤、聚合、排序、分页，数据从配置文件加载，通过 JUnit Extension 管理多独立 Server 进程。
> 
> **Deliverables**:
> - 多个独立 HTTP Server（Alarm:8081, KPI:8082, Card:8083, 等）
> - HttpExternalAdapter 替代 MockExternalAdapter
> - JUnit 5 Extension 启动/停止 HTTP Server
> - 测试数据配置文件（JSON/YAML）
> - TuGraph 初始化 Cypher 脚本
> - 22 个 E2E 测试改造为真实 HTTP 调用
> - TDD 单元测试覆盖
> 
> **Estimated Effort**: Large
> **Parallel Execution**: YES - 4 waves (部分任务因固定端口限制需顺序执行)
> **Critical Path**: Task 1 → Task 5 → Task 8 → Task 11 → Task 15 → Task 17

---

## Context

### Original Request
用户希望对整个测试框架进行重构：
1. 所有真实数据全部在 TuGraph 中存储（TuGraph 外部已运行）
2. 外部数据通过启动 HTTP Server，通过 HTTP 请求访问
3. HTTP Server 支持丰富的过滤条件、聚合、排序、分页等

### Interview Summary
**Key Discussions**:
- HTTP Server 启动方式: 独立进程启动（多独立 Server）
- 功能范围: 全部实现（过滤、聚合、排序、分页）
- HTTP Server 数据流向: 独立数据源（不从 TuGraph 获取）
- TuGraph 启动: 外部已运行
- E2E 测试改造: MockExternalAdapter → HttpExternalAdapter（真实 HTTP 调用）
- 数据存储: 内存数据
- 数据初始化: 配置文件加载（JSON/YAML）
- API 设计: 混合模式（简单过滤用查询参数，复杂聚合用 POST JSON）
- 端口管理: 固定端口分配（Alarm:8081, KPI:8082, Card:8083）
- HTTP Server 管理: JUnit 5 Extension 启动/停止
- 测试策略: 全面 TDD + E2E
- TuGraph 初始化: Cypher 脚本
- 测试隔离: 不可变数据（只读）
- 错误处理: 立即失败
- HTTP 超时: 30秒

**Research Findings**:
- Librarian: WireMock/MockServer 不适合，推荐 Embedded Jetty/SparkJava（但用户选择独立进程，可继续用 HttpServer）
- Explore: 已有 MockHttpServer, MockDataRepository, MockExternalAdapter（577行代码），22 个 E2E 测试

### Metis Review
**Identified Gaps** (addressed):
- TuGraph 数据初始化: ✓ Cypher 脚本初始化
- 测试隔离策略: ✓ 不可变数据（只读）
- 固定端口 vs 动态端口: ✓ 用户坚持固定端口（限制顺序执行，需文档说明）
- 现有测试状态: ✓ 全部通过（22 个 E2E 测试）
- 错误处理策略: ✓ 立即失败
- HTTP 超时配置: ✓ 30秒

**Guardrails Applied** (from Metis):
- Zero functional changes to SDK core logic (parser, rewriter, executor)
- Test behavior preservation: All 22 E2E tests must pass with identical results
- HTTP servers are test-only infrastructure (not production code)
- No scope creep: Only filtering, aggregation, sorting, pagination
- TDD discipline: Tests BEFORE implementation
- Fixed ports limitation: Sequential test execution only

---

## Work Objectives

### Core Objective
重构测试框架，将内存模拟改为真实 HTTP 交互，HTTP Server 支持完整的 REST-like 查询能力（过滤、聚合、排序、分页），所有 22 个 E2E 测试改造后行为保持一致。

### Concrete Deliverables
- `testinfra/` 目录：HTTP Server 框架基础设施
- `HttpExternalAdapter.java`: 替代 MockExternalAdapter，发起真实 HTTP 调用
- `HttpServerExtension.java`: JUnit 5 Extension 管理多 Server 启动/停止
- `TestDataLoader.java`: 从 JSON/YAML 配置文件加载测试数据
- `AlarmHttpServer.java`, `KpiHttpServer.java`: 多独立 Server
- `src/test/resources/test-data/*.json`: 测试数据配置文件
- `src/test/resources/tugraph-init/*.cypher`: TuGraph 初始化脚本
- 改造后的 22 个 E2E 测试
- TDD 单元测试覆盖所有新组件

### Definition of Done
- [ ] `mvn test -Dtest=E2ETest` → Tests run: 22, Failures: 0, Errors: 0
- [ ] `mvn test` → All tests pass, including new TDD tests
- [ ] HTTP servers start/stop correctly via JUnit Extension
- [ ] Test data loads from JSON config files
- [ ] TuGraph initialized via Cypher scripts
- [ ] Performance: Test suite completes in <60 seconds

### Must Have
- HTTP Server 支持过滤（多字段、多条件、AND/OR）
- HTTP Server 支持聚合（count, sum, avg, groupBy）
- HTTP Server 支持排序（多字段、ASC/DESC）
- HTTP Server 支持分页（offset/limit）
- HTTP Server 支持批量查询（batch endpoint）
- HttpExternalAdapter 完全替代 MockExternalAdapter 功能
- 所有 22 个 E2E 测试通过
- TDD 单元测试 >80% 覆盖率

### Must NOT Have (Guardrails)
- 修改生产代码（parser, rewriter, executor, SDK）
- 添加认证/授权功能
- 添加数据库持久化（内存数据）
- 添加 GraphQL/WebSocket 等协议
- 添加速率限制、缓存等生产特性
- 修改测试断言或期望结果
- 并行测试执行（固定端口限制）

---

## Verification Strategy (MANDATORY)

> **ZERO HUMAN INTERVENTION** — ALL verification is agent-executed. No exceptions.

### Test Decision
- **Infrastructure exists**: YES (JUnit 5)
- **Automated tests**: YES (TDD + E2E)
- **Framework**: JUnit 5 + Maven Surefire
- **TDD Approach**: Each new class has tests BEFORE implementation

### QA Policy
Every task MUST include agent-executed QA scenarios.
Evidence saved to `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`.

- **HTTP Server Testing**: Use Bash (curl) — Send requests, assert status + response fields
- **Adapter Testing**: Use Bash (mock server) — Start mock, send request, validate response
- **JUnit Extension Testing**: Use Bash (mvn test) — Run tests, verify servers start/stop
- **E2E Testing**: Use Bash (mvn test) — Run full suite, assert all pass

---

## Execution Strategy

### Parallel Execution Waves

> Fixed ports limit parallel HTTP server testing, but other tasks can run parallel.
> Target: 4-6 tasks per wave where possible.

```
Wave 0 (Foundation - MUST DO FIRST):
├── Task 0: Run baseline - capture current E2E test results [quick]
└── Task 0.1: Review MockExternalAdapter interface [quick]

Wave 1 (Test Infrastructure - Foundation):
├── Task 1: Create testinfra directory structure [quick]
├── Task 2: Design HTTP Server API contract [quick]
├── Task 3: Create test data config file schema [quick]
├── Task 4: Create TuGraph init Cypher scripts [quick]
└── Task 5: Implement TestDataLoader (TDD) [deep]

Wave 2 (HTTP Server Implementation - TDD):
├── Task 6: Write HttpServerBaseTest (TDD) [quick]
├── Task 7: Implement HttpServerBase with filtering/sorting/pagination [unspecified-high]
├── Task 8: Implement aggregation handler (POST JSON) [deep]
├── Task 9: Implement batch endpoint handler [quick]
├── Task 10: Write AlarmHttpServerTest + implement [unspecified-high]
├── Task 11: Write KpiHttpServerTest + implement [unspecified-high]
└── Task 12: Write CardHttpServerTest + implement [unspecified-high]

Wave 3 (JUnit Extension & Adapter - TDD):
├── Task 13: Write HttpServerExtensionTest [quick]
├── Task 14: Implement HttpServerExtension (start/stop multi-servers) [unspecified-high]
├── Task 15: Write HttpExternalAdapterTest (TDD) [deep]
└── Task 16: Implement HttpExternalAdapter [unspecified-high]

Wave 4 (E2E Test Migration):
├── Task 17: Update GraphQueryMetaFactory for real HTTP [quick]
├── Task 18: Migrate E2E tests one by one (validate each) [deep]
└── Task 19: Run full E2E suite validation [deep]

Wave 5 (Documentation & Final Verification):
├── Task 20: Update AGENTS.md test architecture section [writing]
├── Task 21: Create test infrastructure README [writing]
├── Task 22: Document HTTP Server API [writing]
└── Task 23: Final verification - run all acceptance criteria [deep]

Wave FINAL (Independent Review):
├── Task F1: Plan compliance audit (oracle)
├── Task F2: Code quality review (unspecified-high)
├── Task F3: Real E2E QA (unspecified-high)
└── Task F4: Scope fidelity check (deep)
```

### Dependency Matrix

- **0-0.1**: — — Wave 1
- **1-5**: 0, 0.1 — Wave 2
- **6-12**: 1-5 — Wave 3
- **13-16**: 6-12 — Wave 4
- **17-19**: 13-16 — Wave 5
- **20-23**: 17-19 — Wave FINAL
- **F1-F4**: 17-23 — Final Review

### Agent Dispatch Summary

- **Wave 0**: **2** — quick
- **Wave 1**: **5** — quick (T1-T4), deep (T5)
- **Wave 2**: **7** — quick (T6, T9), unspecified-high (T7, T10, T11, T12), deep (T8)
- **Wave 3**: **4** — quick (T13), unspecified-high (T14, T16), deep (T15)
- **Wave 4**: **3** — quick (T17), deep (T18, T19)
- **Wave 5**: **4** — writing (T20-T22), deep (T23)
- **FINAL**: **4** — oracle (F1), unspecified-high (F2, F3), deep (F4)

---

## TODOs

- [ ] 0. Run Baseline - Capture Current E2E Test Results

  **What to do**:
  - Run all 22 E2E tests with `mvn test -Dtest=E2ETest`
  - Capture output: pass rate, execution time, result JSON samples
  - Save baseline to `.sisyphus/baseline/e2e-baseline.json`
  - This establishes the "behavior preservation" baseline for validation

  **Must NOT do**:
  - Skip any failing tests (record failures as-is)
  - Modify test configuration

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `[]`
    - Trivial task, no special skills needed

  **Parallelization**:
  - **Can Run In Parallel**: NO (MUST run first)
  - **Parallel Group**: Wave 0 (sequential)
  - **Blocks**: All Wave 1-5 tasks
  - **Blocked By**: None

  **References**:
  - `graph-query-engine/src/test/java/.../e2e/E2ETest.java` - Current E2E test suite (22 tests)
  - `pom.xml` - Maven test configuration

  **Acceptance Criteria**:
  - [ ] `mvn test -Dtest=E2ETest` executed successfully
  - [ ] Baseline file created: `.sisyphus/baseline/e2e-baseline.json`
  - [ ] Baseline contains: test count, pass/fail count, execution time, sample outputs

  **QA Scenarios**:
  ```
  Scenario: Baseline capture succeeds
    Tool: Bash (mvn)
    Preconditions: Maven project compiled
    Steps:
      1. mvn test -Dtest=E2ETest -Dsurefire.useFile=false
      2. Parse output for test count and pass/fail
    Expected Result: Tests run: 22, documented in baseline file
    Evidence: .sisyphus/evidence/task-0-baseline-capture.txt
  ```

  **Evidence to Capture**:
  - [ ] Test execution output
  - [ ] Baseline JSON file with pass/fail counts

  **Commit**: NO (baseline file is temporary working memory)

- [ ] 0.1. Review MockExternalAdapter Interface

  **What to do**:
  - Read `MockExternalAdapter.java` fully (577 lines)
  - Document all public methods and their signatures
  - Identify the interface contract for drop-in replacement
  - List all usages via `lsp_find_references`
  - Understand batching mechanism (`BatchingStrategy`)

  **Must NOT do**:
  - Modify MockExternalAdapter code
  - Skip understanding the filtering mechanism (lines 122-180)

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `[]`
    - Reading task, no special skills

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Task 0)
  - **Parallel Group**: Wave 0
  - **Blocks**: Task 15-16 (adapter implementation)
  - **Blocked By**: None

  **References**:
  - `graph-query-engine/src/test/java/.../adapter/MockExternalAdapter.java:1-577` - Complete adapter implementation
  - `MockExternalAdapter.java:122-180` - Entity filtering logic
  - `MockExternalAdapter.java:248-296` - executeExternalQuery method
  - `MockExternalAdapter.java:451-567` - MockResponse class

  **Acceptance Criteria**:
  - [ ] Interface contract documented: all methods, parameters, return types
  - [ ] Filtering operators documented: =, !=, >, <, IN, CONTAINS, STARTS WITH, ENDS WITH
  - [ ] Batching mechanism understood: how inputIds are processed
  - [ ] Reference count documented: how many files use MockExternalAdapter

  **QA Scenarios**:
  ```
  Scenario: Interface review complete
    Tool: Bash (grep/lsp)
    Preconditions: Task 0 running or complete
    Steps:
      1. Read MockExternalAdapter.java fully
      2. lsp_find_references on MockExternalAdapter class
      3. Document interface in draft file
    Expected Result: Complete interface documentation
    Evidence: .sisyphus/evidence/task-0.1-interface-review.md
  ```

  **Evidence to Capture**:
  - [ ] Interface contract document
  - [ ] Reference count summary

  **Commit**: NO

- [ ] 1. Create testinfra Directory Structure

  **What to do**:
  - Create `graph-query-engine/src/test/java/.../testinfra/` directory
  - Create subdirectories: `server/`, `adapter/`, `extension/`, `data/`
  - Create package structure following existing test patterns
  - Create `src/test/resources/test-data/` for JSON config files
  - Create `src/test/resources/tugraph-init/` for Cypher scripts

  **Must NOT do**:
  - Create in production source directory (src/main)
  - Add unnecessary nested packages

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: YES (with T2, T3, T4)
  - **Parallel Group**: Wave 1
  - **Blocks**: Wave 2 (server implementation)
  - **Blocked By**: Task 0

  **References**:
  - `graph-query-engine/src/test/java/.../mockserver/` - Existing mock server pattern
  - `graph-query-engine/src/test/java/.../adapter/` - Existing adapter pattern

  **Acceptance Criteria**:
  - [ ] Directory structure created: `testinfra/server/`, `testinfra/adapter/`, `testinfra/extension/`, `testinfra/data/`
  - [ ] Resource directories created: `test-data/`, `tugraph-init/`

  **QA Scenarios**:
  ```
  Scenario: Directory structure exists
    Tool: Bash (ls)
    Steps:
      1. ls -la graph-query-engine/src/test/java/.../testinfra/
      2. ls -la graph-query-engine/src/test/resources/test-data/
    Expected Result: All directories present
    Evidence: .sisyphus/evidence/task-1-dir-structure.txt
  ```

  **Commit**: NO (grouped with Wave 1)

- [ ] 2. Design HTTP Server API Contract

  **What to do**:
  - Design REST API endpoints for each data source (Alarm, KPI, Card)
  - Define query parameter format: `?filter[field]=value&sort=field:ASC&offset=0&limit=20`
  - Define aggregation POST body format: `{"groupBy": "field", "metrics": ["count", "sum(value)"]}`
  - Define batch endpoint: `POST /batch {"ids": ["id1", "id2"]}`
  - Create API contract document in draft file

  **Must NOT do**:
  - Add authentication headers
  - Add versioning (/v1/, /v2/)
  - Design more than 5 data sources

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: YES (with T1, T3, T4)
  - **Parallel Group**: Wave 1
  - **Blocks**: Wave 2 (server implementation needs contract)
  - **Blocked By**: Task 0

  **References**:
  - `mockserver/BaseDataHandler.java:28-78` - Current handler pattern (GET/POST, query params)
  - `mockserver/MockDataRepository.java:90-131` - Current query methods (keyParam, timestamp)

  **Acceptance Criteria**:
  - [ ] API endpoints defined: `/rest/alarm/v1`, `/rest/kpi/v1`, `/rest/card/v1`
  - [ ] Filter syntax: `?filter[name]=value&filter[status]=active`
  - [ ] Sort syntax: `?sort=createdAt:DESC,name:ASC`
  - [ ] Pagination syntax: `?offset=0&limit=20`
  - [ ] Aggregation syntax: POST `/aggregate` with JSON body
  - [ ] Batch syntax: POST `/batch` with `{"ids": [...]}`

  **QA Scenarios**:
  ```
  Scenario: API contract documented
    Tool: Write (draft update)
    Steps:
      1. Document all endpoints in draft
      2. Define request/response formats
    Expected Result: Complete API contract in .sisyphus/drafts/
    Evidence: .sisyphus/evidence/task-2-api-contract.md
  ```

  **Commit**: NO

- [ ] 3. Create Test Data Config File Schema

  **What to do**:
  - Design JSON schema for test data configuration
  - Define structure: `{ "dataSource": "alarm", "records": [...] }`
  - Create sample config files: `alarm-data.json`, `kpi-data.json`, `card-data.json`
  - Define field types and required properties per data source
  - Ensure data matches existing MockDataRepository structure

  **Must NOT do**:
  - Add more than 100 records per data source
  - Use YAML (stick to JSON for simplicity)
  - Add complex nested structures

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: YES (with T1, T2, T4)
  - **Parallel Group**: Wave 1
  - **Blocks**: Task 5 (TestDataLoader)
  - **Blocked By**: Task 0

  **References**:
  - `mockserver/MockDataRepository.java:18-88` - Current hardcoded data structure
  - `mockserver/MockDataRepository.java:19-41` - Alarm data fields (CSN, MENAME, MEDN, OCCURTIME, CLEARTIME)
  - `mockserver/MockDataRepository.java:44-58` - KPI data fields (resId, name, parentResId, time)

  **Acceptance Criteria**:
  - [ ] JSON schema defined for each data source
  - [ ] Sample config files created: `alarm-data.json`, `kpi-data.json`, `kpi2-data.json`, `card-data.json`
  - [ ] Record counts match MockDataRepository: Alarm(3), KPI(2), KPI2(4)

  **QA Scenarios**:
  ```
  Scenario: Config files valid JSON
    Tool: Bash (jq)
    Steps:
      1. jq . src/test/resources/test-data/alarm-data.json
      2. jq . src/test/resources/test-data/kpi-data.json
    Expected Result: JSON parses successfully, expected record counts
    Evidence: .sisyphus/evidence/task-3-config-valid.txt
  ```

  **Commit**: NO

- [ ] 4. Create TuGraph Init Cypher Scripts

  **What to do**:
  - Create Cypher scripts to initialize TuGraph test data
  - Define vertex labels: NetworkElement, LTP, Person
  - Define edge types: NEHasLtps, HAS_CHILD, BORN_IN
  - Create sample data matching E2E test requirements
  - Store in `src/test/resources/tugraph-init/init.cypher`

  **Must NOT do**:
  - Create more labels than existing E2E tests use
  - Add CREATE/MERGE for virtual labels (Card, KPI, Alarm - these are HTTP-only)
  - Modify production TuGraph schema

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: YES (with T1, T2, T3)
  - **Parallel Group**: Wave 1
  - **Blocks**: Task 17 (E2E migration needs TuGraph data)
  - **Blocked By**: Task 0

  **References**:
  - `e2e/E2ETest.java:71-150` - NetworkElement, LTP test data examples
  - `e2e/E2ETest.java:225-301` - Person, Card test data examples
  - `e2e/E2EGraphEntityFactory.java` - Entity creation helpers

  **Acceptance Criteria**:
  - [ ] Cypher script created: `init.cypher`
  - [ ] Creates NetworkElement vertices with test IDs (ne1, ne2, ne3...)
  - [ ] Creates LTP vertices with test IDs (ltp1, ltp2...)
  - [ ] Creates Person vertices with test IDs (p1, p2, p3...)
  - [ ] Creates edges: NEHasLtps, HAS_CHILD

  **QA Scenarios**:
  ```
  Scenario: Cypher script valid syntax
    Tool: Bash (grep)
    Steps:
      1. grep "CREATE" src/test/resources/tugraph-init/init.cypher
      2. Verify vertex and edge creation statements
    Expected Result: Valid Cypher CREATE statements found
    Evidence: .sisyphus/evidence/task-4-cypher-valid.txt
  ```

  **Commit**: NO

- [ ] 5. Implement TestDataLoader (TDD)

  **What to do**:
  - Write `TestDataLoaderTest.java` FIRST (TDD)
  - Test cases: load from JSON, invalid file handling, missing file handling
  - Implement `TestDataLoader.java` to parse JSON config files
  - Load data into in-memory Map<String, List<Map<String, Object>>>
  - Support loading multiple config files (alarm, kpi, card)

  **Test cases to cover**:
  - Load valid JSON file successfully
  - Parse records into correct data structure
  - Handle missing file (throw clear exception)
  - Handle malformed JSON (throw clear exception)
  - Load multiple files in single call

  **Must NOT do**:
  - Add file watching or hot reload
  - Support YAML format
  - Add caching beyond basic Map

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: `['test-quality', 'clean-code']`
    - `test-quality`: TDD requires high-quality test patterns
    - `clean-code`: Loader should be simple and clean

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on T3 schema)
  - **Parallel Group**: Wave 1 (after T3)
  - **Blocks**: Wave 2 (servers need data loader)
  - **Blocked By**: Task 3

  **References**:
  - `common/JsonUtil.java` - Existing JSON utility
  - `pom.xml` - Jackson dependency (2.17.0)
  - `mockserver/MockDataRepository.java:12-16` - Current data storage pattern

  **Acceptance Criteria**:
  - [ ] TestDataLoaderTest.java created with 5+ test cases
  - [ ] `mvn test -Dtest=TestDataLoaderTest` → All pass
  - [ ] TestDataLoader.java implemented
  - [ ] Loader loads JSON from `src/test/resources/test-data/`

  **QA Scenarios**:
  ```
  Scenario: TestDataLoader loads valid config
    Tool: Bash (mvn)
    Steps:
      1. mvn test -Dtest=TestDataLoaderTest#testLoadValidJson
      2. Verify test passes
    Expected Result: Test PASS, data loaded into Map
    Evidence: .sisyphus/evidence/task-5-loader-test.txt

  Scenario: TestDataLoader handles missing file
    Tool: Bash (mvn)
    Steps:
      1. mvn test -Dtest=TestDataLoaderTest#testMissingFileThrowsException
      2. Verify exception thrown with clear message
    Expected Result: Test PASS, exception with file path in message
    Evidence: .sisyphus/evidence/task-5-loader-error.txt
  ```

  **Commit**: YES
  - Message: `testinfra: implement TestDataLoader with TDD tests`
  - Files: `testinfra/data/TestDataLoader.java`, `testinfra/data/TestDataLoaderTest.java`
  - Pre-commit: `mvn test -Dtest=TestDataLoaderTest`

- [ ] 6. Write HttpServerBaseTest (TDD)

  **What to do**:
  - Write comprehensive unit tests for HTTP server base functionality
  - Test cases: server starts on fixed port, handles GET/POST, returns JSON
  - Test filtering: single filter, multiple filters, AND/OR logic
  - Test sorting: single field ASC/DESC, multiple fields
  - Test pagination: offset/limit, out of bounds handling
  - Test batch endpoint: multiple IDs in single request
  - Store tests in `testinfra/server/HttpServerBaseTest.java`

  **Test cases to cover**:
  - Server starts on port 8080 (base test)
  - GET request with filter returns filtered results
  - GET request with sort returns sorted results
  - GET request with pagination returns paginated results
  - POST request for aggregation returns aggregated results
  - Batch POST request returns batched results
  - Invalid request returns HTTP 400 with error message
  - Timeout configured correctly (30s)

  **Must NOT do**:
  - Add performance tests (keep tests unit-level)
  - Test authentication (not in scope)
  - Use dynamic ports in tests (fixed 8080 for base test)

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `['test-quality']`
    - `test-quality`: High-quality test patterns required

  **Parallelization**:
  - **Can Run In Parallel**: YES (with T9, T10, T11, T12 - but need sequential for T7, T8)
  - **Parallel Group**: Wave 2
  - **Blocks**: Task 7 (implementation needs tests)
  - **Blocked By**: Task 1, Task 5

  **References**:
  - `mockserver/BaseDataHandler.java:28-78` - Current handler pattern
  - `mockserver/BaseDataHandler.java:80-111` - Query param parsing
  - Task 2 API contract - Defined endpoints and formats

  **Acceptance Criteria**:
  - [ ] HttpServerBaseTest.java created with 10+ test cases
  - [ ] Tests cover: GET, POST, filtering, sorting, pagination, batch, error handling
  - [ ] Test uses `com.sun.net.httpserver.HttpServer` like existing MockHttpServer

  **QA Scenarios**:
  ```
  Scenario: HttpServerBaseTest compiles and runs
    Tool: Bash (mvn)
    Steps:
      1. mvn compile -pl graph-query-engine
      2. mvn test -Dtest=HttpServerBaseTest (expect failures - TDD RED)
    Expected Result: Tests compile, initial run shows RED (failing tests)
    Evidence: .sisyphus/evidence/task-6-server-test-red.txt
  ```

  **Commit**: NO (commit with T7 implementation)

- [ ] 7. Implement HttpServerBase with Filtering/Sorting/Pagination

  **What to do**:
  - Implement `HttpServerBase.java` - abstract base class for HTTP servers
  - Implement filtering logic: parse `?filter[field]=value`, support AND/OR
  - Implement sorting logic: parse `?sort=field:ASC,field2:DESC`
  - Implement pagination logic: parse `?offset=N&limit=M`, return paginated slice
  - Use `TestDataLoader` to load data on startup
  - Make data immutable (defensive copies on read)
  - Configure timeout: 30 seconds per request
  - Run T7 tests after implementation to achieve GREEN

  **Must NOT do**:
  - Add database persistence (memory only)
  - Add caching beyond simple Map
  - Add rate limiting
  - Support parallel requests (fixed ports limitation)

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: `['clean-code']`
    - Clean, maintainable server implementation

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on T6 tests)
  - **Parallel Group**: Wave 2 (after T6)
  - **Blocks**: T10, T11, T12 (specific servers extend base)
  - **Blocked By**: Task 6

  **References**:
  - `mockserver/MockHttpServer.java:12-107` - Existing server pattern (HttpServer, ExecutorService)
  - `mockserver/BaseDataHandler.java:28-78` - Handler pattern
  - `mockserver/MockDataRepository.java:90-168` - Query and projection logic
  - Task 5 TestDataLoader - Data loading mechanism

  **Acceptance Criteria**:
  - [ ] HttpServerBase.java implemented
  - [ ] `mvn test -Dtest=HttpServerBaseTest` → All pass (GREEN)
  - [ ] Filtering supports: equality, AND logic (multiple filters)
  - [ ] Sorting supports: ASC/DESC, multiple fields
  - [ ] Pagination supports: offset/limit
  - [ ] Data loaded from TestDataLoader

  **QA Scenarios**:
  ```
  Scenario: HttpServerBase filtering works
    Tool: Bash (curl after server start)
    Steps:
      1. Start HttpServerBase on port 8080 (via test)
      2. curl "http://localhost:8080/test?filter[name]=NE001"
      3. Assert response contains only filtered records
    Expected Result: HTTP 200, filtered JSON array
    Evidence: .sisyphus/evidence/task-7-filter-test.txt

  Scenario: HttpServerBase pagination works
    Tool: Bash (curl)
    Steps:
      1. curl "http://localhost:8080/test?offset=0&limit=10"
      2. Assert response has exactly 10 records (or less)
    Expected Result: HTTP 200, paginated JSON array
    Evidence: .sisyphus/evidence/task-7-pagination-test.txt
  ```

  **Commit**: YES
  - Message: `testinfra: implement HttpServerBase with filtering, sorting, pagination`
  - Files: `testinfra/server/HttpServerBase.java`, `testinfra/server/HttpServerBaseTest.java`
  - Pre-commit: `mvn test -Dtest=HttpServerBaseTest`

- [ ] 8. Implement Aggregation Handler (POST JSON)

  **What to do**:
  - Write `AggregationHandlerTest.java` FIRST (TDD)
  - Test cases: groupBy single field, count aggregation, sum/avg aggregation
  - Implement `AggregationHandler.java` - processes POST `/aggregate` requests
  - Parse POST body JSON: `{"groupBy": "field", "metrics": ["count", "sum(value)"]}`
  - Support aggregations: count, sum, avg, min, max
  - Support groupBy: single field (multi-field groupBy optional)

  **Test cases to cover**:
  - POST with groupBy returns grouped results
  - POST with count metric returns counts per group
  - POST with sum metric returns sums per group
  - POST with avg metric returns averages per group
  - POST with invalid metric returns HTTP 400
  - POST with non-numeric aggregation field returns HTTP 400

  **Must NOT do**:
  - Add complex multi-level aggregations
  - Add percentile or statistical functions
  - Support more than 5 metrics

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: `['test-quality', 'clean-code']`
    - TDD + clean aggregation logic

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on T7 HttpServerBase)
  - **Parallel Group**: Wave 2 (after T7)
  - **Blocks**: None (aggregation is standalone handler)
  - **Blocked By**: Task 7

  **References**:
  - Task 2 API contract - Aggregation POST format
  - `mockserver/BaseDataHandler.java` - Handler pattern to extend

  **Acceptance Criteria**:
  - [ ] AggregationHandlerTest.java created with 6+ test cases
  - [ ] AggregationHandler.java implemented
  - [ ] `mvn test -Dtest=AggregationHandlerTest` → All pass
  - [ ] Supports: groupBy, count, sum, avg

  **QA Scenarios**:
  ```
  Scenario: Aggregation groupBy count works
    Tool: Bash (curl)
    Steps:
      1. curl -X POST "http://localhost:8081/rest/alarm/v1/aggregate"
         -H "Content-Type: application/json"
         -d '{"groupBy": "severity", "metrics": ["count"]}'
      2. Assert response has grouped counts
    Expected Result: HTTP 200, {"severity": {...}, "count": N}
    Evidence: .sisyphus/evidence/task-8-aggregation-test.txt

  Scenario: Aggregation non-numeric field fails
    Tool: Bash (curl)
    Steps:
      1. curl -X POST -d '{"groupBy": "name", "metrics": ["sum(value)"]}'
      2. Assert HTTP 400 with error message
    Expected Result: HTTP 400, {"error": "Cannot aggregate non-numeric field..."}
    Evidence: .sisyphus/evidence/task-8-aggregation-error.txt
  ```

  **Commit**: YES
  - Message: `testinfra: implement aggregation handler with TDD tests`
  - Files: `testinfra/server/AggregationHandler.java`, `testinfra/server/AggregationHandlerTest.java`
  - Pre-commit: `mvn test -Dtest=AggregationHandlerTest`

- [ ] 9. Implement Batch Endpoint Handler

  **What to do**:
  - Write `BatchHandlerTest.java` FIRST (TDD)
  - Test cases: batch with multiple IDs, empty batch, invalid IDs
  - Implement `BatchHandler.java` - processes POST `/batch` requests
  - Parse POST body: `{"ids": ["id1", "id2", "id3"]}`
  - Return all records matching IDs
  - Prevent N+1 pattern (single HTTP call for multiple IDs)

  **Test cases to cover**:
  - Batch with valid IDs returns all matching records
  - Batch with empty IDs returns empty array
  - Batch with non-existent IDs returns empty array
  - Batch request is logged correctly

  **Must NOT do**:
  - Add batch size limits (keep simple)
  - Add batch for DELETE/UPDATE operations (read-only)

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `['test-quality']`

  **Parallelization**:
  - **Can Run In Parallel**: YES (with T6, T10, T11, T12 - batch is standalone)
  - **Parallel Group**: Wave 2
  - **Blocks**: None
  - **Blocked By**: Task 7

  **References**:
  - `MockExternalAdapter.java:103-115` - Batching pattern (getKPIByParentResId with multiple IDs)
  - Task 2 API contract - Batch POST format

  **Acceptance Criteria**:
  - [ ] BatchHandlerTest.java created with 4+ test cases
  - [ ] BatchHandler.java implemented
  - [ ] `mvn test -Dtest=BatchHandlerTest` → All pass
  - [ ] Single HTTP call for multiple IDs (not N+1)

  **QA Scenarios**:
  ```
  Scenario: Batch returns multiple records
    Tool: Bash (curl)
    Steps:
      1. curl -X POST "http://localhost:8081/rest/alarm/v1/batch"
         -H "Content-Type: application/json"
         -d '{"ids": ["id1", "id2", "id3"]}'
      2. Assert response has records for all IDs
    Expected Result: HTTP 200, JSON array with matching records
    Evidence: .sisyphus/evidence/task-9-batch-test.txt
  ```

  **Commit**: YES
  - Message: `testinfra: implement batch endpoint handler`
  - Files: `testinfra/server/BatchHandler.java`, `testinfra/server/BatchHandlerTest.java`
  - Pre-commit: `mvn test -Dtest=BatchHandlerTest`

- [ ] 10. Write AlarmHttpServerTest + Implement AlarmHttpServer

  **What to do**:
  - Write `AlarmHttpServerTest.java` FIRST (TDD)
  - Test cases specific to Alarm data: medn filter, timestamp strategy
  - Implement `AlarmHttpServer.java` - extends HttpServerBase
  - Load alarm data from `alarm-data.json`
  - Register handlers: main query, aggregation, batch
  - Port: 8081
  - Endpoint: `/rest/alarm/v1`

  **Test cases to cover**:
  - Server starts on port 8081
  - Query by medn returns matching alarms
  - Query with timestamp strategy (latest/nearest/ffill)
  - Aggregation by severity works
  - Batch query by CSN works

  **Must NOT do**:
  - Modify alarm data schema (use existing fields)
  - Add new alarm fields beyond existing (CSN, MENAME, MEDN, OCCURTIME, CLEARTIME)

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: `['test-quality']`

  **Parallelization**:
  - **Can Run In Parallel**: YES (with T11, T12 - separate servers)
  - **Parallel Group**: Wave 2
  - **Blocks**: Task 14 (Extension needs AlarmHttpServer)
  - **Blocked By**: Task 7, Task 8, Task 9

  **References**:
  - `mockserver/AlarmDataHandler.java:10-39` - Current Alarm handler
  - `mockserver/MockDataRepository.java:18-42` - Alarm data structure
  - `src/test/resources/test-data/alarm-data.json` - Alarm test data (Task 3)

  **Acceptance Criteria**:
  - [ ] AlarmHttpServerTest.java created with 5+ test cases
  - [ ] AlarmHttpServer.java implemented on port 8081
  - [ ] `mvn test -Dtest=AlarmHttpServerTest` → All pass
  - [ ] Endpoint `/rest/alarm/v1` responds correctly

  **QA Scenarios**:
  ```
  Scenario: AlarmHttpServer responds to query
    Tool: Bash (curl)
    Steps:
      1. Start AlarmHttpServer on port 8081 (via test)
      2. curl "http://localhost:8081/rest/alarm/v1?medn=388581df-..."
      3. Assert alarm records returned
    Expected Result: HTTP 200, JSON with alarm data
    Evidence: .sisyphus/evidence/task-10-alarm-server.txt
  ```

  **Commit**: YES
  - Message: `testinfra: implement AlarmHttpServer on port 8081`
  - Files: `testinfra/server/AlarmHttpServer.java`, `testinfra/server/AlarmHttpServerTest.java`
  - Pre-commit: `mvn test -Dtest=AlarmHttpServerTest`

- [ ] 11. Write KpiHttpServerTest + Implement KpiHttpServer

  **What to do**:
  - Write `KpiHttpServerTest.java` FIRST (TDD)
  - Test cases specific to KPI data: parentResId filter, time filter
  - Implement `KpiHttpServer.java` - extends HttpServerBase
  - Load KPI data from `kpi-data.json` and `kpi2-data.json`
  - Port: 8082
  - Endpoint: `/rest/kpi/v1`

  **Test cases to cover**:
  - Server starts on port 8082
  - Query by parentResId returns matching KPIs
  - Query with timestamp strategy
  - Aggregation by name works
  - Batch query by resId works

  **Must NOT do**:
  - Combine KPI and KPI2 differently than current MockDataRepository

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: `['test-quality']`

  **Parallelization**:
  - **Can Run In Parallel**: YES (with T10, T12)
  - **Parallel Group**: Wave 2
  - **Blocks**: Task 14
  - **Blocked By**: Task 7, Task 8, Task 9

  **References**:
  - `mockserver/KpiDataHandler.java:10-39` - Current KPI handler
  - `mockserver/MockDataRepository.java:44-88` - KPI data structure
  - `src/test/resources/test-data/kpi-data.json`, `kpi2-data.json` - KPI test data

  **Acceptance Criteria**:
  - [ ] KpiHttpServerTest.java created with 5+ test cases
  - [ ] KpiHttpServer.java implemented on port 8082
  - [ ] `mvn test -Dtest=KpiHttpServerTest` → All pass
  - [ ] Endpoint `/rest/kpi/v1` responds correctly

  **QA Scenarios**:
  ```
  Scenario: KpiHttpServer responds to query
    Tool: Bash (curl)
    Steps:
      1. curl "http://localhost:8082/rest/kpi/v1?parentResId=eccc2c94-..."
      2. Assert KPI records returned
    Expected Result: HTTP 200, JSON with KPI data
    Evidence: .sisyphus/evidence/task-11-kpi-server.txt
  ```

  **Commit**: YES
  - Message: `testinfra: implement KpiHttpServer on port 8082`
  - Files: `testinfra/server/KpiHttpServer.java`, `testinfra/server/KpiHttpServerTest.java`
  - Pre-commit: `mvn test -Dtest=KpiHttpServerTest`

- [ ] 12. Write CardHttpServerTest + Implement CardHttpServer

  **What to do**:
  - Write `CardHttpServerTest.java` FIRST (TDD)
  - Test cases specific to Card data: name filter, resId filter
  - Implement `CardHttpServer.java` - extends HttpServerBase
  - Load Card data from `card-data.json`
  - Port: 8083
  - Endpoint: `/rest/card/v1`

  **Test cases to cover**:
  - Server starts on port 8083
  - Query by name returns matching cards
  - Query by resId returns matching cards
  - Batch query works

  **Must NOT do**:
  - Add Card-specific fields beyond existing (name, resId, status)

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: `['test-quality']`

  **Parallelization**:
  - **Can Run In Parallel**: YES (with T10, T11)
  - **Parallel Group**: Wave 2
  - **Blocks**: Task 14
  - **Blocked By**: Task 7, Task 8, Task 9

  **References**:
  - `e2e/E2ETest.java:785-813` - Card test data examples
  - `e2e/E2EGraphEntityFactory.java:createCardEntity` - Card entity creation
  - Task 3 `card-data.json` - Card test data

  **Acceptance Criteria**:
  - [ ] CardHttpServerTest.java created with 4+ test cases
  - [ ] CardHttpServer.java implemented on port 8083
  - [ ] `mvn test -Dtest=CardHttpServerTest` → All pass
  - [ ] Endpoint `/rest/card/v1` responds correctly

  **QA Scenarios**:
  ```
  Scenario: CardHttpServer responds to query
    Tool: Bash (curl)
    Steps:
      1. curl "http://localhost:8083/rest/card/v1?name=card001"
      2. Assert card records returned
    Expected Result: HTTP 200, JSON with card data
    Evidence: .sisyphus/evidence/task-12-card-server.txt
  ```

  **Commit**: YES
  - Message: `testinfra: implement CardHttpServer on port 8083`
  - Files: `testinfra/server/CardHttpServer.java`, `testinfra/server/CardHttpServerTest.java`
  - Pre-commit: `mvn test -Dtest=CardHttpServerTest`

- [ ] 13. Write HttpServerExtensionTest

  **What to do**:
  - Write tests for JUnit 5 Extension lifecycle management
  - Test cases: start all servers before tests, stop all servers after tests
  - Test port conflict handling (port already in use)
  - Test TuGraph connectivity validation (optional)
  - Store in `testinfra/extension/HttpServerExtensionTest.java`

  **Test cases to cover**:
  - Extension starts AlarmHttpServer, KpiHttpServer, CardHttpServer
  - Extension stops all servers on test completion
  - Extension fails fast if port is already in use
  - Extension provides server URLs to tests

  **Must NOT do**:
  - Add TuGraph startup management (external)
  - Add parallel test execution support

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `['test-quality']`

  **Parallelization**:
  - **Can Run In Parallel**: YES (with T15)
  - **Parallel Group**: Wave 3
  - **Blocks**: Task 14 (implementation needs tests)
  - **Blocked By**: Task 10, Task 11, Task 12

  **References**:
  - JUnit 5 `BeforeAllCallback`, `AfterAllCallback` - Extension lifecycle
  - `mockserver/MockHttpServer.java:34-54` - Server start/stop pattern

  **Acceptance Criteria**:
  - [ ] HttpServerExtensionTest.java created with 4+ test cases
  - [ ] Tests use JUnit 5 Extension pattern

  **QA Scenarios**:
  ```
  Scenario: Extension test compiles
    Tool: Bash (mvn)
    Steps:
      1. mvn compile -pl graph-query-engine
      2. mvn test -Dtest=HttpServerExtensionTest (expect RED)
    Expected Result: Tests compile, initial run shows RED
    Evidence: .sisyphus/evidence/task-13-extension-test-red.txt
  ```

  **Commit**: NO

- [ ] 14. Implement HttpServerExtension (Start/Stop Multi-Servers)

  **What to do**:
  - Implement `HttpServerExtension.java` - JUnit 5 Extension
  - Implement `BeforeAllCallback`: start Alarm(8081), KPI(8082), Card(8083) servers
  - Implement `AfterAllCallback`: stop all servers
  - Store server URLs in Extension context for test access
  - Fail fast with clear error if port conflict
  - Configure timeout: 30s for HTTP requests
  - Run T13 tests after implementation to achieve GREEN

  **Must NOT do**:
  - Add TuGraph initialization (external)
  - Add parallel execution support
  - Start servers per-test (@BeforeEach) - use @BeforeAll (once per suite)

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: `['clean-code']`

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on T13)
  - **Parallel Group**: Wave 3 (after T13)
  - **Blocks**: Task 17, Task 18
  - **Blocked By**: Task 13

  **References**:
  - JUnit 5 Extension API documentation
  - `mockserver/MockHttpServer.java:34-54` - Server start/stop
  - `mockserver/MockHttpServer.java:72-106` - Main method (shutdown hook)

  **Acceptance Criteria**:
  - [ ] HttpServerExtension.java implemented
  - [ ] `mvn test -Dtest=HttpServerExtensionTest` → All pass
  - [ ] Starts servers: Alarm(8081), KPI(8082), Card(8083)
  - [ ] Stops servers after tests
  - [ ] Clear error on port conflict

  **QA Scenarios**:
  ```
  Scenario: Extension starts servers
    Tool: Bash (curl + mvn)
    Steps:
      1. mvn test -Dtest=*Test that uses Extension
      2. curl http://localhost:8081/rest/alarm/v1 (during test)
      3. Assert server responds
    Expected Result: HTTP 200 during test run
    Evidence: .sisyphus/evidence/task-14-extension-start.txt

  Scenario: Extension stops servers after test
    Tool: Bash (curl)
    Steps:
      1. mvn test -Dtest=*Test
      2. After test completes, curl http://localhost:8081/rest/alarm/v1
      3. Assert connection refused (server stopped)
    Expected Result: Connection refused after test
    Evidence: .sisyphus/evidence/task-14-extension-stop.txt
  ```

  **Commit**: YES
  - Message: `testinfra: implement JUnit Extension for HTTP server lifecycle`
  - Files: `testinfra/extension/HttpServerExtension.java`, `testinfra/extension/HttpServerExtensionTest.java`
  - Pre-commit: `mvn test -Dtest=HttpServerExtensionTest`

- [ ] 15. Write HttpExternalAdapterTest (TDD)

  **What to do**:
  - Write comprehensive tests for HTTP adapter
  - Test cases: execute query via HTTP, handle timeout, handle 500 error
  - Test batching: single request for multiple IDs
  - Test filtering: HTTP adapter sends filter params correctly
  - Store in `testinfra/adapter/HttpExternalAdapterTest.java`

  **Test cases to cover**:
  - Execute query returns GraphEntity list from HTTP response
  - Batch query sends single POST /batch request
  - Timeout (30s) causes immediate failure
  - HTTP 500 error causes immediate failure
  - Empty result returns empty list (not error)
  - Filtering sends correct query parameters

  **Must NOT do**:
  - Add retry logic
  - Add circuit breaker

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: `['test-quality']`
    - Comprehensive adapter tests

  **Parallelization**:
  - **Can Run In Parallel**: YES (with T13)
  - **Parallel Group**: Wave 3
  - **Blocks**: Task 16
  - **Blocked By**: Task 0.1 (interface review)

  **References**:
  - `adapter/MockExternalAdapter.java:51-120` - Execute method pattern
  - `adapter/MockExternalAdapter.java:122-180` - Filtering logic
  - `adapter/MockExternalAdapter.java:248-296` - executeExternalQuery method
  - Task 0.1 interface review document

  **Acceptance Criteria**:
  - [ ] HttpExternalAdapterTest.java created with 6+ test cases
  - [ ] Tests cover: execute, batch, timeout, error, empty, filter

  **QA Scenarios**:
  ```
  Scenario: Adapter test compiles
    Tool: Bash (mvn)
    Steps:
      1. mvn compile -pl graph-query-engine
      2. mvn test -Dtest=HttpExternalAdapterTest (expect RED)
    Expected Result: Tests compile, RED state
    Evidence: .sisyphus/evidence/task-15-adapter-test-red.txt
  ```

  **Commit**: NO

- [ ] 16. Implement HttpExternalAdapter

  **What to do**:
  - Implement `HttpExternalAdapter.java` - replaces MockExternalAdapter
  - Use Java HttpClient (java.net.http.HttpClient) for HTTP calls
  - Implement all methods from MockExternalAdapter interface
  - Timeout: 30 seconds per request
  - Error handling: immediate failure on timeout or HTTP 500
  - Batching: use POST /batch endpoint for multiple IDs
  - Parse HTTP JSON response into GraphEntity list
  - Run T15 tests after implementation to achieve GREEN

  **Interface methods to implement** (from Task 0.1):
  - `execute(ExternalQuery query)`: CompletableFuture<QueryResult>
  - `executeSync(ExternalQuery query)`: QueryResult
  - `executeTuGraphQuery(String cypher)`: List<Record>
  - `executeExternalQuery(DataSourceQueryParams params)`: List<Map<String,Object>>
  - `isHealthy()`: boolean
  - `getDataSourceType()`: String
  - `getDataSourceName()`: String

  **Must NOT do**:
  - Add retry logic
  - Add caching beyond simple Map
  - Change method signatures (drop-in replacement)

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
  - **Skills**: `['clean-code']`

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on T15)
  - **Parallel Group**: Wave 3 (after T15)
  - **Blocks**: Task 17, Task 18
  - **Blocked By**: Task 15

  **References**:
  - `adapter/MockExternalAdapter.java:1-577` - Complete adapter implementation
  - `datasource/DataSourceAdapter.java` - Interface to implement
  - Java HttpClient API (java.net.http)
  - Task 14 HttpServerExtension - Server URLs

  **Acceptance Criteria**:
  - [ ] HttpExternalAdapter.java implemented
  - [ ] `mvn test -Dtest=HttpExternalAdapterTest` → All pass
  - [ ] Implements all MockExternalAdapter public methods
  - [ ] Uses HTTP for all external queries
  - [ ] Timeout: 30s, immediate failure on error

  **QA Scenarios**:
  ```
  Scenario: Adapter executes HTTP query
    Tool: Bash (mvn + curl)
    Steps:
      1. Start mock HTTP server (via Extension)
      2. mvn test -Dtest=HttpExternalAdapterTest#testExecuteQuery
      3. Verify HTTP request made (server logs)
    Expected Result: Test PASS, HTTP request logged
    Evidence: .sisyphus/evidence/task-16-adapter-http.txt

  Scenario: Adapter handles timeout
    Tool: Bash (mvn)
    Steps:
      1. mvn test -Dtest=HttpExternalAdapterTest#testTimeout
      2. Verify immediate failure
    Expected Result: Test PASS, exception thrown
    Evidence: .sisyphus/evidence/task-16-adapter-timeout.txt
  ```

  **Commit**: YES
  - Message: `testinfra: implement HttpExternalAdapter with real HTTP calls`
  - Files: `testinfra/adapter/HttpExternalAdapter.java`, `testinfra/adapter/HttpExternalAdapterTest.java`
  - Pre-commit: `mvn test -Dtest=HttpExternalAdapterTest`

- [ ] 17. Update GraphQueryMetaFactory for Real HTTP

  **What to do**:
  - Update `testutil/GraphQueryMetaFactory.java` to use HttpExternalAdapter
  - Replace MockExternalAdapter creation with HttpExternalAdapter
  - Configure HttpExternalAdapter with server URLs from HttpServerExtension
  - Update data source URLs: `http://localhost:8081`, `http://localhost:8082`, `http://localhost:8083`
  - Ensure factory can be used by E2E tests with @ExtendWith(HttpServerExtension.class)

  **Must NOT do**:
  - Remove MockExternalAdapter entirely yet (keep for comparison)
  - Change factory interface

  **Recommended Agent Profile**:
  - **Category**: `quick`
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on T16)
  - **Parallel Group**: Wave 4
  - **Blocks**: Task 18
  - **Blocked By**: Task 14, Task 16

  **References**:
  - `testutil/GraphQueryMetaFactory.java` - Current factory (creates MockExternalAdapter)
  - `testutil/GraphQueryMetaFactory.java:61-65` - createAdapter method
  - `e2e/E2ETest.java:32-66` - Factory usage pattern

  **Acceptance Criteria**:
  - [ ] GraphQueryMetaFactory updated to create HttpExternalAdapter
  - [ ] URLs configured: Alarm(8081), KPI(8082), Card(8083)
  - [ ] Factory works with HttpServerExtension

  **QA Scenarios**:
  ```
  Scenario: Factory creates HttpExternalAdapter
    Tool: Bash (grep)
    Steps:
      1. grep "HttpExternalAdapter" testutil/GraphQueryMetaFactory.java
      2. Verify import and usage
    Expected Result: HttpExternalAdapter found in factory
    Evidence: .sisyphus/evidence/task-17-factory-update.txt
  ```

  **Commit**: YES
  - Message: `testinfra: update GraphQueryMetaFactory for real HTTP`
  - Files: `testutil/GraphQueryMetaFactory.java`
  - Pre-commit: `mvn compile`

- [ ] 18. Migrate E2E Tests One by One (Validate Each)

  **What to do**:
  - Add `@ExtendWith(HttpServerExtension.class)` to E2ETest class
  - Migrate each E2E test method one at a time
  - For each test:
    1. Run test, compare result with baseline (Task 0)
    2. Fix any discrepancies
    3. Verify test passes
  - Start with simplest tests (single entity queries)
  - Progress to complex tests (multi-hop paths, UNION)
  - Track migration progress: completed/total

  **Test migration order** (recommended):
  1. `simpleMatchQuery()` - Simple entity query
  2. `simpleMatchQueryWithExecute()` - Basic SDK execution
  3. `whereClauseQuery()` - WHERE condition
  4. `orderByLimitQuery()` - ORDER BY + LIMIT
  5. `example4_PureExternalSourceQuery()` - Pure external
  6. `example5_ExternalToInternalQuery()` - External to internal
  7. Progress to remaining 16 tests...

  **Must NOT do**:
  - Skip failing tests (fix each one)
  - Change test assertions
  - Migrate all at once (validate each)

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: `['test-quality']`
    - Careful validation required

  **Parallelization**:
  - **Can Run In Parallel**: NO (sequential validation)
  - **Parallel Group**: Wave 4
  - **Blocks**: Task 19
  - **Blocked By**: Task 17

  **References**:
  - `e2e/E2ETest.java` - All 22 test methods
  - `.sisyphus/baseline/e2e-baseline.json` - Baseline results (Task 0)
  - Task 14 HttpServerExtension - Annotation to add

  **Acceptance Criteria**:
  - [ ] E2ETest class annotated with `@ExtendWith(HttpServerExtension.class)`
  - [ ] Each test passes with identical results to baseline
  - [ ] All 22 tests migrated and validated
  - [ ] Migration progress tracked

  **QA Scenarios**:
  ```
  Scenario: First test passes after migration
    Tool: Bash (mvn)
    Steps:
      1. mvn test -Dtest=E2ETest#simpleMatchQuery
      2. Compare result with baseline
    Expected Result: Test PASS, result matches baseline
    Evidence: .sisyphus/evidence/task-18-first-test.txt

  Scenario: All tests pass after full migration
    Tool: Bash (mvn)
    Steps:
      1. mvn test -Dtest=E2ETest
      2. Assert: Tests run: 22, Failures: 0
    Expected Result: All 22 tests pass
    Evidence: .sisyphus/evidence/task-18-all-tests.txt
  ```

  **Commit**: YES (per batch of 5 tests)
  - Message: `testinfra: migrate E2E tests to real HTTP (batch 1)`
  - Files: `e2e/E2ETest.java`
  - Pre-commit: `mvn test -Dtest=E2ETest`

- [ ] 19. Run Full E2E Suite Validation

  **What to do**:
  - Run complete E2E test suite: `mvn test -Dtest=E2ETest`
  - Verify all 22 tests pass with identical results to baseline
  - Run multiple times (3x) to ensure stability (no flaky tests)
  - Compare execution time with baseline (Task 0)
  - Document final test pass rate and execution time
  - Clean up MockExternalAdapter if all tests pass (delete or deprecate)

  **Must NOT do**:
  - Accept any test failures
  - Skip flaky tests (must fix or mark as known issue)

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on T18)
  - **Parallel Group**: Wave 4
  - **Blocks**: Wave 5, Wave FINAL
  - **Blocked By**: Task 18

  **References**:
  - `e2e/E2ETest.java` - All 22 tests
  - `.sisyphus/baseline/e2e-baseline.json` - Baseline for comparison
  - `adapter/MockExternalAdapter.java` - To clean up if all pass

  **Acceptance Criteria**:
  - [ ] `mvn test -Dtest=E2ETest` → Tests run: 22, Failures: 0, Errors: 0
  - [ ] Test run 3x → All pass each time (no flaky tests)
  - [ ] Results match baseline JSON structure
  - [ ] Execution time documented (target: <60s)
  - [ ] MockExternalAdapter deprecated or deleted

  **QA Scenarios**:
  ```
  Scenario: Full suite passes 3 times
    Tool: Bash (mvn repeat)
    Steps:
      1. mvn test -Dtest=E2ETest
      2. mvn test -Dtest=E2ETest
      3. mvn test -Dtest=E2ETest
      4. Assert: All 3 runs have 0 failures
    Expected Result: 3 consecutive successful runs
    Evidence: .sisyphus/evidence/task-19-stability.txt

  Scenario: Execution time acceptable
    Tool: Bash (time)
    Steps:
      1. time mvn test -Dtest=E2ETest
      2. Assert: <60 seconds total
    Expected Result: Total time <60s
    Evidence: .sisyphus/evidence/task-19-performance.txt
  ```

  **Commit**: YES
  - Message: `testinfra: complete E2E migration, deprecate MockExternalAdapter`
  - Files: `e2e/E2ETest.java`, `adapter/MockExternalAdapter.java` (deprecate annotation)
  - Pre-commit: `mvn test -Dtest=E2ETest`

- [ ] 20. Update AGENTS.md Test Architecture Section

  **What to do**:
  - Update AGENTS.md with new test architecture section
  - Document HTTP Server ports: Alarm(8081), KPI(8082), Card(8083)
  - Document test data config files location: `src/test/resources/test-data/`
  - Document TuGraph init scripts: `src/test/resources/tugraph-init/`
  - Document HttpServerExtension usage
  - Document fixed ports limitation (sequential execution only)

  **Must NOT do**:
  - Remove existing AGENTS.md content (add new section)
  - Add documentation for production features

  **Recommended Agent Profile**:
  - **Category**: `writing`
  - **Skills**: `['writing-clearly-and-concisely']`
    - Clear documentation

  **Parallelization**:
  - **Can Run In Parallel**: YES (with T21, T22)
  - **Parallel Group**: Wave 5
  - **Blocks**: Task 23
  - **Blocked By**: Task 19

  **References**:
  - `AGENTS.md` - Current project documentation
  - Task 14 HttpServerExtension - To document
  - Task 5 TestDataLoader - To document

  **Acceptance Criteria**:
  - [ ] AGENTS.md updated with "Test Infrastructure" section
  - [ ] Ports documented: 8081-8083
  - [ ] Config file locations documented
  - [ ] HttpServerExtension usage documented

  **QA Scenarios**:
  ```
  Scenario: AGENTS.md has test section
    Tool: Bash (grep)
    Steps:
      1. grep "HTTP Server" AGENTS.md
      2. grep "8081" AGENTS.md
    Expected Result: Documentation found
    Evidence: .sisyphus/evidence/task-20-agents-update.txt
  ```

  **Commit**: YES
  - Message: `docs: update AGENTS.md with test infrastructure architecture`
  - Files: `AGENTS.md`
  - Pre-commit: `mvn compile`

- [ ] 21. Create Test Infrastructure README

  **What to do**:
  - Create `src/test/java/.../testinfra/README.md`
  - Explain how to run tests: prerequisites (TuGraph must be running)
  - Explain HTTP Server architecture
  - Explain how to add new test data (add JSON config file)
  - Explain how to add new HTTP Server (extend HttpServerBase)
  - Troubleshooting section: port conflicts, TuGraph connection

  **Must NOT do**:
  - Duplicate content from AGENTS.md (keep focused)

  **Recommended Agent Profile**:
  - **Category**: `writing`
  - **Skills**: `['writing-clearly-and-concisely', 'crafting-effective-readmes']`
    - README writing best practices

  **Parallelization**:
  - **Can Run In Parallel**: YES (with T20, T22)
  - **Parallel Group**: Wave 5
  - **Blocks**: Task 23
  - **Blocked By**: Task 19

  **References**:
  - Task 14 HttpServerExtension - Usage to document
  - Task 5 TestDataLoader - Data loading to document
  - Metis review - Troubleshooting scenarios (port conflicts, TuGraph)

  **Acceptance Criteria**:
  - [ ] README.md created in testinfra/
  - [ ] Prerequisites documented: TuGraph, fixed ports
  - [ ] How to run tests documented
  - [ ] How to add test data documented
  - [ ] Troubleshooting section: port conflicts, timeout, TuGraph

  **QA Scenarios**:
  ```
  Scenario: README exists and readable
    Tool: Bash (ls + head)
    Steps:
      1. ls src/test/java/.../testinfra/README.md
      2. head -30 README.md
    Expected Result: README exists, content readable
    Evidence: .sisyphus/evidence/task-21-readme.txt
  ```

  **Commit**: YES
  - Message: `docs: create test infrastructure README`
  - Files: `testinfra/README.md`
  - Pre-commit: `mvn compile`

- [ ] 22. Document HTTP Server API

  **What to do**:
  - Create `testinfra/API.md` documenting HTTP endpoints
  - Document each endpoint: path, method, parameters, response format
  - Document filtering: `?filter[field]=value`
  - Document sorting: `?sort=field:ASC`
  - Document pagination: `?offset=N&limit=M`
  - Document aggregation: POST `/aggregate` body format
  - Document batch: POST `/batch` body format
  - Include examples for each operation

  **Must NOT do**:
  - Add authentication documentation (not in scope)

  **Recommended Agent Profile**:
  - **Category**: `writing`
  - **Skills**: `['writing-clearly-and-concisely']`

  **Parallelization**:
  - **Can Run In Parallel**: YES (with T20, T21)
  - **Parallel Group**: Wave 5
  - **Blocks**: Task 23
  - **Blocked By**: Task 19

  **References**:
  - Task 2 API contract - Defined formats
  - Task 7-9 Handlers - Implementation details

  **Acceptance Criteria**:
  - [ ] API.md created with all endpoint documentation
  - [ ] Examples included for each operation
  - [ ] Response formats documented

  **QA Scenarios**:
  ```
  Scenario: API documentation exists
    Tool: Bash (ls)
    Steps:
      1. ls testinfra/API.md
      2. grep "GET /rest/alarm" API.md
    Expected Result: API.md exists with endpoint docs
    Evidence: .sisyphus/evidence/task-22-api-doc.txt
  ```

  **Commit**: YES
  - Message: `docs: document HTTP Server API endpoints`
  - Files: `testinfra/API.md`
  - Pre-commit: `mvn compile`

- [ ] 23. Final Verification - Run All Acceptance Criteria

  **What to do**:
  - Run all verification commands from Success Criteria
  - Execute baseline comparison: E2E results vs original baseline
  - Run performance test: measure total execution time
  - Run stability test: 5 consecutive runs, all pass
  - Generate final report: pass/fail counts, performance metrics
  - Save final evidence to `.sisyphus/evidence/final/`

  **Verification commands to run**:
  ```bash
  mvn test -Dtest=E2ETest  # All 22 tests pass
  mvn test                 # All tests (including new TDD tests)
  time mvn test -Dtest=E2ETest  # Performance <60s
  curl tests for each HTTP endpoint  # Functional validation
  ```

  **Must NOT do**:
  - Skip any verification command
  - Accept partial pass (all must pass)

  **Recommended Agent Profile**:
  - **Category**: `deep`
  - **Skills**: `[]`

  **Parallelization**:
  - **Can Run In Parallel**: NO (depends on T20-22)
  - **Parallel Group**: Wave 5
  - **Blocks**: Wave FINAL
  - **Blocked By**: Task 20, Task 21, Task 22

  **References**:
  - Success Criteria section - All verification commands
  - `.sisyphus/baseline/e2e-baseline.json` - Baseline for comparison

  **Acceptance Criteria**:
  - [ ] All verification commands executed
  - [ ] E2E tests: 22 run, 0 failures
  - [ ] All tests: all pass (including TDD)
  - [ ] Performance: <60s total
  - [ ] Final report generated

  **QA Scenarios**:
  ```
  Scenario: Final verification complete
    Tool: Bash (mvn + curl)
    Steps:
      1. mvn test -Dtest=E2ETest
      2. mvn test
      3. time mvn test -Dtest=E2ETest
      4. curl all HTTP endpoints
    Expected Result: All pass, performance <60s
    Evidence: .sisyphus/evidence/final/verification-report.txt
  ```

  **Commit**: YES
  - Message: `testinfra: final verification complete`
  - Files: `.sisyphus/evidence/final/*`
  - Pre-commit: `mvn test`

---

## Final Verification Wave (MANDATORY — after ALL implementation tasks)

- [ ] F1. **Plan Compliance Audit** — `oracle`
  Verify each "Must Have" is implemented, each "Must NOT Have" is absent.
  Run evidence verification, compare deliverables against plan.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | Tasks [N/N] | VERDICT: APPROVE/REJECT`

- [ ] F2. **Code Quality Review** — `unspecified-high`
  Run `mvn compile` + `mvn test`. Review all new files for code quality.
  Check for hardcoded values, missing error handling, unused imports.
  Output: `Build [PASS/FAIL] | Tests [N/N] | Files [N clean/N issues] | VERDICT`

- [ ] F3. **Real E2E QA** — `unspecified-high`
  Start TuGraph + HTTP servers, run full E2E suite.
  Test edge cases: timeout, empty results, invalid filters.
  Save evidence to `.sisyphus/evidence/final-qa/`.
  Output: `Scenarios [N/N pass] | Edge Cases [N tested] | VERDICT`

- [ ] F4. **Scope Fidelity Check** — `deep`
  Compare plan spec vs actual implementation.
  Verify no scope creep (no auth, no persistence, no extra protocols).
  Output: `Tasks [N/N compliant] | Unaccounted [CLEAN/N files] | VERDICT`

---

## Commit Strategy

- **Wave 0-1**: `testinfra: setup test infrastructure foundation` — testinfra/, test-data/
- **Wave 2**: `testinfra: implement HTTP servers with rich query support` — HttpServerBase, handlers
- **Wave 3**: `testinfra: implement JUnit Extension and HTTP adapter` — HttpServerExtension, HttpExternalAdapter
- **Wave 4**: `testinfra: migrate E2E tests to real HTTP calls` — E2ETest.java, GraphQueryMetaFactory
- **Wave 5**: `docs: update test architecture documentation` — AGENTS.md, README

---

## Success Criteria

### Verification Commands
```bash
# Baseline verification (Wave 0)
mvn test -Dtest=E2ETest
# Expected: Tests run: 22, Failures: 0, Errors: 0, Skipped: 0

# HTTP Server startup verification
curl -s http://localhost:8081/rest/alarm/v1?medn=test-id
# Expected: HTTP 200, JSON array response

# Aggregation verification
curl -s -X POST http://localhost:8081/rest/alarm/v1/aggregate \
  -H "Content-Type: application/json" \
  -d '{"groupBy": "severity", "metrics": ["count"]}'
# Expected: HTTP 200, JSON with aggregation results

# Full test suite verification
mvn test
# Expected: All tests pass, including new TDD tests

# Performance verification
time mvn test -Dtest=E2ETest
# Expected: <60 seconds total
```

### Final Checklist
- [ ] All "Must Have" features implemented
- [ ] All "Must NOT Have" features absent
- [ ] All 22 E2E tests pass with identical results
- [ ] All TDD unit tests pass (>80% coverage)
- [ ] HTTP servers start/stop correctly
- [ ] Test data loads from config files
- [ ] TuGraph initialized via Cypher scripts
- [ ] Documentation updated