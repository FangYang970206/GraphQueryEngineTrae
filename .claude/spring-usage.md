# Spring Usage Guidelines

## Overview

Guidelines for using Spring Framework 6.1.6 in this project.

---

## Version

- **Spring Framework**: 6.1.6 (NOT Spring Boot)
- Used only for dependency injection: `@Component`, `@Service`, `@Configuration`

---

## Component Scanning

`GraphQueryEngineConfiguration` does `@ComponentScan("com.federatedquery")`.

---

## Injection Rules

### Component Injection Rule

For Spring-managed `@Component` / `@Service` / `@Repository` / `@Configuration` classes in this project, prefer **`@Autowired` field injection** instead of constructor injection to keep component wiring style consistent across the codebase.

```java
@Service
public class MyService {
    @Autowired
    private MyDependency dependency;
}
```

### UT Mock Injection Rule

Unit tests should follow **Mockito best practice** with annotation-driven injection. Prefer `@Mock` / `@Spy` plus **`@InjectMocks`** instead of manual `new` chains for the system under test.

```java
@ExtendWith(MockitoExtension.class)
class MyServiceTest {
    @Mock
    private MyDependency dependency;
    
    @InjectMocks
    private MyService myService;
}
```

> **Note**: Use standard `@InjectMocks` spelling (not the non-standard variant).

---

## Tests Wiring

Tests wire components **manually** — no Spring test context needed.

```java
// Manual wiring pattern
CypherParserFacade parser = new CypherParserFacade();
QueryRewriter rewriter = new QueryRewriter(registry, detector);
FederatedExecutor executor = new FederatedExecutor(registry);
GraphQuerySDK sdk = new GraphQuerySDK(parser, rewriter, executor, ...);
```