# AGENTS.md — GraphQueryEngine

## Project

Java 17 Maven multi-module SDK. Federated Cypher query engine: parses Cypher via ANTLR4, splits into physical (TuGraph) + external data-source queries, aggregates results in memory, returns JSON.

## Modules

| Module | Package | Description                                     |
|--------|---------|-------------------------------------------------|
| `graph-query-common` | `com.fangyang.common` | Shared utilities (eg: JsonUtil)                 |
| `graph-query-metadata` | `com.fangyang.metadata` | Metadata registry, label/edge definitions       |
| `graph-query-datasource` | `com.fangyang.datasource` | Data source adapters, TuGraph connector         |
| `graph-query-engine` | `com.fangyang.federatedquery` | Core engine (parser, rewriter, executor, model) |

## Commands

| Command | Description |
|---------|-------------|
| `mvn compile` | **MUST run first** — generates ANTLR sources |
| `mvn test` | Run tests |
| `mvn package` | Build JARs |
| `mvn install` | Install to local repo |

> **Critical**: `mvn compile` must run before IDE or agents can resolve `com.fangyang.federatedquery.grammar.*` (generated from `Lcypher.g4`).

## Architecture (Pipeline)

```
Cypher → CypherParserFacade (ANTLR4 + Caffeine cache) → AST (Program)
     → QueryRewriter (VirtualEdgeDetector + MetadataQueryService) → ExecutionPlan
     → FederatedExecutor (parallel via ExecutorService, batched external queries)
     → ResultStitcher → GlobalSorter → UnionDeduplicator → JSON
```

### Key Entry Points

| File | Role |
|------|------|
| `graph-query-engine/.../sdk/GraphQuerySDK.java` | Public API — `execute(cypher)`, `executeRecords(cypher)` |
| `graph-query-engine/.../parser/CypherParserFacade.java` | ANTLR4 parse + MD5-based plan cache (max 1000, 1h TTL) |
| `graph-query-engine/.../rewriter/QueryRewriter.java` | AST → ExecutionPlan, splits virtual/physical queries |
| `graph-query-engine/.../executor/FederatedExecutor.java` | Parallel execution, fixed thread pool (10), batching |
| `graph-query-metadata/.../MetadataRegistryImpl.java` | Registers data sources, virtual edges, labels |

## Core Constraints (Hard Rules)

1. **Virtual edge/node boundary**: Only allowed at **first hop** or **last hop** of a path. No `[physical]→[virtual]→[physical]` sandwich structures. Enforced by `VirtualEdgeConstraintException`.
2. **Read-only**: No `CREATE`, `MERGE`, `DELETE`, `SET`, `REMOVE`. Only `MATCH`, `RETURN`, `WITH`, `WHERE`, `ORDER BY`, `LIMIT`, `SKIP`, `UNION`.
3. **WHERE pushdown**: Conditions on virtual nodes must NOT be pushed to TuGraph — they become Java-level filters for external queries.
4. **Global sort/limit**: `ORDER BY` and `LIMIT` are stripped from physical queries, applied in-memory after aggregation.
5. **No N+1**: External queries are batched via `BatchingStrategy`. Never loop-call external APIs.

## Metadata-Driven Rules

- **No hard-coded graph semantics**: `label` / `edgeType` / schema / property names / id join fields must not be hard-coded in business logic. They must be resolved from `MetadataQueryService`, `LabelMetadata`, and `VirtualEdgeBinding`.
- **Virtual edge target resolution**: Whether an edge is virtual, its target label, target data source, and join-field mapping must come from metadata lookup.
- **Schema access rule**: Label identity field, required properties, property mapping, and virtual-edge `idMapping` are the single source of truth.
- **Test setup rule**: Any new label / virtual edge used in UT or E2E must register complete metadata first.

## Must

### 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

## Detailed Guidelines

- [Directory Structure](.claude/directory-structure.md)
- [Testing Guidelines](.claude/testing.md)
- [Spring Usage](.claude/spring-usage.md)
- [JSON Usage](.claude/json-usage.md)
