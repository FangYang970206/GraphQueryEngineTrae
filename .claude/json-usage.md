# JSON Usage Guidelines

## Overview

Guidelines for JSON handling in this Java SDK project.

---

## Rule

Shared JSON handling **must** go through `com.federatedquery.util.JsonUtil`.

```java
import com.federatedquery.util.JsonUtil;

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

Check `JsonUtil` class for available methods:
- `toJson(Object)` - Serialize to JSON string
- `fromJson(String, Class)` - Deserialize from JSON string
- `readTree(String)` - Parse JSON to JsonNode
- `valueToTree(Object)` - Convert object to JsonNode
- etc.