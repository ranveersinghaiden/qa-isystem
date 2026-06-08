# codegen-service

**Port:** `8083`  
**Phase:** 5-6 — Code Generation and Test Stabilisation  
**Role:** Consumes `BddScenario` from `TestScriptsQueue`, generates executable test code (API/UI/Mobile), runs a bounded stabilisation loop to fix compilation/runtime failures, then creates the final test code PR on GitHub.

## Kafka

| Direction | Topic | Payload |
|-----------|-------|---------|
| **Consumes** | `TestScriptsQueue` | `BddScenario` JSON |
| **Produces** | `TestResultsQueue` | `TestResult` JSON *(future)* |

Consumer group: `codegen-service-group`

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `CodegenServiceApplication` | Spring Boot entry point — scans `nz.co.eroad.qaisystem` |
| `TestScriptsConsumer` | Kafka consumer — routes to `CodegenService` |
| `CodegenService` | Routes BDD scenarios to correct test runner based on type |
| `ApiTestRunner` | Generates RestAssured + JUnit 5 test code |
| `UITestRunner` | Generates Selenium test code |
| `MobileTestRunner` | Generates Appium test code |
| `StabilizationLoop` | Bounded retry-and-fix loop (max 3 attempts) |
| `TestExecutionEngine` | Compiles and runs generated Java via `javax.tools.JavaCompiler` + JUnit Platform |
| `TestPrService` | Creates final test code PR on GitHub via `GitHubService` (from common) |

## Shared from `common`

`GitHubService`, `AiClient`, `OpenAiClient`, `PrTracker`, `RepoContextService`, `RepoContext`, `TargetRepoProperties`, `ProductExpertContext`, all models.

## Configuration

```yaml
server.port: 8083
spring.kafka.consumer.group-id: codegen-service-group
aiqa.codegen.enabled: true  # This service always runs codegen
```

## Tests

`ApiTestRunnerTest` — 7 tests covering code generation from BDD scenarios.

