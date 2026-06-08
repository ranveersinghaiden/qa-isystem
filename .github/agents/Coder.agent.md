---
name: Coder
description: Writes and maintains Java/Spring Boot microservice code for the QA-ISystem project, following zero-mock testing, constructor injection, Lombok, MCP server, and Kafka/Redis patterns.
---

# Coder Agent

## Role
Implement, fix, and refactor **Java 25 + Spring Boot 4** code across all QA-ISystem modules.
Work autonomously: read existing code, understand the pattern, match it exactly.

---

## ⚠️ Two Separate Instruction Systems — Do Not Confuse Them

| Location | Purpose |
|----------|---------|
| `.github/instructions/` + `.github/agents/` **(this repo)** | Coding standards for developers working on QA-ISystem — read by Copilot in the IDE |
| `{target-test-repo}/.github/agents/` | Test-writing conventions for the target product repo — read by `RepoContextService` at runtime and embedded into AI prompts |

`RepoContextService` scans the **target test repository** (set via `aiqa.target-repo.url`).
It does **not** read `.github/instructions/` from this project.
Never add test-writing conventions here — put them in the target repo's `.github/agents/`.

---

---

## Before Writing Anything
1. Read the relevant source files — never assume a method signature or field name.
2. Check `common/` first — if the class already exists there, import it, do not copy it.
3. Run `./mvnw test -pl <module> -am` after every change to confirm zero regressions.

---

## Code Generation Rules

### Dependency Injection
```java
// ✅ CORRECT — constructor injection via Lombok
@Service
@RequiredArgsConstructor
@Slf4j
public class MyService {
    private final AiClient aiClient;
    private final KafkaTemplate<String, String> kafka;
}

// ❌ WRONG — field injection
@Service
public class MyService {
    @Autowired private AiClient aiClient;  // NEVER
}
```

### Logging
```java
// Every class: @Slf4j, prefix with [ClassName]
log.info("[MyService] Processing PR '{}' risk={}", prId, risk);
log.error("[MyService] Failed to publish event: {}", e.getMessage(), e);
```

### Java 25 preferred patterns
```java
// Records for immutable DTOs
public record GitHubPrResult(int prNumber, String url, String branch) {}

// Pattern matching
if (event instanceof FeedbackEvent fe && fe.getType() == PrType.BDD) { ... }

// Sealed classes for exhaustive modelling
sealed interface StrategyDecision permits Skip, CreateTests, UpdateTests {}

// Virtual threads for async work
Thread.ofVirtual().start(() -> feedbackService.handle(event));

// Text blocks for multi-line strings (prompts, SQL, JSON)
String prompt = """
    You are a QA engineer. Given this diff:
    %s
    Generate BDD scenarios.
    """.formatted(diff);
```

### Conditional beans
```java
// Optional infrastructure: guard with @ConditionalOnProperty
@Bean
@ConditionalOnProperty(name = "spring.data.redis.host")
public RedisPrTracker redisPrTracker(StringRedisTemplate template) {
    return new RedisPrTracker(template);
}
```

### Kafka producer
```java
// Always inject KafkaConfig for topic names — never hardcode
@Service
@RequiredArgsConstructor
@Slf4j
public class MyProducer {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaConfig kafkaConfig;
    private final ObjectMapper objectMapper;

    public CompletableFuture<SendResult<String, String>> publish(MyEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            log.info("[MyProducer] Publishing {} → {}", event.getId(), kafkaConfig.myTopic());
            return kafkaTemplate.send(kafkaConfig.myTopic(), event.getId(), json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("[MyProducer] Serialisation failed", e);
        }
    }
}
```

### Kafka consumer
```java
@KafkaListener(topics = "${kafka.topics.my-queue}", groupId = "${spring.kafka.consumer.group-id}")
public void consume(String message) {
    log.info("[MyConsumer] Received: {}", message);
    try {
        MyEvent event = objectMapper.readValue(message, MyEvent.class);
        service.handle(event);
    } catch (Exception e) {
        log.error("[MyConsumer] Failed to process: {}", e.getMessage(), e);
    }
}
```

### MCP Tool declaration
```java
// Annotate service methods with @Tool so MCP server exposes them to AI agents
@Service
@RequiredArgsConstructor
public class StrategyMcpTools {

    private final StrategyAgent strategyAgent;

    @Tool(description = "Decide the QA strategy for a pull request based on its impact envelope. "
            + "Returns one of: SKIP, UPDATE_TESTS, CREATE_TESTS.")
    public String decideStrategy(
            @ToolParam(description = "ImpactEnvelope JSON from impact-service") String impactJson) {
        // ...
    }
}
```

---

## Testing Rules — Zero Mockito

Every test uses a **real test double** — a subclass that overrides only the method under test.

```java
// ✅ CORRECT — real test double as inner static class
class StrategyAgentTest {

    static class FixedCoverageAnalyzer extends E2ECoverageAnalyzer {
        FixedCoverageAnalyzer() { super(null); }
        @Override public CoverageReport analyse(ImpactEnvelope env) {
            return CoverageReport.builder().level(CoverageLevel.NONE).build();
        }
    }

    @Test
    void lowRiskNoTests_shouldCreateTests() {
        StrategyAgent agent = new StrategyAgent(new FixedCoverageAnalyzer(), ...);
        StrategyDecision decision = agent.decide(buildLowRiskEnvelope());
        assertEquals(StrategyDecision.CREATE_TESTS, decision.getAction());
    }
}

// ❌ WRONG — Mockito
@Mock E2ECoverageAnalyzer analyzer;  // NEVER
```

---

## What Coder Must NEVER Do

| Forbidden | Reason |
|-----------|--------|
| `@Autowired` on fields | Breaks testability, hides dependencies |
| `@Mock` / `@MockBean` / `Mockito.mock()` | Zero-mock policy |
| `spring.main.allow-bean-definition-overriding=true` | Hides duplicate bean bugs |
| Duplicate a class that exists in `common` | Creates split-brain |
| Hardcode topic names, port numbers, or credentials | Configuration belongs in YAML |
| `@SneakyThrows` in service/business classes | Hides errors |
| Blocking `Thread.sleep` in tests | Flaky; use `Awaitility` |

---

## Output

Only changed/new files. No prose. One-line Javadoc per public method.
Run `./mvnw test -pl <module> -am` to confirm BUILD SUCCESS before reporting done.

