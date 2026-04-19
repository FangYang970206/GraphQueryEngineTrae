# Testing Guidelines

## Overview

Guidelines for writing and validating unit tests in this Java SDK project.

---

## Test Setup Pattern

1. Create `MetadataRegistryImpl` (from `graph-query-metadata` module)
2. Register data sources + virtual edges + labels
3. Register mock adapters on `FederatedExecutor` (for unit tests)
4. Or use `TuGraphConnector` for real database integration tests
5. Wire all components into `GraphQuerySDK`

---

## Mock Data Sources

**Unit tests use Mock data sources**, including:
- **TuGraph** (physical graph database) - mocked via `MockExternalAdapter`
- **External services** (REST API, gRPC, custom adapters) - mocked via `MockExternalAdapter`

The `MockExternalAdapter` (`graph-query-engine/src/test/java/.../adapter/MockExternalAdapter.java`) simulates all data source behaviors:
- Supports configurable responses per operator
- Simulates delays and errors for reliability testing
- No real database or external service connections required

---

## Real Database Integration Tests

**Integration tests support real TuGraph database connections**:
- **TuGraphConnector** (`graph-query-datasource/src/main/java/.../TuGraphConnectorImpl.java`) provides Bolt protocol connection
- **Default configuration**: `bolt://127.0.0.1:7687`, username: `admin`, password: `73@TuGraph`
- Tests are automatically skipped if TuGraph is not available
- Located in `graph-query-engine/src/test/java/.../connector/TuGraphConnectorTest.java` and `graph-query-engine/src/test/java/.../e2e/TuGraphRealDatabaseE2ETest.java`

---

## Test Coverage

| Test Class | Coverage |
|-----------|----------|
| E2ETest | 5 query scenarios with full pipeline (mocked) |
| TuGraphRealDatabaseE2ETest | Real database integration tests |
| TuGraphConnectorTest | TuGraph connector unit/integration tests |
| ParserTest | ANTLR4 parsing |
| RewriterTest | Query rewriting |
| ExecutorTest | Federated execution |
| AggregatorTest | Result aggregation |

---

## UT Strict Validation Requirements

**All unit tests and end-to-end tests MUST follow these validation rules:**

### Assertions Rules

1. **No weak assertions as primary validation**: Do NOT rely on `assertNotNull(...)`, `assertTrue(...)`, or `assertFalse(...)` alone to claim functional correctness
2. **No non-empty bypasses**: Do NOT use `json.size() > 0`, `list.size() > 0`, or `if (size > 0)` as acceptance criteria
3. **Exact cardinality is mandatory**: Always use `assertEquals(expectedSize, actualSize, ...)` for result counts
4. **Field-by-field validation is mandatory**: For each row, assert both field existence and exact expected value for all key fields
5. **Semantic assertions are mandatory**: Sorting, deduplication, pagination, filtering, and UNION semantics must be asserted explicitly
6. **Error-path assertions are mandatory**: Exception type, error message, and warning payloads must be asserted with concrete expectations
7. **`assertNotNull` boundary**: Allowed only as a guard (for example after `readTree`); never as final acceptance
8. **`assertTrue` boundary**: Allowed for boolean semantics or field presence (for example `row.has("field")`); never for vague non-empty checks

### Correct Example

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

### Incorrect Example (FORBIDDEN)

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

---

## Review Gate (Mandatory)

1. Every added/updated UT must document which fields are validated and what each expected value is
2. If assertions do not cover key fields or core semantics, the review must reject the change
3. If exact-value assertions are impossible, the test must state the reason and add a strong semantic alternative assertion
4. Pre-commit self-check must search and clean the following patterns  
   - `assertTrue(.*size\(\)\s*>\s*0`  
   - `if\s*\(.*size\(\)\s*>\s*0\)`  
   - cases with only `assertNotNull`/`assertTrue` and no `assertEquals` value assertions

---

## Test Command

```bash
mvn test       # Run all tests across modules
```
