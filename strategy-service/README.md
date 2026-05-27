# strategy-service

**Port:** `8082`  
**Phases:** 2 → 6 — Strategy, BDD Generation, Code Generation, Test Stabilisation  
**Role:** The AI layer. Consumes impact envelopes, decides what tests to write,
generates BDD scenarios, produces executable test code, and stabilises failing tests.

---

## Responsibilities

- Consume `ImpactEnvelope` from `ImpactResultsQueue`
- Decide: SKIP / UPDATE_TESTS / CREATE_TESTS (with coverage-awareness and fallback rules)
- Generate Gherkin BDD scenarios and raise a human-review PR
- After human approval, generate executable test code (API / UI / Mobile)
- Run a bounded stabilisation loop (max 3 retries with incremental auto-fixes)
- Raise a Final Test PR for human review

---

## Package Structure

```
qaisystem/
├── strategy/
│   └── StrategyServiceApplication.java    ← Spring Boot entry point
├── agent/
│   ├── StrategyAgent.java                 ← Decision engine + UPDATE_TESTS handler
│   └── BddGenerator.java                  ← Template-based Gherkin generation
├── execution/
│   ├── CodegenService.java                ← Routes BDD scenarios to correct runner
│   ├── ApiTestRunner.java                 ← Generates RestAssured + JUnit 5 code
│   ├── UITestRunner.java                  ← Generates Selenium + ChromeDriver code
│   ├── MobileTestRunner.java              ← Generates Appium + AndroidDriver code
│   ├── TestExecutionEngine.java           ← Executes (or simulates) generated test code
│   └── StabilizationLoop.java             ← Run → fail → fix loop (≤ 3 attempts)
├── service/
│   └── TestPrService.java                 ← Simulates raising GitHub/GitLab PRs
├── kafka/
│   ├── ImpactResultsConsumer.java         ← Consumes ImpactEnvelope from Kafka
│   ├── TestScriptsProducer.java           ← Publishes BddScenario to TestScriptsQueue
│   └── TestScriptsConsumer.java           ← Consumes BddScenario and triggers codegen
└── controller/
    └── StrategyController.java            ← approve-bdd endpoint + health
```

---

## StrategyAgent — Decision Logic

### Primary Decision Rules (evaluated in order)

| Condition | Decision |
|-----------|----------|
| `CoverageReport.level == NONE` and missing areas exist | `CREATE_TESTS` (forced) |
| Only CONFIGURATION_CHANGE or DEPENDENCY_UPDATE AND `riskLevel == LOW` | `SKIP` |
| All changed files are test files | `SKIP` |
| `riskLevel == HIGH` or `CRITICAL` | `CREATE_TESTS` |
| `detectedChangeTypes` contains `NEW_FEATURE` | `CREATE_TESTS` |
| Existing test files present in the diff | `UPDATE_TESTS` |
| Default | `CREATE_TESTS` |

### Fallback Rules (applied after primary decision)

| Trigger | Effect | Flag Set |
|---------|--------|----------|
| `confidenceScore < 0.4` | Full regression suite required | `fullRegressionRequired = true` |
| `riskLevel == HIGH` or `CRITICAL` | Widen test scope to all impacted + transitive deps | `expandedScope = true` |

Both flags are propagated on `TestStrategy` so `BddGenerator` can include
regression/smoke tags and `CodegenService` can request additional test coverage.

### UPDATE_TESTS Handler (formerly `TestUpdater`)
`TestUpdater` is deleted. The update scenario logic lives as a private method
`handleUpdateTests()` inside `StrategyAgent` — no extra class, no extra indirection.

---

## BddGenerator

Template-based only — no LLM calls. For each `TestRequirement`:

1. **Happy path scenario** — 200 response, within 2000ms
2. **Error path scenario** — 4xx/5xx, descriptive error message
3. **Boundary outline** *(only if `API_CHANGE` detected)* — parameterised table covering
   valid/empty/null/oversize inputs

Tags added automatically: `@api|@ui|@mobile`, `@pr-{prId}`, `@auto-generated`,
`@smoke` (if HIGH/CRITICAL risk).

The generated `BddScenario` is logged via `TestPrService.createBddPr()` — a simulated
PR creation. **Codegen does not start until the BDD PR is explicitly approved.**

---

## Code Generation Pipeline

```
BddScenario
    │
    ▼
CodegenService.generateAndExecute(scenario)
    │
    ├─ testType == "API"    → ApiTestRunner    → RestAssured + JUnit 5
    ├─ testType == "UI"     → UITestRunner     → Selenium + ChromeDriver (headless)
    └─ testType == "MOBILE" → MobileTestRunner → Appium + AndroidDriver
    │
    ▼
TestScript (scriptContent + metadata)
    │
    ▼
StabilizationLoop.execute(script)
```

### Generated Test Targets

| Type | Framework | Assertion Library |
|------|-----------|------------------|
| API | RestAssured | AssertJ |
| UI | Selenium WebDriver | AssertJ |
| Mobile | Appium (AndroidDriver) | AssertJ |

---

## Stabilisation Loop

Bounded retry with incremental auto-fix heuristics. Never open-ended.

| Attempt | Fix Applied |
|---------|------------|
| 1 | Add connection timeouts; add `@Timeout` annotation for slow tests |
| 2 | Wrap assertions in a retry block; add null guards on response |
| 3 | Replace with a minimal smoke test (just checks endpoint reachability) |

After any passing attempt: `TestPrService.createFinalTestPr()` is called.  
After 3 failures: script marked `ABANDONED`, PR still raised for human review.

---

## Human Review Gates

The pipeline has **two deliberate human checkpoints:**

```
BDD Scenarios generated
        │
        ▼  (logged as "BDD Review PR")
   ◄ HUMAN REVIEW ►
        │  POST /api/strategy/approve-bdd
        ▼
Code generation starts
        │
        ▼  (logged as "Final Test PR")
   ◄ HUMAN REVIEW ►
        │
        ▼
Tests merged to repo
```

Both PRs are currently **simulated** via `TestPrService` (logs the PR body).
Swap the stub for real GitHub/GitLab API calls to go live.

---

## Kafka Flow

| Direction | Topic | Payload |
|-----------|-------|---------|
| **Consumes** | `ImpactResultsQueue` | `ImpactEnvelope` JSON |
| **Produces** | `TestScriptsQueue` | `BddScenario` JSON (after approve-bdd) |

---

## API Endpoints

### `GET /api/strategy/status`
```json
{ "service": "strategy-service", "status": "OPERATIONAL", "timestamp": "..." }
```

### `POST /api/strategy/approve-bdd`
Simulates a human merging the BDD Review PR. Publishes the `BddScenario` to
`TestScriptsQueue`, which triggers the codegen pipeline.

**Request:** Full `BddScenario` JSON (copy from logs when `createBddPr` is called).

**Response `202 Accepted`:**
```json
{
  "status": "CODEGEN_TRIGGERED",
  "prId": "PR-A1B2C3D4",
  "scenarioId": "...",
  "scenarios": 4,
  "message": "BDD approved — code generation pipeline started"
}
```

---

## Configuration (`application.yaml`)

```yaml
server:
  port: 8082
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: strategy-service-group
kafka:
  topics:
    impact-results: ImpactResultsQueue
    test-scripts: TestScriptsQueue
aiqa:
  stabilization:
    max-retries: 3
    retry-delay-ms: 2000
  strategy:
    risk-threshold-high: 0.7
    risk-threshold-medium: 0.4
logging:
  level:
    qaisystem: DEBUG
```

---

## Extending to Real AI

The `StrategyAgent` decision logic is currently rule-based. To add an LLM:

1. Add a `LlmService` bean with an `ask(prompt)` method
2. Inject it into `StrategyAgent`
3. Replace `computeDecision()` with an LLM prompt that includes the `ImpactEnvelope` summary
4. Keep the fallback rules — they act as safety nets regardless of the LLM output

The `BddGenerator` can similarly be upgraded: replace the template builders with
a prompt that feeds `TestRequirement` + `ImpactEnvelope.changesSummary` to the LLM.

