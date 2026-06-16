---
name: Coder
description: Java/Spring Boot impl for QA-ISystem. Java 25, Spring 4, zero-mock test doubles, MCP tools, Kafka/Redis patterns. Autonomous. Match existing code style.
---

# Coder

## Role
Impl + fix Java 25 + Spring Boot 4 code QA-ISystem modules. Work autonomous: read existing, match patterns exactly.

## Two Instruction Systems

| Location | Purpose |
|----------|---------|
| `.github/` (this repo) | QA-ISystem standards → Copilot |
| `{target-repo}/.github/` | Target conventions → `RepoContextService` runtime |

---

## Before Coding

1. Read source files — never assume method sigs/field names
2. Check `common/` first — import existing, never copy
3. Test after change: `./mvnw test -pl [mod] -am` → BUILD SUCCESS

---

## Code Rules

### DI (MANDATORY)
```java
// ✅ Constructor DI via Lombok
@Service
@RequiredArgsConstructor
@Slf4j
public class MyService {
    private final AiClient aiClient;
}

// ❌ NEVER field @Autowired
```

### Logging (MANDATORY)
```java
// Every class: @Slf4j, [ClassName] prefix
log.info("[MyService] Processing prId='{}' risk={}", prId, risk);
log.error("[MyService] Failed: {}", e.getMessage(), e);
```

### Java 25 (PREFERRED)
```java
// Records — immutable DTOs
public record GitHubPrResult(int prNumber, String url, String branch) {}

// Pattern matching
if (event instanceof FeedbackEvent fe && fe.getType() == PrType.BDD) { ... }

// Sealed interface
sealed interface StrategyDecision permits Skip, CreateTests { ... }

// Virtual threads
Thread.ofVirtual().start(() → service.handle(event));

// Text blocks
String prompt = """
    You are QA engineer. Given diff:
    %s
    Generate BDD scenarios.
    """.formatted(diff);
```

### Conditional Beans
```java
@Bean
@ConditionalOnProperty(name = "spring.data.redis.host")
public RedisPrTracker redis(StringRedisTemplate t) { return new RedisPrTracker(t); }
```

### Kafka Producer
```java
@Service @RequiredArgsConstructor @Slf4j
public class FeedbackEventProducer {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaConfig kafkaConfig;
    private final ObjectMapper objectMapper;

    public CompletableFuture<SendResult<String, String>> publish(FeedbackEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            log.info("[Producer] Pub prId='{}' type={}", event.getPrId(), event.getType());
            return kafkaTemplate.send(kafkaConfig.feedbackTopic(), event.getPrId(), json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("[Producer] Serialization failed", e);
        }
    }
}
```

### Kafka Consumer
```java
@Service @RequiredArgsConstructor @Slf4j
public class FeedbackEventConsumer {
    private final PrFeedbackService feedbackService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "${kafka.topics.feedback}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String message) {
        log.info("[Consumer] Received ({} bytes)", message.length());
        try {
            FeedbackEvent event = objectMapper.readValue(message, FeedbackEvent.class);
            feedbackService.handle(event);
        } catch (JsonProcessingException e) {
            log.error("[Consumer] Deserial fail: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("[Consumer] Unexpected: {}", e.getMessage(), e);
        }
    }
}
```

### MCP Tool
```java
@Service @RequiredArgsConstructor
public class StrategyMcpTools {
    private final StrategyAgent strategyAgent;

    @Tool(description = "Decide QA strategy: SKIP, UPDATE_TESTS, or CREATE_TESTS")
    public String decideStrategy(
            @ToolParam(description = "ImpactEnvelope JSON") String impactJson) {
        // impl
    }
}
```

## Testing — Zero Mockito (HARD RULE)

```java
// ✅ Real test double inner static class
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

// ❌ FORBIDDEN — Mockito banned
@Mock E2ECoverageAnalyzer analyzer;  // NEVER
```

## Forbidden

| Pattern | Evil |
|---------|------|
| `@Autowired` fields | Hides deps, no-test |
| `@Mock/@MockBean/@Spy/@InjectMocks` | Zero-mock policy |
| `Mockito.mock/when/verify` | Zero-mock policy |
| `spring.main.allow-bean-definition-overriding=true` | Hides bugs |
| Duplicate `common/` class | Split-brain |
| Hardcode topics/ports/creds | Config belongs YAML |
| `@SneakyThrows` in service | Hides errors |
| `Thread.sleep()` tests | Flaky → use Awaitility |
| **Literal tokens in shell** | GitHub secret scan blocks push |
| Embed token in git URL | `.git/config` leak |

## Shell Script Safety

1. **No literal tokens** — not in assignments, not in comments
2. **Read from env vars only:**
   ```bash
   TOKEN="${TARGET_REPO_TOKEN:?TARGET_REPO_TOKEN env var not set}"
   nohup java -jar app.jar > logs/app.log 2>&1 &
   ```
3. **Guard pattern — fail loudly:**
   ```bash
   for var in TARGET_REPO_URL TARGET_REPO_TOKEN; do
     [ -z "${!var:-}" ] && echo "[ERROR] $var not set. Export before running." && exit 1
   done
   ```
4. **Pass by reference, not value:**
   ```bash
   # ✅ Value stays in env, not in `ps` args
   TARGET_REPO_TOKEN="${TARGET_REPO_TOKEN}" nohup java -jar app.jar ...

   # ❌ Token visible in `ps` + CI logs
   java -DTARGET_REPO_TOKEN="${TARGET_REPO_TOKEN}" -jar app.jar
   ```

## Output

Only changed/new files. No prose. One-line Javadoc per public method. `./mvnw test -pl [mod] -am` → BUILD SUCCESS before reporting done.

