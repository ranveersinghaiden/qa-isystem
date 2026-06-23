# codegen-service

**Port:** `8083`  
**Phase:** 5-6 — Code Generation and Test Stabilisation  
**Role:** Consumes `BddScenario` from `TestScriptsQueue`, delegates executable test-code generation (API/UI/Mobile) to the target repository's **Conductor** agent, runs a bounded stabilisation loop to fix compilation/runtime failures, then creates the final test code PR on GitHub.

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
| `CodegenService` | For each scenario delegates test-code generation to the Conductor agent via `ConductorCodeGenerator`. No template generation, no AI API call, no `tests/api\|ui\|mobile` context scan. |
| `ConductorCodeGenerator` *(common)* | Builds the per-scenario prompt and delegates to the Conductor agent via `ConductorAgentRunner`, running in the cloned target repo directory |
| `StabilizationLoop` | Bounded retry-and-fix loop (max 3 attempts) |
| `TestExecutionEngine` | Compiles and runs generated Java via `javax.tools.JavaCompiler` + JUnit Platform |
| `TestPrService` | Creates final test code PR on GitHub via `GitHubService` (from common). Title: `✅ [AI-QA] {prTitle}` (passing) or `⚠️ [NEEDS REVIEW] {prTitle}` (abandoned). `prTitle` sourced from `BddScenario.prTitle` → `PullRequest.title`. |

## Shared from `common`

`GitHubService`, `ConductorAgentRunner`, `ConductorCodeGenerator`, `PrTracker`, `RepoContextService`, `RepoContext`, `TargetRepoProperties`, `ProductExpertContext`, all models.

## AI Provider

Test-code generation is delegated to the repository's **Conductor** agent via a monitored
`copilot` subprocess (`ConductorAgentRunner`). Run `gh auth login` once. No other agent is
invoked and no AI API is called directly.

## Configuration

```yaml
server.port: 8083
spring.kafka.consumer.group-id: codegen-service-group
aiqa.codegen.enabled: true  # This service always runs codegen
```

## Tests

`StrategyAgentTest` and shared `common` tests cover the pipeline. Test-code generation itself is
delegated to the Conductor agent at runtime (no template-runner unit tests).

