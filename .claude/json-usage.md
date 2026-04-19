# JSON Usage Guidelines

## Overview

Guidelines for JSON handling in this Java SDK project.

---

## Rule

Shared JSON handling **must** go through `com.fangyang.common.JsonUtil` (from `graph-query-common` module).

```java
import com.fangyang.common.JsonUtil;

// Serialize
String json = JsonUtil.toJson(object);

// Deserialize
MyObject obj = JsonUtil.fromJson(json, MyObject.class);
```

---

## What NOT to Do

**Do not** create `new ObjectMapper()` in production or test code outside `JsonUtil`:

```java
// ❌ FORBIDDEN
ObjectMapper mapper = new ObjectMapper();
String json = mapper.writeValueAsString(object);
```

If code needs JSON serialization/deserialization, call `JsonUtil` exposed APIs instead of holding per-class `ObjectMapper` instances.

---

## JsonUtil APIs

Check `JsonUtil` class in `graph-query-common` module for available methods:
- `toJson(Object)` - Serialize to JSON string
- `fromJson(String, Class)` - Deserialize from JSON string
- `readTree(String)` - Parse JSON to JsonNode
- `valueToTree(Object)` - Convert object to JsonNode
- etc.

---

## Module Dependency

To use `JsonUtil` in other modules, add dependency in `pom.xml`:

```xml
<dependency>
    <groupId>com.fangyang</groupId>
    <artifactId>graph-query-common</artifactId>
</dependency>
```
