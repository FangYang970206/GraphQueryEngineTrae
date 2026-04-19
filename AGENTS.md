# AGENTS.md — GraphQueryEngine

## Project

Java 17 Maven multi-module SDK. Federated Cypher query engine: parses Cypher via ANTLR4, splits into physical (TuGraph) + external data-source queries, aggregates results in memory, returns JSON.

## Modules

| Module | Package | Description |
|--------|---------|-------------|
| `graph-query-common` | `com.fangyang.common` | Shared utilities (JsonUtil) |
| `graph-query-metadata` | `com.fangyang.metadata` | Metadata registry, label/edge definitions |
| `graph-query-datasource` | `com.fangyang.datasource` | Data source adapters, TuGraph connector |
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

## Detailed Guidelines

- [Directory Structure](.claude/directory-structure.md)
- [Testing Guidelines](.claude/testing.md)
- [Spring Usage](.claude/spring-usage.md)
- [JSON Usage](.claude/json-usage.md)
