# AGENTS.md — GraphQueryEngine

## Project

Java 17 Maven single-module SDK. Federated Cypher query engine: parses Cypher via ANTLR4, splits into physical (TuGraph) + external data-source queries, aggregates results in memory, returns JSON.

## Commands

```bash
mvn compile    # MUST run first — generates ANTLR sources into target/generated-sources/
mvn test       # 52 tests across 5 classes (ParserTest, RewriterTest, ExecutorTest, AggregatorTest, E2ETest)
mvn package    # Build JAR
mvn install    # Install to local repo
```

> **Critical**: `mvn compile` must run before IDE or agents can resolve `com.federatedquery.grammar.*` (generated from `Lcypher.g4`). Without it, imports of `LcypherLexer`/`LcypherParser` will appear broken.

## Architecture (Pipeline)

```
Cypher → CypherParserFacade (ANTLR4 + Caffeine cache) → AST (Program)
       → QueryRewriter (VirtualEdgeDetector + MetadataRegistry) → ExecutionPlan
       → FederatedExecutor (parallel via ExecutorService, batched external queries)
       → ResultStitcher → GlobalSorter → UnionDeduplicator → JSON
```

### Key Entry Points

| File | Role |
|------|------|
| `src/main/java/.../sdk/GraphQuerySDK.java` | Public API — `execute(cypher)` and `executeRaw(cypher)` |
| `src/main/java/.../parser/CypherParserFacade.java` | ANTLR4 parse + MD5-based plan cache (max 1000, 1h TTL) |
| `src/main/java/.../rewriter/QueryRewriter.java` | AST → ExecutionPlan, splits virtual/physical queries |
| `src/main/java/.../executor/FederatedExecutor.java` | Parallel execution, fixed thread pool (10), batching |
| `src/main/java/.../metadata/MetadataRegistry.java` | Registers data sources, virtual edges, labels |

### ANTLR4 Grammar

- Source: `src/main/antlr4/com/federatedquery/grammar/Lcypher.g4`
- Derived from openCypher, extended for TuGraph: `USING SNAPSHOT`, `PROJECT BY`, multi-rel types (`|`)
- Generated output: `${project.build.directory}/generated-sources/antlr4/com/federatedquery/grammar/`
- Visitor + listener both enabled

## Core Constraints (Hard Rules)

1. **Virtual edge/node boundary**: Only allowed at **first hop** or **last hop** of a path. No `[physical]→[virtual]→[physical]` sandwich structures. Enforced by `VirtualEdgeConstraintException`.
2. **Read-only**: No `CREATE`, `MERGE`, `DELETE`, `SET`, `REMOVE`. Only `MATCH`, `RETURN`, `WITH`, `WHERE`, `ORDER BY`, `LIMIT`, `SKIP`, `UNION`.
3. **WHERE pushdown**: Conditions on virtual nodes must NOT be pushed to TuGraph — they become Java-level filters for external queries.
4. **Global sort/limit**: `ORDER BY` and `LIMIT` are stripped from physical queries, applied in-memory after aggregation.
5. **No N+1**: External queries are batched via `BatchingStrategy`. Never loop-call external APIs.

## Metadata-Driven Rules

- **No hard-coded graph semantics**: `label` / `edgeType` / schema / property names / id join fields must not be hard-coded in business logic. They must be resolved from `MetadataRegistry`, `LabelMetadata`, and `VirtualEdgeBinding`.
- **Virtual edge target resolution**: Whether an edge is virtual, its target label, target data source, and join-field mapping must come from metadata lookup. Do not maintain switch/if hard-code tables inside `GraphQuerySDK`, `QueryRewriter`, executor, or tests.
- **Schema access rule**: Label identity field, required properties, property mapping, and virtual-edge `idMapping` are the single source of truth for query rewrite, stitching, filtering, and path reconstruction.
- **Test setup rule**: Any new label / virtual edge used in UT or E2E must register complete metadata first; test assertions must validate behavior through registered metadata, not hidden hard-coded assumptions in production code.

## Testing

### Mock Data Sources

**Unit tests use Mock data sources**, including:
- **TuGraph** (physical graph database) - mocked via `MockExternalAdapter`
- **External services** (REST API, gRPC, custom adapters) - mocked via `MockExternalAdapter`

The `MockExternalAdapter` (`src/test/java/.../adapter/MockExternalAdapter.java`) simulates all data source behaviors:
- Supports configurable responses per operator
- Simulates delays and errors for reliability testing
- No real database or external service connections required

### Real Database Integration Tests

**Integration tests support real TuGraph database connections**:
- **TuGraphConnector** (`src/main/java/.../connector/TuGraphConnectorImpl.java`) provides Bolt protocol connection
- **Default configuration**: `bolt://127.0.0.1:7687`, username: `admin`, password: `73@TuGraph`
- Tests are automatically skipped if TuGraph is not available
- Located in `src/test/java/.../connector/TuGraphConnectorTest.java` and `src/test/java/.../e2e/TuGraphRealDatabaseE2ETest.java`

### Test Setup Pattern

1. Create `MetadataRegistryImpl`
2. Register data sources + virtual edges + labels
3. Register mock adapters on `FederatedExecutor` (for unit tests)
4. Or use `TuGraphConnector` for real database integration tests
5. Wire all components into `GraphQuerySDK`

### Test Coverage

- `E2ETest` — 5 query scenarios with full pipeline (mocked)
- `TuGraphRealDatabaseE2ETest` — Real database integration tests
- `TuGraphConnectorTest` — TuGraph connector unit/integration tests
- `ParserTest` — ANTLR4 parsing
- `RewriterTest` — Query rewriting
- `ExecutorTest` — Federated execution
- `AggregatorTest` — Result aggregation

### UT Strict Validation Requirements

**All unit tests and end-to-end tests MUST follow these validation rules:**

1. **No weak assertions as primary validation**: Do NOT rely on `assertNotNull(...)`, `assertTrue(...)`, or `assertFalse(...)` alone to claim functional correctness
2. **No non-empty bypasses**: Do NOT use `json.size() > 0`, `list.size() > 0`, or `if (size > 0)` as acceptance criteria
3. **Exact cardinality is mandatory**: Always use `assertEquals(expectedSize, actualSize, ...)` for result counts
4. **Field-by-field validation is mandatory**: For each row, assert both field existence and exact expected value for all key fields
5. **Semantic assertions are mandatory**: Sorting, deduplication, pagination, filtering, and UNION semantics must be asserted explicitly
6. **Error-path assertions are mandatory**: Exception type, error message, and warning payloads must be asserted with concrete expectations
7. **`assertNotNull` boundary**: Allowed only as a guard (for example after `readTree`); never as final acceptance
8. **`assertTrue` boundary**: Allowed for boolean semantics or field presence (for example `row.has("field")`); never for vague non-empty checks

**Correct Example**:
```java
JsonNode json = objectMapper.readTree(result);
assertTrue(json.isArray(), "Result must be an array");
assertEquals(1, json.size(), "Result array should have 1 record");

JsonNode firstRow = json.get(0);
assertTrue(firstRow.has("n"), "First row must have 'n' field");

JsonNode nNode = firstRow.get("n");
assertEquals("NetworkElement", nNode.get("label").asText(), "n.label must be NetworkElement");
assertEquals("NE001", nNode.get("name").asText(), "n.name must be NE001");
```

**Incorrect Example** (FORBIDDEN):
```java
// ❌ FORBIDDEN: Vague count assertion
assertTrue(json.size() > 0, "Result array cannot be empty");

// ❌ FORBIDDEN: Conditional bypass
if (json.size() > 0) {
    JsonNode firstRow = json.get(0);
}

// ❌ FORBIDDEN: Non-null as final acceptance
assertNotNull(result);

// ❌ FORBIDDEN: Generic success assertion without content checks
assertTrue(executionResult.isSuccess());
```

**Review Gate (Mandatory)**:
1. Every added/updated UT must document which fields are validated and what each expected value is
2. If assertions do not cover key fields or core semantics, the review must reject the change
3. If exact-value assertions are impossible, the test must state the reason and add a strong semantic alternative assertion
4. Pre-commit self-check must search and clean the following patterns  
   - `assertTrue(.*size\\(\\)\\s*>\\s*0`  
   - `if\\s*\\(.*size\\(\\)\\s*>\\s*0\\)`  
   - cases with only `assertNotNull`/`assertTrue` and no `assertEquals` value assertions

## Spring Usage

- Spring Framework 6.1.6 (NOT Spring Boot). Used only for `@Component`/`@Service`/`@Configuration` DI.
- `GraphQueryEngineConfiguration` does `@ComponentScan("com.federatedquery")`.
- Tests wire components manually — no Spring test context needed.
- **Component injection rule**: For Spring-managed `@Component` / `@Service` / `@Repository` / `@Configuration` classes in this project, prefer `@Autowired` field injection instead of constructor injection to keep component wiring style consistent across the codebase.
- **UT mock injection rule**: Unit tests should follow Mockito best practice with annotation-driven injection. Prefer `@Mock` / `@Spy` plus `@InjectMocks` instead of manual `new` chains for the system under test. Avoid the non-standard `@InjectMock` spelling.

## JSON Usage

- Shared JSON handling must go through `com.federatedquery.util.JsonUtil`.
- Do not create `new ObjectMapper()` in production or test code outside `JsonUtil`.
- If code needs JSON serialization/deserialization, call `JsonUtil` exposed APIs instead of holding per-class `ObjectMapper` instances.

## Directory Structure

```
src/main/antlr4/.../grammar/   Lcypher.g4 (ANTLR4 grammar)
src/main/java/.../ast/         AST node model (Program, Statement, MatchClause, etc.)
src/main/java/.../grammar/     Generated ANTLR lexer/parser (after mvn compile)
src/main/java/.../parser/      CypherParserFacade, CypherASTVisitor
src/main/java/.../rewriter/    QueryRewriter, VirtualEdgeDetector
src/main/java/.../plan/        ExecutionPlan, PhysicalQuery, ExternalQuery, UnionPart
src/main/java/.../executor/    FederatedExecutor, BatchingStrategy, ResultStitcher, GlobalSorter, UnionDeduplicator, StitchedResult, PathBuilder
src/main/java/.../aggregator/  ResultStitcher, GlobalSorter, UnionDeduplicator, PathBuilder, StitchedResult (duplicates of executor/ — SDK uses aggregator/ versions)
src/main/java/.../reliability/  WhereConditionPushdown (used by QueryRewriter)
src/main/java/.../metadata/    MetadataRegistry, VirtualEdgeBinding, LabelMetadata
src/main/java/.../adapter/     DataSourceAdapter interface, GraphEntity (no production TuGraph adapter — use MockExternalAdapter in tests)
src/main/java/.../connector/   TuGraphConnector, TuGraphConnectorImpl, TuGraphConfig, RecordConverter (TuGraph Bolt connection)
src/main/java/.../sdk/         GraphQuerySDK (public entry point)
```

## Reference Docs

- `docs/SPEC.md` — Full SDK spec with constraints, return formats, extension mechanisms
- `docs/图联邦查询引擎思路.md` — Design doc with query examples and architecture rationale
- `docs/cases/cypher_case.md` — base cypher case
- `docs/cases/virtual_graph_case.md` — virtual graph case
