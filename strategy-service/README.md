# strategy-service

**Port:** `8082`  
**Phases:** 2–6 — Strategy, BDD Generation, Code Generation, Test Stabilisation  
**Package root:** `nz.co.eroad.qaisystem`  
**Role:** Consumes `ImpactEnvelope` from Kafka, makes the only AI-style decision in the
system (minimal, rule-based), generates BDD scenarios, produces executable test code, runs a
bounded retry-and-fix stabilisation loop, then raises human-review PRs.

> **Minimal AI.** The strategy decision is rule-based (no LLM). The only "intelligence" is
> the prioritised decision tree in `StrategyAgent`. Everything else is deterministic templates.

---

## Table of Contents

1. [Package Structure](#package-structure)
2. [End-to-End Data Flow](#end-to-end-data-flow)
3. [Class-by-Class Breakdown](#class-by-class-breakdown)
   - [Kafka Layer](#kafka-layer)
   - [Agent Layer](#agent-layer)
   - [Execution Layer](#execution-layer)
   - [Service Layer](#service-layer)
   - [Config Layer](#config-layer)
4. [Strategy Decision Logic (Full Detail)](#strategy-decision-logic-full-detail)
5. [RepoContextService Deep Dive](#repocontextservice-deep-dive)
6. [StabilizationLoop Deep Dive](#stabilizationloop-deep-dive)
7. [Kafka Topics](#kafka-topics)
8. [API Endpoints](#api-endpoints)
9. [Configuration](#configuration)

---

## Package Structure

```
nz/co/eroad/qaisystem/
├── strategy/
│   └── StrategyServiceApplication.java    ← Spring Boot entry point
├── config/
│   ├── KafkaConfig.java                   ← Explicit consumer/producer/factory beans
│   └── TargetRepoProperties.java          ← @ConfigurationProperties for aiqa.target-repo
├── kafka/
│   ├── ImpactResultsConsumer.java         ← Consumes ImpactEnvelope → triggers StrategyAgent
│   ├── TestScriptsConsumer.java           ← Consumes BddScenario → triggers CodegenService
│   └── TestScriptsProducer.java           ← Publishes BddScenario to TestScriptsQueue
├── agent/
│   ├── StrategyAgent.java                 ← Decision + fallback rules + UPDATE handler
│   └── BddGenerator.java                  ← Template-based Gherkin scenario builder
├── execution/
│   ├── CodegenService.java                ← Routes BDD → API/UI/Mobile runner → stabilisation
│   ├── ApiTestRunner.java                 ← Generates RestAssured + JUnit 5 test code
│   ├── UITestRunner.java                  ← Generates Selenium test code
│   ├── MobileTestRunner.java              ← Generates Appium test code
│   ├── TestExecutionEngine.java           ← Simulates (or runs) the generated test
│   ├── StabilizationLoop.java             ← Run → fail → fix (max 3× bounded loop)
│   └── RepoContext.java                   ← Value object: context extracted from target repo
├── service/
│   ├── RepoContextService.java            ← Clones target repo, scans test conventions
│   └── TestPrService.java                 ← Simulates creating GitHub/GitLab PRs
└── controller/
    └── StrategyController.java            ← /status, /approve-bdd, /refresh-context
```

---

## End-to-End Data Flow

```
  Kafka: ImpactResultsQueue
          │  ImpactEnvelope JSON
          ▼
  ┌─────────────────────────────────────────────────────────────────────┐
  │ ImpactResultsConsumer                                               │
  │  deserialise → strategyAgent.decide(envelope)                       │
  └──────────────────────────────┬──────────────────────────────────────┘
                                 │
                                 ▼
  ┌─────────────────────────────────────────────────────────────────────┐
  │ StrategyAgent.decide(ImpactEnvelope)                                │
  │                                                                     │
  │  computeDecision(envelope)  → SKIP | UPDATE_TESTS | CREATE_TESTS   │
  │  buildStrategy(...)         → TestStrategy (requirements, types)    │
  │  applyFallbackRules(...)    → may set fullRegression / expandedScope│
  │                                                                     │
  │        ┌──────────┬─────────────────┬─────────────────────┐        │
  │     SKIP          UPDATE_TESTS       CREATE_TESTS           │        │
  │     (log only)  handleUpdateTests   bddGenerator.generate  │        │
  │                       │                     │               │        │
  │               testPrService          testPrService          │        │
  │              .createBddPr()         .createBddPr()          │        │
  └──────────────────────────────┬──────────────────────────────────────┘
                                 │  BDD PR logged for human review
                                 │
  ────────── Human reviews BDD PR ─────────────────────────────────────────
                                 │
                                 │  POST /api/strategy/approve-bdd
                                 ▼
  ┌─────────────────────────────────────────────────────────────────────┐
  │ StrategyController.approveBdd()                                     │
  │  testScriptsProducer.publishBddScenario(bddScenario)                │
  └──────────────────────────────┬──────────────────────────────────────┘
                                 │  BddScenario JSON
                                 ▼
  Kafka: TestScriptsQueue
          │
          ▼
  ┌─────────────────────────────────────────────────────────────────────┐
  │ TestScriptsConsumer                                                 │
  │  deserialise → codegenService.generateAndExecute(scenario)          │
  └──────────────────────────────┬──────────────────────────────────────┘
                                 │
                                 ▼
  ┌─────────────────────────────────────────────────────────────────────┐
  │ CodegenService.generateAndExecute(BddScenario)                      │
  │                                                                     │
  │  for each Scenario in BddScenario:                                  │
  │    repoContextService.getContext(testType)  → RepoContext           │
  │    route by testType:                                               │
  │      "API"    → apiTestRunner.generateCode()                        │
  │      "UI"     → uiTestRunner.generateCode()                         │
  │      "MOBILE" → mobileTestRunner.generateCode()                     │
  │    → TestScript (Java source code as string)                        │
  │    stabilizationLoop.execute(script)                                │
  └──────────────────────────────┬──────────────────────────────────────┘
                                 │
                                 ▼
  ┌─────────────────────────────────────────────────────────────────────┐
  │ StabilizationLoop.execute(TestScript)                               │
  │                                                                     │
  │  attempt 1: testExecutionEngine.execute(script, 1)                  │
  │    PASS → testPrService.createFinalTestPr() → DONE                  │
  │    FAIL → applyFix(attempt=1) → sleep(retryDelayMs)                 │
  │  attempt 2: testExecutionEngine.execute(script, 2)                  │
  │    PASS → testPrService.createFinalTestPr() → DONE                  │
  │    FAIL → applyFix(attempt=2)                                       │
  │  attempt 3: testExecutionEngine.execute(script, 3)                  │
  │    PASS → testPrService.createFinalTestPr(STABILIZED) → DONE        │
  │    FAIL → status=ABANDONED → testPrService.createFinalTestPr()      │
  └─────────────────────────────────────────────────────────────────────┘
```

---

## Class-by-Class Breakdown

### Kafka Layer

#### ImpactResultsConsumer
**`kafka/ImpactResultsConsumer.java`**

Listens on `ImpactResultsQueue` with `concurrency=3`. Per message:
1. Deserialise JSON → `ImpactEnvelope`
2. `strategyAgent.decide(envelope)` — runs the full decision + BDD + PR creation chain
3. `ack.acknowledge()` — commits offset regardless of success or failure

On exception: logs the error and still acknowledges (poison-pill prevention).

---

#### TestScriptsConsumer
**`kafka/TestScriptsConsumer.java`**

Listens on `TestScriptsQueue` with `concurrency=3`. Per message:
1. Deserialise JSON → `BddScenario`
2. `codegenService.generateAndExecute(scenario)` — runs the full codegen + stabilisation chain
3. `acknowledgment.acknowledge()` — commits offset

Triggered by `POST /api/strategy/approve-bdd`, not by internal logic.

---

#### TestScriptsProducer
**`kafka/TestScriptsProducer.java`**

Single public method `publishBddScenario(BddScenario)`:
- Serialises to JSON
- `kafkaTemplate.send(topic, prId, json)` — keyed by `prId` for partition locality
- `.whenComplete()` logs partition + offset on success

Called by `StrategyController.approveBdd()` — this is the human-gate trigger.

---

### Agent Layer

#### StrategyAgent
**`agent/StrategyAgent.java`**

The single decision-maker. Three responsibilities:
1. **`computeDecision(envelope)`** — rules applied in priority order (see [full decision tree](#strategy-decision-logic-full-detail))
2. **`buildStrategy(envelope, decision)`** — constructs `TestStrategy` with test requirements, test types (`API` or `UI` by component type), scenario hints per component, confidence score, and priority (P0–P3)
3. **`applyFallbackRules(strategy, envelope)`** — two safety-net rules applied after the base decision

**Test type resolution:**
| ComponentType | Test type |
|--------------|-----------|
| CONTROLLER, SERVICE, REPOSITORY | `API` |
| anything else | `UI` |

**Confidence score formula:**
```
base = min(1.0, filesChanged / 10.0)
confidence = min(1.0, (base + overallRiskScore) / 2.0)
```

**Priority mapping:**
| RiskLevel | Priority |
|-----------|---------|
| CRITICAL | P0 |
| HIGH | P1 |
| MEDIUM | P2 |
| LOW | P3 |

**`UPDATE_TESTS` handler** (merged from deleted `TestUpdater`): for each existing test file in the diff, generates a minimal BDD update scenario with `GIVEN/WHEN/THEN` focused on "existing assertions still hold + new behaviour is validated". Calls `testPrService.createBddPr()`.

---

#### BddGenerator
**`agent/BddGenerator.java`**

Purely template-based. No intelligence, no ML, no LLM.

For each `TestRequirement` in the `TestStrategy`, generates up to **3 scenarios**:

| Scenario | Type | When generated |
|---------|------|---------------|
| Happy path | `Scenario` | Always |
| Error handling | `Scenario` | Always |
| Boundary testing | `Scenario Outline` | Only when `API_CHANGE` in detected change types |

**Tags added:**
- `@api` / `@ui` / `@mobile` (from test type)
- `@pr-{prId}`
- `@auto-generated`
- `@smoke` (if risk is HIGH or CRITICAL)

After building the `BddScenario`, calls `testPrService.createBddPr(bdd)` which logs the
full Gherkin to the console as a formatted PR payload for human review.

---

### Execution Layer

#### CodegenService
**`execution/CodegenService.java`**

Routes each `BddScenario.Scenario` to the appropriate test runner based on `testType`:

| `testType` | Runner | Generated framework | Default dependencies |
|-----------|--------|-------------------|--------------------|
| `API` | `ApiTestRunner` | RestAssured + JUnit 5 + AssertJ | `restassured`, `junit5`, `assertj` |
| `UI` | `UITestRunner` | Selenium + ChromeDriver | `selenium`, `webdriver-manager` |
| `MOBILE` | `MobileTestRunner` | Appium + AndroidDriver | `appium`, `selenium` |
| *(default)* | `ApiTestRunner` | same as API | same as API |

Also calls `repoContextService.getContext(type)` before routing — the `RepoContext` is
passed to the runner so generated code uses the target repo's package structure, base class,
common imports, and naming convention instead of built-in defaults.

File naming: if repo uses prefix convention (`Test*.java`) → `Test{SafeName}.java`, otherwise
`{SafeName}{TypeInitial}Test.java` (e.g. `Payment_gateway_addApiTest.java`).

---

#### ApiTestRunner / UITestRunner / MobileTestRunner
**`execution/ApiTestRunner.java`** (UI and Mobile follow the same pattern)

Pure code-generation — no execution, no I/O. Takes a `BddScenario.Scenario` +
`BddScenario` (parent) + `RepoContext` and returns a Java source string.

**The generated file includes (when context is available):**
1. A comment block at the top containing the full content of all `.github/agents/*.md` files
   (agent instructions) — or sample test references if no agent files exist
2. The repo's actual package name
3. The repo's common imports (those appearing in ≥50% of existing test files)
4. `extends {baseTestClass}` if a base class was detected
5. `@BeforeEach setUp()` setting `RestAssured.baseURI`
6. `@Test` method with GIVEN/WHEN/THEN comments and assertions derived from the BDD steps

**Step → assertion translation (API runner):**
| BDD `then` step contains | Generated assertion |
|--------------------------|-------------------|
| `"200"` | `.isEqualTo(200)` |
| `"4xx"` or `"5xx"` | `.isGreaterThanOrEqualTo(400)` |
| `"2000ms"` | `response.time().isLessThan(2000L)` |
| anything else | `// {step}` (comment placeholder) |

---

#### TestExecutionEngine
**`execution/TestExecutionEngine.java`**

Simulates test execution (current implementation). In production would:
- Write the script to disk
- Compile with `javax.tools.JavaCompiler`
- Run via JUnit Platform Launcher or Maven Surefire subprocess
- Parse Surefire XML / stdout for results

**Current simulation (probabilistic pass rates):**
| Attempt | Pass probability |
|---------|----------------|
| 1 | 60% |
| 2 | 80% |
| 3 | 95% |

Returns a `TestResult` with `passed`, `errorMessage`, `failureReasons`, `executionTimeMs`.

---

#### StabilizationLoop
**`execution/StabilizationLoop.java`**

Bounded retry loop. `maxRetries` and `retryDelayMs` are configurable (defaults: 3 retries, 2000ms).

**Flow:**
```
for attempt = 1 to maxRetries:
  result = testExecutionEngine.execute(script, attempt)
  if result.passed:
    script.status = PASSED
    testPrService.createFinalTestPr(script, result)
    return result
  if attempt < maxRetries:
    script = applyFix(script, result, attempt)
    sleep(retryDelayMs)

script.status = ABANDONED
testPrService.createFinalTestPr(script, result)   ← still raises PR for human review
return result
```

**Per-attempt auto-fix heuristics:**

| Attempt | Fix applied | Trigger condition |
|---------|------------|------------------|
| 1 | Add connection timeout + `@Timeout(30s)` | error contains "Connection refused"/"503"/"timeout" |
| 2 | Wrap assertions in retry block + add null guards | error contains "AssertionError" |
| 3 | Replace entire test with minimal smoke test | always (last resort) |

Attempt 3 generates a new class `{OriginalClass}_Stabilized` in a `stabilized` subpackage
that only calls `GET /api/v1/health` and asserts `statusCode().isBetween(100, 599)`.
The original error is recorded as a comment at the top.

The stabilisation loop always creates a final PR regardless of outcome — a `⚠️ [NEEDS REVIEW]`
PR is raised even on `ABANDONED` so humans can see and fix the partial code.

---

#### RepoContext
**`execution/RepoContext.java`**

Immutable value object (Lombok `@Data @Builder`) carrying everything the runners need from
the target test repo. Priority of context sources (documented in class Javadoc):
1. `agentInstructions` (`.github/agents/*.md`) — highest precedence
2. Heuristically scanned: `basePackage`, `baseTestClass`, `commonImports`, `testNamingConvention`, `sampleTests`
3. Built-in templates when `contextAvailable == false`

Key convenience methods:
- `effectivePackage(fallback)` — returns `basePackage` or fallback if no context
- `extendsClause()` — returns `" extends {baseTestClass}"` or `""`
- `commonImportsBlock()` — formats common imports as Java import statements
- `contextHeader()` — returns agent instructions block comment (or sample test comment)

---

### Service Layer

#### RepoContextService
**`service/RepoContextService.java`**

See [dedicated section below](#repocontextservice-deep-dive).

---

#### TestPrService
**`service/TestPrService.java`**

Simulates raising PRs for human review. In production would call GitHub/GitLab/Bitbucket APIs.
Currently logs the full PR payload as formatted JSON to the console.

Two public methods:

**`createBddPr(BddScenario)`** — raises a BDD review PR:
- Branch: `qa/bdd/{prId}-{scenarioId(6chars)}`
- Title: `[AI-QA] BDD Scenarios for PR: {prId}`
- Body: formatted Gherkin (`Feature:`/`Scenario:`/`Given`/`When`/`Then`) + review checklist
- Type: `BDD_REVIEW`

**`createFinalTestPr(TestScript, TestResult)`** — raises a final test code PR:
- Branch: `qa/tests/{prId}-{scriptId(6chars)}`
- Title: `✅ [PASSING]` / `✅ [STABILIZED]` / `⚠️ [NEEDS REVIEW]` based on result
- Body: execution summary table, failure details (if failed), full generated Java source, review checklist
- Type: `FINAL_TEST_CODE`

---

### Config Layer

#### KafkaConfig
**`config/KafkaConfig.java`**

Identical purpose to impact-service's `KafkaConfig`: explicitly declares
`kafkaListenerContainerFactory` (with `MANUAL_IMMEDIATE` ack, concurrency=3),
`ConsumerFactory`, `ProducerFactory`, and `KafkaTemplate` — required because Spring Boot
auto-config does not create the factory bean when manual ack is configured.

---

#### TargetRepoProperties
**`config/TargetRepoProperties.java`**

`@ConfigurationProperties(prefix = "aiqa.target-repo")` mapping the YAML block into a
typed bean. Three nested classes:

| Inner class | Fields | Purpose |
|-------------|--------|---------|
| `Modules` | `api`, `ui`, `mobile` | Relative paths inside the repo to each test module |
| `Auth` | `type`, `token`, `username` | Auth strategy for cloning |

`isConfigured()` returns `true` only if `url` is non-blank — used in `RepoContextService`
to decide whether to attempt a clone.

`modulePathFor(testType)` resolves `"UI"` → `modules.ui`, `"MOBILE"` → `modules.mobile`,
everything else → `modules.api`.

---

## Strategy Decision Logic (Full Detail)

`StrategyAgent.computeDecision(envelope)` — evaluated in this exact priority order:

```
1. coverage.level == NONE AND missingCoverageAreas not empty
       → CREATE_TESTS   ← hard override, no further rules checked

2. ALL change types are CONFIGURATION_CHANGE or DEPENDENCY_UPDATE
   AND riskLevel == LOW
       → SKIP

3. totalFilesChanged == affectedTestFiles  (PR is only test file changes)
       → SKIP

4. riskLevel == HIGH or CRITICAL
       → CREATE_TESTS

5. NEW_FEATURE in detectedChangeTypes
       → CREATE_TESTS

6. existingTestFiles not empty  (PR includes test file modifications)
       → UPDATE_TESTS

7. (default)
       → CREATE_TESTS
```

**Fallback rules applied after the base decision:**

```
if confidenceScore < 0.4:
    strategy.fullRegressionRequired = true   ← low confidence, run everything

if riskLevel == HIGH or CRITICAL:
    strategy.expandedScope = true
    testAreasTocover += all transitiveDependencies
    testAreasTocover += all impactedComponent names
```

---

## RepoContextService Deep Dive

`RepoContextService` is the only component that connects to an external system
(the target test repository). Everything else in the service is self-contained.

### Lifecycle

```
@PostConstruct initialise()
  if url not configured → log warning, all contexts = unavailable, skip
  else:
    cloneOrPull()     ← git clone (shallow --depth 1) or git pull
    refreshCache()    ← scan three module directories
```

`POST /api/strategy/refresh-context` calls `refresh()` which repeats `cloneOrPull()` +
`refreshCache()` without a restart.

### Clone / Pull

`cloneOrPull()` checks if `.git` directory exists at `localPath`:
- **Exists** → `git pull origin {branch}`
- **Absent** → `git clone {cloneUrl} --branch {branch} --depth 1 {localPath}`

To prevent macOS Keychain popups and interactive prompts, every `git` invocation is wrapped with:
```
git -c credential.helper=  {command}
```
And these env vars are set on the `ProcessBuilder`:
```
GIT_TERMINAL_PROMPT=0
GIT_ASKPASS=echo
```

**Token auth** injects credentials into the HTTPS URL:
```
https://github.com/org/repo
→ https://{username}:{token}@github.com/org/repo
```

### Context Scanning

For `API`, `UI`, and `MOBILE` module paths:
1. Walk directory tree for `*Test.java`, `*Spec.java`, `*IT.java`, `*Test.kt`, `*Spec.kt`
2. Sort by file size ascending (small → representative)
3. Extract `basePackage` (first `package` statement found)
4. Extract `commonImports` (imports in ≥50% of files)
5. Detect `baseTestClass` (first `class X extends Y` found across first 10 files)
6. Detect `testNamingConvention` (`Test*.java` prefix if majority, else `*Test.java` suffix)
7. Load up to 3 `sampleTests` (skipping files >6000 chars)

### Agent Instructions

Scans `{repoRoot}/.github/agents/` for `.md`, `.txt`, and `.instructions.md` files.
Distributes to test types by filename keyword:

| Filename contains (case-insensitive) | Goes to |
|--------------------------------------|---------|
| `api` | API |
| `ui`, `web`, `selenium`, `playwright` | UI |
| `mobile`, `appium`, `android`, `ios` | MOBILE |
| none of the above | **ALL THREE** (shared conventions) |

If the module directory doesn't exist but agent files were found, the context is still
marked `contextAvailable=true` — the runners will use agent instructions as their template.

---

## StabilizationLoop Deep Dive

### Attempt 1 — Timeout & connection fixes

Triggered by: `"Connection refused"`, `"503"`, `"timeout"`, `"timed out"` in error message.

- Inserts `RestAssured.config` with `http.connection.timeout=5000`, `http.socket.timeout=10000`
- Replaces `@Test` with `@Test @Timeout(value=30, unit=SECONDS)`
- Adds missing imports for `TimeUnit` and `Timeout`

### Attempt 2 — Retry wrapper + null guards

Applies all attempt-1 fixes first, then:

- If `"AssertionError"` in error: wraps the `// THEN` block in a 3-iteration retry loop
  with `Thread.sleep(1000)` and catch/rethrow on final failure
- Always adds a null check before `assertThat(response.statusCode())`:
  `assertThat(response).isNotNull();`

### Attempt 3 — Minimal smoke test

Ignores the original content entirely. Generates a fresh class `{OriginalClass}_Stabilized`
that only calls `GET /api/v1/health` and asserts `statusCode().isBetween(100, 599)`.
The original error is recorded as a comment at the top.

The stabilisation loop always creates a final PR regardless of outcome — a `⚠️ [NEEDS REVIEW]`
PR is raised even on `ABANDONED` so humans can see and fix the partial code.

---

## Kafka Topics

| Direction | Topic | Payload |
|-----------|-------|---------|
| **Consumes** | `ImpactResultsQueue` | `ImpactEnvelope` JSON (from impact-service) |
| **Produces** | `TestScriptsQueue` | `BddScenario` JSON |
| **Consumes** | `TestScriptsQueue` | `BddScenario` JSON (self-loop via human approval gate) |
| *(future)* | `TestResultsQueue` | `TestResult` JSON |

Consumer group: `strategy-service-group`

---

## API Endpoints

### `GET /api/strategy/status`
```json
{ "service": "strategy-service", "status": "OPERATIONAL", "timestamp": "..." }
```

### `POST /api/strategy/approve-bdd`
Human gate — call this with the `BddScenario` JSON logged by the service when it creates the
BDD review PR. This publishes the scenario to `TestScriptsQueue`, triggering codegen.

**Body:** the full `BddScenario` JSON from the service logs (copy from log line starting with
`[TestPrService] BDD Review PR created:`).

```bash
curl -X POST http://localhost:8082/api/strategy/approve-bdd \
  -H "Content-Type: application/json" \
  -d '{ "scenarioId": "...", "prId": "PR-XXXX", "scenarios": [...], ... }'
```

### `POST /api/strategy/refresh-context`
Re-runs `git pull` on the target repo and refreshes the RepoContext cache without restart.

```bash
curl -X POST http://localhost:8082/api/strategy/refresh-context
```

**Response:**
```json
{ "status": "OK", "api": "12 tests, pkg=com.example.tests.api, agentFiles=2", "ui": "...", "mobile": "..." }
```
Or `"status": "SKIPPED"` if no target repo is configured.

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
      auto-offset-reset: earliest
      enable-auto-commit: false

kafka:
  topics:
    impact-results: ImpactResultsQueue    # consumed
    test-scripts: TestScriptsQueue        # produced & consumed

aiqa:
  stabilization:
    max-retries: 3              # max fix-and-retry attempts per script
    retry-delay-ms: 2000        # ms to wait between attempts
  strategy:
    risk-threshold-high: 0.7    # score >= this → HIGH risk
    risk-threshold-medium: 0.4  # score >= this → MEDIUM risk
  target-repo:
    url: "https://github.com/your-org/your-test-repo"
    branch: main
    local-path: /tmp/qa-context-repo
    auth:
      type: token               # none | token | ssh
      token: ${TARGET_REPO_TOKEN:}
      username: ${TARGET_REPO_USERNAME:}
    modules:
      api: tests/api            # relative path inside the repo
      ui: tests/ui
      mobile: tests/mobile

logging:
  level:
    nz.co.eroad.qaisystem: DEBUG
    org.apache.kafka: WARN
```
