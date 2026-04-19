# Directory Structure

## Overview

Reference for the project's multi-module Maven structure and key files.

---

## Module Structure

```
GraphQueryEngine/
├── graph-query-common/          # Shared utilities
├── graph-query-metadata/        # Metadata management
├── graph-query-datasource/      # Data source adapters
├── graph-query-engine/          # Core query engine
├── docs/                        # Documentation
├── .claude/                     # Agent instruction files
└── pom.xml                      # Parent POM
```

---

## Module Details

### graph-query-common

```
graph-query-common/
└── src/main/java/com/fangyang/common/
    └── JsonUtil.java            # Shared JSON utilities
```

### graph-query-metadata

```
graph-query-metadata/
└── src/main/java/com/fangyang/metadata/
    ├── DataSourceMetadata.java  # Data source definition
    ├── DataSourceType.java      # Enum: TUGRAPH, REST, etc.
    ├── LabelMetadata.java       # Label schema definition
    ├── MetadataFactory.java     # Factory for metadata creation
    ├── MetadataQueryService.java# Query service interface
    ├── MetadataRegistrar.java   # Registration interface
    ├── MetadataRegistryImpl.java# Registry implementation
    └── VirtualEdgeBinding.java  # Virtual edge configuration
```

### graph-query-datasource

```
graph-query-datasource/
└── src/main/java/com/fangyang/datasource/
    ├── DataSourceAdapter.java       # Adapter interface
    ├── DataSourceFactory.java       # Factory for adapters
    ├── DataSourceQueryParams.java   # Query parameters
    ├── TuGraphAdapterImpl.java      # TuGraph adapter
    ├── TuGraphConfig.java           # TuGraph configuration
    ├── TuGraphConnector.java        # Connector interface
    └── TuGraphConnectorImpl.java    # Bolt protocol implementation
```

### graph-query-engine

```
graph-query-engine/
├── src/main/antlr4/com/fangyang/federatedquery/grammar/
│   └── Lcypher.g4               # ANTLR4 grammar (before mvn compile)
│
├── src/main/java/com/fangyang/federatedquery/
│   ├── aggregator/              # Result aggregation
│   │   ├── GlobalSorter.java
│   │   ├── PathBuilder.java
│   │   ├── PendingFilterApplier.java
│   │   ├── ResultConverter.java
│   │   ├── ResultStitcher.java
│   │   ├── StitchedResult.java
│   │   └── UnionDeduplicator.java
│   ├── ast/                     # AST node model
│   │   ├── Program.java, Statement.java, MatchClause.java
│   │   ├── ReturnClause.java, WhereClause.java, WithClause.java
│   │   └── ... (other AST nodes)
│   ├── exception/               # Exception types
│   │   ├── ErrorCode.java
│   │   ├── GraphQueryException.java
│   │   ├── SyntaxErrorException.java
│   │   └── VirtualEdgeConstraintException.java
│   ├── executor/                # Query execution
│   │   ├── BatchRequest.java
│   │   ├── BatchingStrategy.java
│   │   ├── DependencyResolver.java
│   │   ├── ExecutionResult.java
│   │   ├── FederatedExecutor.java
│   │   └── ResultEnricher.java
│   ├── parser/                  # Cypher parsing
│   │   ├── CypherASTVisitor.java
│   │   ├── CypherParserFacade.java
│   │   └── SyntaxErrorListener.java
│   ├── plan/                    # Execution plan
│   │   ├── ExecutionPlan.java
│   │   ├── ExternalQuery.java
│   │   ├── GlobalContext.java
│   │   ├── PhysicalQuery.java
│   │   └── UnionPart.java
│   ├── reliability/             # Reliability features
│   │   └── WhereConditionPushdown.java
│   ├── rewriter/                # Query rewriting
│   │   ├── MixedPatternRewriter.java
│   │   ├── PhysicalQueryBuilder.java
│   │   ├── QueryRewriter.java
│   │   └── VirtualEdgeDetector.java
│   ├── sdk/                     # Public API
│   │   └── GraphQuerySDK.java
│   ├── model/                   # Domain models
│   │   ├── GraphEntity.java     # Graph node/edge entity
│   │   └── QueryResult.java     # Query execution result
│   ├── util/                    # Utilities
│   │   └── RecordConverter.java
│
└── src/test/java/com/fangyang/federatedquery/
    ├── aggregator/              # Aggregator tests
    ├── e2e/                     # End-to-end tests
    ├── executor/                # Executor tests
    ├── mockserver/              # Mock HTTP server
    ├── parser/                  # Parser tests
    ├── rewriter/                # Rewriter tests
    ├── sdk/                     # SDK tests
    └── testutil/                # Test utilities
```

---

## Key Entry Points

| File | Role |
|------|------|
| `graph-query-engine/.../sdk/GraphQuerySDK.java` | Public API — `execute(cypher)`, `executeRecords(cypher)` |
| `graph-query-engine/.../parser/CypherParserFacade.java` | ANTLR4 parse + MD5-based plan cache (max 1000, 1h TTL) |
| `graph-query-engine/.../rewriter/QueryRewriter.java` | AST → ExecutionPlan, splits virtual/physical queries |
| `graph-query-engine/.../executor/FederatedExecutor.java` | Parallel execution, fixed thread pool (10), batching |
| `graph-query-metadata/.../MetadataRegistryImpl.java` | Registers data sources, virtual edges, labels |

---

## ANTLR4 Grammar

- **Source**: `graph-query-engine/src/main/antlr4/com/fangyang/federatedquery/grammar/Lcypher.g4`
- **Generated**: `graph-query-engine/target/generated-sources/antlr4/com/fangyang/federatedquery/grammar/` (after `mvn compile`)
- **Extended for TuGraph**: `USING SNAPSHOT`, `PROJECT BY`, multi-rel types (`|`)
- Visitor + listener both enabled

---

## Reference Docs

| File | Description |
|------|-------------|
| `docs/SPEC.md` | Full SDK spec with constraints, return formats |
| `docs/图联邦查询引擎思路.md` | Design doc with query examples |
| `docs/cases/cypher_case.md` | Base Cypher case |
| `docs/cases/virtual_graph_case.md` | Virtual graph case |
