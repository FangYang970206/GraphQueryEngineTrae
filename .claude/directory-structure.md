# Directory Structure

## Overview

Reference for the project's package structure and key files.

---

## Main Source

```
src/main/
├── antlr4/
│   └── com/federatedquery/grammar/
│       └── Lcypher.g4              # ANTLR4 grammar (before mvn compile)
│
└── java/
    └── com/federatedquery/
        ├── adapter/                 # DataSourceAdapter interface, GraphEntity
        ├── aggregator/              # ResultStitcher, GlobalSorter, UnionDeduplicator, PathBuilder, StitchedResult
        ├── ast/                      # AST node model (Program, Statement, MatchClause, etc.)
        ├── connector/               # TuGraphConnector, TuGraphConnectorImpl, TuGraphConfig, RecordConverter
        ├── executor/                # FederatedExecutor, BatchingStrategy, ResultStitcher, GlobalSorter, UnionDeduplicator, StitchedResult, PathBuilder
        ├── grammar/                  # Generated ANTLR lexer/parser (after mvn compile)
        ├── metadata/                 # MetadataRegistry, VirtualEdgeBinding, LabelMetadata
        ├── parser/                    # CypherParserFacade, CypherASTVisitor
        ├── plan/                     # ExecutionPlan, PhysicalQuery, ExternalQuery, UnionPart
        ├── rewriter/                 # QueryRewriter, VirtualEdgeDetector
        ├── reliability/              # WhereConditionPushdown (used by QueryRewriter)
        ├── sdk/                      # GraphQuerySDK (public entry point)
        └── util/                     # JsonUtil, etc.
```

---

## Test Source

```
src/test/
└── java/
    └── com/federatedquery/
        ├── adapter/
        │   └── MockExternalAdapter.java    # Mock for data sources
        ├── connector/
        │   └── TuGraphConnectorTest.java
        └── e2e/
            ├── E2ETest.java
            └── TuGraphRealDatabaseE2ETest.java
```

---

## Key Entry Points

| File | Role |
|------|------|
| `src/main/java/.../sdk/GraphQuerySDK.java` | Public API — `execute(cypher)` and `executeRaw(cypher)` |
| `src/main/java/.../parser/CypherParserFacade.java` | ANTLR4 parse + MD5-based plan cache (max 1000, 1h TTL) |
| `src/main/java/.../rewriter/QueryRewriter.java` | AST → ExecutionPlan, splits virtual/physical queries |
| `src/main/java/.../executor/FederatedExecutor.java` | Parallel execution, fixed thread pool (10), batching |
| `src/main/java/.../metadata/MetadataRegistry.java` | Registers data sources, virtual edges, labels |

---

## ANTLR4 Grammar

- **Source**: `src/main/antlr4/com/federatedquery/grammar/Lcypher.g4`
- **Generated**: `target/generated-sources/antlr4/com/federatedquery/grammar/` (after `mvn compile`)
- **Extended for TuGraph**: `USING SNAPSHOT`, `PROJECT BY`, multi-rel types (`|`)
- Visitor + listener both enabled

---

## Reference Docs

| File | Description |
|------|-------------|
| `docs/SPEC.md` | Full SDK spec with constraints, return formats, extension mechanisms |
| `docs/图联邦查询引擎思路.md` | Design doc with query examples and architecture rationale |
| `docs/cases/cypher_case.md` | Base Cypher case |
| `docs/cases/virtual_graph_case.md` | Virtual graph case |