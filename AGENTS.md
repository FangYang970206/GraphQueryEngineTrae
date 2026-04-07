# AGENTS.md — GraphQueryEngine

## Project

Java 17 Maven single-module SDK. Federated Cypher query engine: parses Cypher via ANTLR4, splits into physical (TuGraph) + external data-source queries, aggregates results in memory, returns JSON.

- **Package**: `com.federatedquery`
- **GroupId**: `com.federatedquery`
- **Artifact**: `graph-query-engine`
- **Version**: `1.0.0-SNAPSHOT`

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

## Testing

### Mock Data Sources

**ALL data sources are mocked in unit tests**, including:
- **TuGraph** (physical graph database)
- **External services** (REST API, gRPC, custom adapters)

The `MockExternalAdapter` (`src/test/java/.../adapter/MockExternalAdapter.java`) simulates all data source behaviors:
- Supports configurable responses per operator
- Simulates delays and errors for reliability testing
- No real database or external service connections required

### Test Setup Pattern

1. Create `MetadataRegistryImpl`
2. Register data sources + virtual edges + labels
3. Register mock adapters on `FederatedExecutor`
4. Wire all components into `GraphQuerySDK`

### Test Coverage

- `E2ETest` — 5 query scenarios with full pipeline
- `ParserTest` — ANTLR4 parsing
- `RewriterTest` — Query rewriting
- `ExecutorTest` — Federated execution
- `AggregatorTest` — Result aggregation

### UT Strict Validation Requirements

**All end-to-end tests MUST follow these validation rules:**

1. **No vague assertions**: Do NOT use `json.size() > 0` or `if (json.size() > 0)` to bypass validation
2. **Exact count validation**: MUST use `assertEquals(expectedSize, json.size(), "Result count must be X")`
3. **Content completeness validation**: MUST verify every field value in returned results
4. **Field existence validation**: MUST use `assertTrue(row.has("fieldName"), "fieldName field is required")`

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
    // ...
}
```

## Spring Usage

- Spring Framework 6.1.6 (NOT Spring Boot). Used only for `@Component`/`@Service`/`@Configuration` DI.
- `GraphQueryEngineConfiguration` does `@ComponentScan("com.federatedquery")`.
- Tests wire components manually — no Spring test context needed.

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| ANTLR4 | 4.13.1 | Cypher parsing |
| Caffeine | 3.1.8 | Plan cache |
| Jackson | 2.17.0 | JSON serialization |
| JUnit 5 | 5.10.2 | Testing |
| Mockito | 5.11.0 | Mocking |
| SLF4J + Logback | 2.0.12 / 1.5.4 | Logging |

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
src/main/java/.../sdk/         GraphQuerySDK (public entry point)
```

## Reference Docs

- `docs/SPEC.md` — Full SDK spec with constraints, return formats, extension mechanisms
- `docs/图联邦查询引擎思路.md` — Design doc with query examples and architecture rationale
- `docs/todo/optimization-items.md` — Known issues and planned improvements
