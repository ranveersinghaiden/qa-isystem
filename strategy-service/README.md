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
     - [E2ECoverageAnalyzer](#e2ecoverageanalyzer)
     - [RepoContextService](#repocontextservice)
     - [TestPrService](#testprservice)
   - [Config Layer](#config-layer)
4. [Strategy Decision Logic (Full Detail)](#strategy-decision-logic-full-detail)
5. [Two-Phase Coverage Assessment](#two-phase-coverage-assessment)
6. [RepoContextService Deep Dive](#repocontextservice-deep-dive)
7. [StabilizationLoop Deep Dive](#stabilizationloop-deep-dive)
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
├── github/
│   ├── GitHubService.java                 ← GitHub REST API v3 client (branches, files, PRs)
│   └── BddScenarioStore.java              ← In-memory branch→BddScenario map (webhook bridge)
├── execution/
│   ├── CodegenService.java                ← Routes BDD → API/UI/Mobile runner → stabilisation
│   ├── ApiTestRunner.java                 ← Generates RestAssured + JUnit 5 test code
│   ├── UITestRunner.java                  ← Generates Selenium test code
│   ├── MobileTestRunner.java              ← Generates Appium test code
│   ├── TestExecutionEngine.java           ← Compiles and runs generated tests (JavaCompiler + JUnit Platform)
│   ├── StabilizationLoop.java             ← Run → fail → fix (max 3× bounded loop)
│   └── RepoContext.java                   ← Value object: context extracted from target repo
├── service/
│   ├── E2ECoverageAnalyzer.java           ← Scans cloned test repo; produces GOOD/PARTIAL/NONE coverage level
│   ├── RepoContextService.java            ← Clones target repo, builds coverage index, scans test conventions
│   └── TestPrService.java                 ← Creates real GitHub PRs (BDD Review + Final Test Code)
└── controller/
    ├── StrategyController.java            ← /status, /approve-bdd, /refresh-context
    └── GitHubWebhookController.java       ← /github-webhook — handles pull_request merged events
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
   │  ① e2eCoverageAnalyzer.analyze(envelope)                            │
   │       ├─ repoContextService.getCoverageIndex()                       │
   │       ├─ if index empty → propagate UNKNOWN (no repo configured)    │
   │       └─ if index present → scan: GOOD / PARTIAL / NONE             │
   │                                                                     │
   │  ② computeDecision(envelope, realCoverage)                          │
   │       → SKIP | UPDATE_TESTS | CREATE_TESTS                          │
   │  ③ buildStrategy(...)       → TestStrategy                          │
   │  ④ applyFallbackRules(...)  → may set fullRegression/expandedScope  │
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
                  ┌───────────────┴──────────────────────┐
                  │                                       │
         Path A — GitHub webhook                 Path B — manual endpoint
         (production)                            (local dev / testing)
                  │                                       │
    BDD PR merged on GitHub               POST /api/strategy/approve-bdd
    → POST /api/strategy/github-webhook    with the BddScenario JSON
                  │                                       │
   GitHubWebhookController              StrategyController.approveBdd()
   verifySignature()                            │
   bddScenarioStore.findByBranch()             │
                  └───────────────┬─────────────┘
                                  │  testScriptsProducer.publishBddScenario()
                                  ▼
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

Compiles and executes generated test code in an isolated temp directory using the Java
Compiler API and JUnit Platform Launcher. No simulation — every attempt is a real
compilation + runtime execution.

**Execution flow per attempt:**

```
1. Write generated .java source to a temp dir preserving the package structure
   (e.g. /tmp/qa-gen-{scriptId}-a{N}-/src/nz/co/eroad/.../PaymentTest.java)

2. Compile with javax.tools.JavaCompiler
   — Classpath: the running JVM classpath (includes RestAssured, JUnit 5,
     Cucumber, Selenium, Appium — all added as compile-scope runtime deps)
   — On failure: return TestResult(passed=false, output="COMPILE_ERROR",
     errorMessage=diagnostics) so StabilizationLoop can apply a targeted fix

3. Load compiled class with URLClassLoader

4. Execute via JUnit Platform Launcher with explicit JupiterTestEngine
   — avoids ServiceLoader problems inside Spring Boot's nested-JAR classloader
   — SummaryGeneratingListener captures: started/passed/failed/skipped counts
     and the exception message for each failure

5. Delete temp dir (always, in finally block)
```

**Returns:** `TestResult` with `passed`, `output` ("N started, N passed, N failed, N skipped"), `errorMessage`, `failureReasons`, `executionTimeMs`.

**Compile failure result:** `output = "COMPILE_ERROR"`, `errorMessage` = full compiler diagnostics (file:line message format) — `StabilizationLoop` pattern-matches against this to pick the right fix.

> **JDK required at runtime.** `ToolProvider.getSystemJavaCompiler()` returns `null` if the
> service runs on a JRE-only image. Use the `eclipse-temurin:25-jdk` base image (already set
> in the Dockerfiles) to ensure the compiler is available in production.

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

#### E2ECoverageAnalyzer
**`service/E2ECoverageAnalyzer.java`**

Produces the **real** integration/E2E coverage assessment by cross-referencing the impacted
components against the **coverage index** built by `RepoContextService`. This class replaces
the `UNKNOWN`-level report from impact-service with a concrete `GOOD / PARTIAL / NONE` level
before `StrategyAgent` makes its decision.

**Main method: `analyze(ImpactEnvelope envelope)`**

```
1. coverageIndex = repoContextService.getCoverageIndex()
   (component name → list of test files that reference it)

2. Filter envelope.impactedComponents to integration-testable types:
   CONTROLLER, SERVICE, REPOSITORY, CONFIG

3. If no testable components → return GOOD (nothing needs integration tests)

4. If coverageIndex is empty (no test repo configured):
   → propagate the UNKNOWN report from impact-service (conservative fallback)

5. For each testable component:
   tests = coverageIndex.get(componentName)
   if tests non-empty  → add to testedComponents + collect to existingTestFiles
   if tests empty      → add to untestedComponents

6. Derive coverage level:
   uncovered empty          → GOOD
   covered >= uncovered     → PARTIAL
   otherwise                → NONE

7. Return CoverageReport with source=REPO_SCAN
```

**Coverage level definitions:**

| Level | Meaning |
|-------|---------|
| `GOOD` | All changed integration-testable components already have integration/E2E tests in the repo |
| `PARTIAL` | Some components are covered; at least half are covered |
| `NONE` | No integration/E2E tests found for any of the changed components |
| `UNKNOWN` | No test repo configured — `RepoContextService` returned an empty index |

---

#### RepoContextService
**`service/RepoContextService.java`**

See [dedicated section below](#repocontextservice-deep-dive).

---

#### TestPrService
**`service/TestPrService.java`**

Creates real Pull Requests on the target GitHub repository using the `GitHubService` REST
client. No simulation — every call makes live GitHub API calls.

Throws `IllegalStateException` at call time if `GitHubService.isConfigured()` is false
(i.e. `TARGET_REPO_URL` was not set). Throws `GitHubPrException` (inner class) if any
API call fails so the caller gets an actionable error rather than silent failure.

**`createBddPr(BddScenario)`** — raises a BDD review PR:
1. Create branch `qa/bdd/{prId}-{scenarioId(6chars)}` from `main` HEAD SHA
2. Commit `scenarios/{prId}.feature` with the Gherkin content (base64 encoded)
3. Open PR titled `[AI-QA] BDD Scenarios for PR: {prId}` with review checklist body
4. Register scenario in `BddScenarioStore` keyed by branch name (enables webhook lookup)
5. Return the PR HTML URL (e.g. `https://github.com/org/repo/pull/42`)

**`createFinalTestPr(TestScript, TestResult)`** — raises a final test code PR:
1. Create branch `qa/tests/{prId}-{scriptId(6chars)}` from `main`
2. Commit `src/test/java/{package}/{fileName}` with the generated Java source
3. Open PR titled `✅ [PASSING]` / `✅ [STABILIZED]` / `⚠️ [NEEDS REVIEW]` based on result
4. Body includes execution summary table, failure details (if failed), full generated Java source, review checklist

> **Requires GitHub configuration.** Set `TARGET_REPO_URL` and ensure a token is available
> via `TARGET_REPO_TOKEN` or the system git credential helper. See `GitHubService` below.

---

#### GitHubService
**`github/GitHubService.java`**

Thin REST client wrapping the GitHub API v3.  Constructed as a Spring `@Service` singleton
and injected into `TestPrService`.

**Token resolution — priority order:**

| Priority | Source | Notes |
|----------|--------|-------|
| 1 | `TARGET_REPO_TOKEN` env var | Explicit PAT — recommended for CI/production |
| 2 | `git credential fill` | Reads from the system credential helper. On macOS this is **osxkeychain**, which holds the token IntelliJ wrote when you connected it to GitHub — so local dev works with zero extra setup as long as IntelliJ is signed in. |
| 3 | *(none)* | Service fails startup if `TARGET_REPO_URL` is set but no token was resolved |

**Startup validation (`@PostConstruct validateConfiguration()`):**
Throws `IllegalStateException` if a URL is configured but no token could be resolved.
If no URL is configured at all, the check is skipped — the service starts without GitHub
integration (PR creation will throw `IllegalStateException` at runtime if invoked).

**Public API:**

| Method | Description |
|--------|-------------|
| `isConfigured()` | `true` when URL + token both available |
| `createBranch(name, base)` | POST `/git/refs` — returns `true` on success or 422 (already exists) |
| `createFile(branch, path, content, msg)` | PUT `/contents/{path}` — base64 encodes content |
| `createPullRequest(title, body, head, base)` | POST `/pulls` — returns `GitHubPrResult(prNumber, url, branch)` |

All methods return `false` / `null` (not throw) when `isConfigured()` is false, so callers
(`TestPrService`) can check and throw their own typed exceptions.

---

#### BddScenarioStore
**`github/BddScenarioStore.java`**

In-memory `ConcurrentHashMap<branchName, BddScenario>` that bridges the time between
opening a BDD Review PR (in `TestPrService`) and receiving the GitHub merge webhook
(in `GitHubWebhookController`).

**Lifecycle of an entry:**

```
1. TestPrService.createBddPr()
     → bddScenarioStore.put("qa/bdd/PR-xxx-abc123", scenario)

2. (human reviews and merges the PR on GitHub)

3. GitHubWebhookController.handleWebhook()
     → bddScenarioStore.findByBranch("qa/bdd/PR-xxx-abc123")
     → testScriptsProducer.publishBddScenario(scenario)
     → bddScenarioStore.remove("qa/bdd/PR-xxx-abc123")
```

Also exposes `size()` for observability — the number of BDD PRs currently awaiting human
review.

---

### Config Layer

#### KafkaConfig

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

`StrategyAgent.computeDecision(envelope, coverage)` — `coverage` comes from **E2ECoverageAnalyzer**
(real repo scan), **not** from the UNKNOWN-level report in the ImpactEnvelope.
Evaluated in this exact priority order:

```
1. coverage.level == NONE AND coverage.untestedComponents not empty
       → CREATE_TESTS   ← hard override: repo confirmed zero integration tests exist

2. ALL change types are CONFIGURATION_CHANGE or DEPENDENCY_UPDATE
   AND riskLevel == LOW
       → SKIP

3. totalFilesChanged == affectedTestFiles  (PR is only test file changes)
       → SKIP

4. riskLevel == HIGH or CRITICAL
       → CREATE_TESTS

5. NEW_FEATURE in detectedChangeTypes
       → CREATE_TESTS

6. coverage.level == PARTIAL
       → UPDATE_TESTS   ← some tests exist; extend them rather than creating from scratch

7. existingTestFiles not empty  (PR itself includes test file modifications)
       → UPDATE_TESTS

8. (default)
       → CREATE_TESTS
```

> **Note on UNKNOWN level:** When no test repo is configured, coverage stays `UNKNOWN`.
> The strategy then falls through to rules 4–8 — so it still creates tests, just without
> knowing which ones already exist.

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

## Two-Phase Coverage Assessment

This section explains the architectural choice of splitting coverage assessment across two services.

```
 impact-service                          strategy-service
      │                                       │
      │  Has: PR diff + component types       │  Has: cloned test repo
      │  No:  test repo access               │  Has: coverage index (inverted)
      │                                       │
      │  Phase 1 output:                      │  Phase 2 output:
      │  CoverageReport(                      │  CoverageReport(
      │    level=UNKNOWN,         ──Kafka──►  │    level=GOOD/PARTIAL/NONE,
      │    untestedComponents=[…],            │    testedComponents=[…],
      │    requiredTestTypes=[…]              │    untestedComponents=[…],
      │  )                                    │    existingTestFiles=[…]
      │                                       │  )
```

**Why not just check the repo in impact-service?**
impact-service is designed to be stateless and fast — it runs as a streaming processor.
Cloning a git repo at startup and keeping it fresh would make it stateful and slow.
strategy-service already owns the repo lifecycle, so coverage analysis naturally belongs there.

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
                         builds coverage index per module, then merges
```

`POST /api/strategy/refresh-context` calls `refresh()` which repeats `cloneOrPull()` +
`refreshCache()` without a restart.

### Clone / Pull

`cloneOrPull()` checks if `.git` directory exists at `localPath`:
- **Exists** → `git pull origin {branch}`
- **Absent** → `git clone {cloneUrl} --branch {branch} --depth 1 {localPath}`

#### Credential handling — what actually runs when

The service always sets `GIT_TERMINAL_PROMPT=0` to prevent git from hanging on a keyboard
prompt during startup. Beyond that, behaviour depends on `auth.type`:

| `auth.type` | Token blank? | What happens |
|-------------|-------------|--------------|
| `token` | No | Token embedded in clone URL. `credential.helper=` overridden to prevent macOS Keychain popup (not needed — token is already in URL). `GIT_ASKPASS=echo` set. |
| `token` | Yes | Falls through to `none` behaviour |
| `none` | — | System credential helper runs normally. **osxkeychain on macOS** is used, which is where IntelliJ stores its GitHub token. No extra config needed. |
| `ssh` | — | System SSH agent is used (`~/.ssh/id_rsa` or `id_ed25519`). Credential helper is left intact. |

**This means:** If IntelliJ is already connected to GitHub, just set `auth.type: none`
(or leave it at the default) — the service will automatically use the same Keychain
credentials that IntelliJ uses. No token or SSH key setup required.

**Token auth** injects credentials into the HTTPS URL:
```
https://github.com/org/repo
→ https://{username}:{token}@github.com/org/repo
```

#### Fallback local path

If the remote clone or pull fails for any reason (network, credentials, rate limit),
the service automatically tries a **fallback local directory**. This is configured via:

```yaml
aiqa:
  target-repo:
    fallback-local-path: /Users/yourname/projects/your-test-repo
    fallback-pull: false   # set true to git pull the fallback before scanning
```

Fallback use cases:

| Scenario | Setup |
|----------|-------|
| IntelliJ has the repo cloned locally — skip remote entirely | Set `url: ""`, `fallback-local-path:` your local clone |
| Remote fails at startup — use last known good copy | Set `fallback-local-path` to the cached clone; service uses it silently |
| Air-gapped or offline development | Point fallback at a manually synced directory |
| Keep fallback fresh automatically | Set `fallback-pull: true` — on fallback, git pull is attempted first (failures are logged and ignored) |

Fallback startup sequence:
```
1. Try remote clone/pull (if url is set)
   └─ SUCCESS → scan remote clone for context + coverage index
   └─ FAILURE → log warning, proceed to fallback

2. Try fallback-local-path (if set)
   └─ fallback-pull=true AND it's a git repo → try git pull (ignore failures)
   └─ Scan directory for context + coverage index
   └─ FAILURE → log error, use built-in templates

3. No remote and no fallback:
   └─ log info, use built-in templates (contextAvailable=false)
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

### Coverage Index Building

In addition to context scanning, `refreshCache()` calls `buildCoverageIndex(modulePath)` for each
module and merges the results into a single `coverageIndex` map.

**What is the coverage index?**
```
coverageIndex: Map<String, List<String>>
  key   = component name (e.g. "PaymentController", "OrderService")
  value = list of integration/E2E test file names that reference this component
```
This is an **inverted index** — instead of "test files → what they test", it's
"component name → which test files test it". This structure makes lookup O(1):
`coverageIndex.get("PaymentController")` immediately returns all tests covering it.

**How integration/E2E files are identified (`isIntegrationOrE2eFile`):**

| Detection method | Signal | Priority |
|-----------------|--------|----------|
| File extension `.feature` | Gherkin file = always integration/E2E | Strong (filename) |
| Filename contains `IT`, `IntTest`, `IntegrationTest`, `E2ETest`, `AcceptanceTest` | Naming convention | Strong (filename) |
| Content contains `@SpringBootTest` | Boots full Spring context → integration | Content |
| Content contains `@IntegrationTest` | Explicit annotation | Content |
| Content contains `RestAssured`, `MockMvc`, `WebTestClient`, `TestRestTemplate` | HTTP-level assertion → not a unit test | Content |

> Unit tests (JUnit + Mockito only, no Spring context) are **not** indexed.
> They don't catch integration bugs and would flood the index with noise.

**How component names are extracted (`extractClassReferences`):**

The coverage indexer uses a `PascalCase` regex `\b([A-Z][a-zA-Z0-9]{2,})\b` to extract
every class-name reference from the test file. For example:

```java
// In PaymentServiceIT.java:
@Autowired PaymentService paymentService;
RestAssured.given().post("/api/payments")...
```
Extracts: `PaymentService`, `RestAssured`, `Autowired`, ...

The `EXCLUDED_NAMES` set filters out ~80 known Java/framework names that are not real
application components: `String`, `List`, `Autowired`, `SpringBootTest`, `RestAssured`, etc.
What remains are application-specific class names like `PaymentService`, `PaymentController`.

**Why PascalCase?**
Java class names are always PascalCase. By matching this pattern, the indexer can identify
component references without parsing the full AST. It is a heuristic (not 100% precise),
but correct for the most common naming patterns in enterprise Java codebases.

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
| **Produces** | `TestScriptsQueue` | `BddScenario` JSON — published by `TestScriptsProducer` via two paths: `POST /approve-bdd` (local dev) or GitHub webhook merge event (production) |
| **Consumes** | `TestScriptsQueue` | `BddScenario` JSON — picked up by `TestScriptsConsumer` → `CodegenService` |
| *(future)* | `TestResultsQueue` | `TestResult` JSON |

Consumer group: `strategy-service-group`

---

## API Endpoints

### `GET /api/strategy/status`
```json
{ "service": "strategy-service", "status": "OPERATIONAL", "timestamp": "..." }
```

### `POST /api/strategy/approve-bdd`
Manual codegen trigger for **local development and testing**. Call this with the
`BddScenario` JSON logged by the service when it opens the BDD Review PR.  
This publishes the scenario to `TestScriptsQueue`, triggering the full codegen pipeline.

In production the equivalent trigger is the GitHub webhook (see below) — this endpoint
exists so you can test the pipeline without needing a real GitHub webhook delivery.

**Body:** the full `BddScenario` JSON from the service logs (copy from the log line starting
with `[TestPrService] BDD Review PR #N created:`).

```bash
curl -X POST http://localhost:8082/api/strategy/approve-bdd \
  -H "Content-Type: application/json" \
  -d '{ "scenarioId": "...", "prId": "PR-XXXX", "scenarios": [...], ... }'
```

### `POST /api/strategy/github-webhook`
Receives GitHub `pull_request` webhook events. Used in **production** as the automatic
codegen trigger when a BDD Review PR is merged on GitHub.

**GitHub Setup:**
```
Repository Settings → Webhooks → Add webhook
  Payload URL : https://<your-host>/api/strategy/github-webhook
  Content type: application/json
  Secret      : value of GITHUB_WEBHOOK_SECRET env var
  Events      : Pull requests
```

**Flow:**
1. GitHub fires `pull_request` with `action=closed, merged=true`
2. Controller verifies `X-Hub-Signature-256` HMAC-SHA256 header
3. Looks up the `BddScenario` in `BddScenarioStore` by the merged branch name
4. Publishes to `TestScriptsQueue` → `CodegenService` handles from here

**Response examples:**
```json
{ "status": "CODEGEN_TRIGGERED", "sourcePrId": "PR-XXXX", "scenarioId": "...", "mergedBranch": "qa/bdd/PR-XXXX-abc123" }
{ "status": "NOT_A_QA_PR",       "branch": "feature/some-other-branch" }
{ "status": "IGNORED",           "event": "issues" }
```

> If `GITHUB_WEBHOOK_SECRET` is not set, signature verification is skipped with a warning —
> acceptable for local dev, **must be set in production**.

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

    # Fallback: use a local copy when remote clone/pull fails
    # Set to your IntelliJ checkout path to work without needing a separate clone
    fallback-local-path: ""      # e.g. /Users/yourname/projects/your-test-repo
    fallback-pull: false         # set true to git pull the fallback before scanning

    auth:
      type: none                  # none | token | ssh
                                  # none  = use osxkeychain (IntelliJ credentials work automatically)
                                  # token = embed PAT in URL (best for CI)
                                  # ssh   = use SSH agent (~/.ssh/id_rsa)
      token: ${TARGET_REPO_TOKEN:}
      username: ${TARGET_REPO_USERNAME:}
    modules:
      api: tests/api
      ui: tests/ui
      mobile: tests/mobile

logging:
  level:
    nz.co.eroad.qaisystem: DEBUG
    org.apache.kafka: WARN
```
