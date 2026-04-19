# AGENTS.md — GraphQueryEngine

## Project

Java 17 Maven single-module SDK. Federated Cypher query engine: parses Cypher via ANTLR4, splits into physical (TuGraph) + external data-source queries, aggregates results in memory, returns JSON.

## Commands

| Command | Description                                                                   |
|---------|-------------------------------------------------------------------------------|
| `mvn compile` | **MUST run first** — generates ANTLR sources into `target/generated-sources/` |
| `mvn test` | run test case                                                                 |
| `mvn package` | Build JAR                                                                     |
| `mvn install` | Install to local repo                                                         |

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

- **Source**: `src/main/antlr4/com/federatedquery/grammar/Lcypher.g4`
- **Derived from openCypher, extended for TuGraph**: `USING SNAPSHOT`, `PROJECT BY`, multi-rel types (`|`)
- **Generated output**: `${project.build.directory}/generated-sources/antlr4/com/federatedquery/grammar/`
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

## Detailed Guidelines

- [Testing Guidelines](.claude/testing.md)
- [Spring Usage](.claude/spring-usage.md)
- [JSON Usage](.claude/json-usage.md)
- [Directory Structure](.claude/directory-structure.md)