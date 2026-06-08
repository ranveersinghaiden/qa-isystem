# strategy-service

**Port:** `8082`  
**Phases:** 2–7 — Strategy, BDD Generation, Code Generation, Test Stabilisation, AI-Native Feedback Loop  
**Package root:** `nz.co.eroad.qaisystem`  
**Role:** Consumes `ImpactEnvelope` from Kafka, makes the only AI-style decision in the
system (minimal, rule-based), generates BDD scenarios (AI or template), produces executable
test code, runs a bounded retry-and-fix stabilisation loop, raises human-review PRs, and
**handles rejection feedback for both BDD and test code PRs** — re-generating improved content
and updating product expert knowledge files when needed.

> **AI-native.** Set `AI_PROVIDER` to select the AI backend:
> - `AI_PROVIDER=openai` (default) + `OPENAI_API_KEY` — uses OpenAI or any OpenAI-compatible endpoint (Azure, Ollama, GitHub Models)
> - `AI_PROVIDER=copilot` + `GITHUB_COPILOT_TOKEN` — uses the GitHub Copilot API
>
> Without any credential the service uses enhanced template mode — all other functionality
> (GitHub PRs, webhook handling, feedback loop, stabilisation) works identically in both modes.

---

## Table of Contents

1. [Package Structure](#package-structure)
2. [End-to-End Data Flow](#end-to-end-data-flow)
3. [Class-by-Class Breakdown](#class-by-class-breakdown)
   - [Kafka Layer](#kafka-layer)
   - [Agent Layer](#agent-layer)
   - [Context Layer](#context-layer)
   - [Execution Layer](#execution-layer)
   - [Service Layer](#service-layer)
     - [E2ECoverageAnalyzer](#e2ecoverageanalyzer)
     - [RepoContextService](#repocontextservice)
     - [TestPrService](#testprservice)
   - [GitHub Layer](#github-layer)
   - [Config Layer](#config-layer)
4. [Strategy Decision Logic (Full Detail)](#strategy-decision-logic-full-detail)
5. [Two-Phase Coverage Assessment](#two-phase-coverage-assessment)
6. [RepoContextService Deep Dive](#repocontextservice-deep-dive)
7. [StabilizationLoop Deep Dive](#stabilizationloop-deep-dive)
8. [AI-Native Feedback Loop](#ai-native-feedback-loop)
9. [Kafka Topics](#kafka-topics)
10. [API Endpoints](#api-endpoints)
11. [Configuration](#configuration)

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
│   ├── BddGenerator.java                  ← AI (OpenAI-compatible) or template Gherkin builder
│   ├── AiClient.java                      ← Interface: complete(systemPrompt, userPrompt)
│   ├── OpenAiClient.java                  ← OpenAI-compatible implementation (GPT, Azure, Ollama)
│   └── PrFeedbackService.java             ← Handles PR rejections: fetch comments → re-generate → new PR
├── context/
│   └── ProductExpertContext.java          ← Record: per-product knowledge from productExpert/{name}/*.md
├── github/
│   ├── GitHubService.java                 ← GitHub REST API v3 client (branches, files, PRs, comments)
│   ├── PrTracker.java                     ← Thread-safe in-memory tracker for open BDD + TEST PRs
│   └── BddScenarioStore.java              ← Legacy: superseded by PrTracker (kept for compatibility)
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
│   ├── RepoContextService.java            ← Clones target repo, loads product expert, builds coverage index
│   └── TestPrService.java                 ← Creates real GitHub PRs (BDD Review + Final Test Code)
└── controller/
    ├── StrategyController.java            ← /status, /approve-bdd, /refresh-context
    └── GitHubWebhookController.java       ← /github-webhook — merged AND rejected PR events
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
   │  ② computeDecision() → SKIP | UPDATE_TESTS | CREATE_TESTS           │
   │  ③ buildStrategy()   → TestStrategy                                 │
   │  ④ applyFallbackRules()                                              │
   │                                                                     │
   │        ┌──────────┬─────────────────┬──────────────────┐           │
   │     SKIP          UPDATE_TESTS       CREATE_TESTS        │           │
   │     (log only)  handleUpdateTests   bddGenerator.generate│           │
   │                       │                     │            │           │
   │   bddGenerator uses:  │                     │            │           │
   │   • productExpert context (from repo)        │            │           │
   │   • .aiqa/context.md                        │            │           │
   │   • .github/agents/*.md                     │            │           │
   │   • AI mode (OpenAI) or template fallback    │            │           │
   │                       └──────────┬───────────┘            │           │
   │                                  ▼                        │           │
   │                         testPrService.createBddPr()       │           │
   │                         prTracker.trackBdd(branch, ...)   │           │
   └──────────────────────────────────────────────────────────────────────┘
                                 │  BDD PR on GitHub (qa/bdd/*)
                                 │
   ─────────── GitHub pull_request webhook fires ────────────────────────────
                                 │
               ┌─────────────────┴──────────────────────────┐
               │                                             │
          action=closed,merged=true               action=closed,merged=false
          (BDD PR MERGED)                         (BDD PR REJECTED)
               │                                             │
               ▼                                             ▼
   PrTracker.findByBranch()                    PrFeedbackService
   type=BDD → publish to TestScriptsQueue      .handleBddRejection() [virtual thread]
                                                 │ fetch PR review comments
                                                 │ AI classify: KNOWLEDGE_GAP or STYLE_ONLY
                                                 │ if KNOWLEDGE_GAP:
                                                 │   update productExpert/ → new PR
                                                 │ AI re-generate BDD w/ feedback
                                                 │ create revised BDD PR
                                                 │ prTracker.trackBdd(revBranch, ...)
                                                 └── (loop repeats from webhook)

   Kafka: TestScriptsQueue
           │
           ▼
   ┌─────────────────────────────────────────────────────────────────────┐
   │ TestScriptsConsumer → CodegenService                                │
   │  for each Scenario:                                                  │
   │    repoContextService.getContext(testType) → RepoContext             │
   │    route by testType:                                                │
   │      "API"    → apiTestRunner.generateCode()                        │
   │      "UI"     → uiTestRunner.generateCode()                         │
   │      "MOBILE" → mobileTestRunner.generateCode()                     │
   │    → TestScript                                                      │
   │    stabilizationLoop.execute(script)                                 │
   └──────────────────────────────┬──────────────────────────────────────┘
                                  │
                                  ▼
   ┌─────────────────────────────────────────────────────────────────────┐
   │ StabilizationLoop  (attempt 1–3)                                    │
   │  PASS → testPrService.createFinalTestPr()                           │
   │         prTracker.trackTest(branch, ...)                            │
   │  ABANDONED → still creates PR (⚠️ NEEDS REVIEW)                    │
   └──────────────────────────────┬──────────────────────────────────────┘
                                  │  Final Test PR on GitHub (qa/tests/*)
                                  │
   ─────────── GitHub pull_request webhook fires ────────────────────────────
                                  │
               ┌──────────────────┴────────────────────────┐
               │                                            │
          action=closed,merged=true            action=closed,merged=false
          (TEST PR MERGED)                     (TEST PR REJECTED)
               │                                            │
               ▼                                            ▼
   Pipeline complete                        PrFeedbackService
   "tests are in the repo"                  .handleTestRejection() [virtual thread]
   status: TEST_PR_MERGED                     │ fetch PR review comments
                                              │ AI classify: KNOWLEDGE_GAP or STYLE_ONLY
                                              │ if KNOWLEDGE_GAP:
                                              │   update productExpert/ → new PR
                                              │ AI re-generate test code w/ feedback
                                              │ create revised test code PR
                                              │ prTracker.trackTest(revBranch, ...)
                                              └── (loop repeats from webhook)
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

Generates Gherkin BDD scenarios in two modes:

**AI mode** (when `AiClient.isAvailable()` returns `true`):
1. Builds a rich system prompt from all available context sources (in priority order):
   - `productExpert/{product}/*.md` files — per-product domain knowledge
   - `.aiqa/context.md` — team-wide QA conventions
   - `.github/agents/*.md` — agent instruction files
   - Sample existing tests from the repo
2. Calls `aiClient.complete(systemPrompt, userPrompt)` 
3. Parses the Gherkin response into `BddScenario` with a lightweight parser

**Template mode** (fallback when no AI key):
- Generates up to 3 scenarios per requirement: happy path, error path, boundary outline (if `API_CHANGE`)
- Uses domain-specific `Given` steps when product expert context is available

**Tags added:**
- `@api` / `@ui` / `@mobile` (from test type)
- `@pr-{prId}`
- `@auto-generated`
- `@smoke` (if risk is HIGH or CRITICAL)

---

#### AiClient
**`agent/AiClient.java`**

Interface abstracting the language model API:
```java
String complete(String systemPrompt, String userPrompt);
boolean isAvailable();
```

---

#### AI Client (AiClient / OpenAiClient / CopilotClient)
**`common` module: `agent/AiClient.java`, `agent/OpenAiClient.java`, `agent/CopilotClient.java`**

The active implementation is selected by `aiqa.ai.provider` (resolved via `AiClientConfig`):

| `aiqa.ai.provider` | Implementation | Credential |
|---|---|---|
| `openai` (default) | `OpenAiClient` | `OPENAI_API_KEY` |
| `copilot` | `CopilotClient` | `GITHUB_COPILOT_TOKEN` |

**OpenAiClient** supports:
- **OpenAI** — default when `OPENAI_API_KEY` is set
- **Azure OpenAI** — set `OPENAI_BASE_URL` to your Azure endpoint
- **Local models (Ollama, etc.)** — set `OPENAI_BASE_URL=http://localhost:11434` (no key required)
- **GitHub Models** — set `OPENAI_BASE_URL=https://models.inference.ai.azure.com` + `OPENAI_API_KEY` to a GitHub token

**CopilotClient** uses the GitHub Copilot API endpoint (`https://api.githubcopilot.com`). It authenticates with any GitHub token that has Copilot access — a PAT, a GitHub App installation token, or the `GITHUB_TOKEN` available in GitHub Actions when the repository has Copilot enabled.

**`isAvailable()`** returns `true` when the active provider's credential is non-blank (or when using a custom `OPENAI_BASE_URL` for local models, which don't need keys).

Configuration properties:
```yaml
aiqa:
  ai:
    provider: ${AI_PROVIDER:copilot}               # openai | copilot
    openai:
      api-key:  ${OPENAI_API_KEY:}
      base-url: ${OPENAI_BASE_URL:https://api.openai.com}
      model:    ${OPENAI_MODEL:gpt-4o}
    copilot:
      token:    ${GITHUB_COPILOT_TOKEN:}
      base-url: ${COPILOT_BASE_URL:https://api.githubcopilot.com}
      model:    ${COPILOT_MODEL:gpt-4o}
```

---

#### PrFeedbackService
**`agent/PrFeedbackService.java`**

Handles PR rejection events for both BDD and test code PRs. Runs asynchronously on a virtual thread (launched by `GitHubWebhookController`) so it never blocks the webhook HTTP response.

**`handleBddRejection(PrRecord record, int prNumber)`**
1. `gitHubService.getPrAllComments(prNumber)` — fetches all review comments (inline + PR-level)
2. AI classify feedback: `KNOWLEDGE_GAP: <desc>` or `STYLE_ONLY: <reason>`
3. If `KNOWLEDGE_GAP` → `handleProductExpertUpdate()` (see below)
4. `regenerateBdd(original, feedback, context)` — AI re-generates scenarios
5. `createRevisedBddPr()` → `prTracker.trackBdd(revBranch, ...)` → loop continues

**`handleTestRejection(PrRecord record, int prNumber)`**
1. Fetch all review comments
2. AI classify feedback
3. If `KNOWLEDGE_GAP` → `handleProductExpertUpdate()`
4. `regenerateTestCode(original, feedback, context)` — AI re-generates test code with reviewer feedback as additional context; system prompt includes product expert + existing test patterns
5. `createRevisedTestPr()` → `prTracker.trackTest(revBranch, ...)` → loop continues

**`handleProductExpertUpdate(feedback, prId, context)` (shared)**
1. AI prompt: `KNOWLEDGE_GAP: <desc>` classification (strict format)
2. Read existing `productExpert/{product}/PRODUCT.md` (may not exist yet)
3. AI appends new knowledge section to the file
4. Create branch + commit + PR: `[AI-QA] Product Expert Update: {product}`
5. Human reviews and merges the PR — next generation cycle starts with richer context

---

### Context Layer

#### ProductExpertContext
**`context/ProductExpertContext.java`**

Immutable record holding per-product knowledge loaded from the test repository:

```java
public record ProductExpertContext(String productName, Map<String, String> files) {
    public String asSystemPromptSection()  // formats all files as an AI system prompt section
    public String productMd()              // content of PRODUCT.md
    public String patternsMd()             // content of PATTERNS.md
}
```

**Loaded from:** `productExpert/{productName}/*.md` in the test repo (any `.md` file in the
product's subdirectory — `PRODUCT.md` for domain flows/business rules, `PATTERNS.md` for
preferred test patterns, and any other `.md` files for supplementary knowledge).

`RepoContextService` scans all subdirectories under `productExpert/` at startup and on each
`refresh-context` call, storing results in `RepoContext.productExpertSections`.

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
4. `prTracker.trackBdd(branch, prNumber, scenario)` — registers PR for webhook lookup
5. Return the PR HTML URL (e.g. `https://github.com/org/repo/pull/42`)

**`createFinalTestPr(TestScript, TestResult)`** — raises a final test code PR:
1. Create branch `qa/tests/{prId}-{scriptId(6chars)}` from `main`
2. Commit `src/test/java/{package}/{fileName}` with the generated Java source
3. Open PR titled `✅ [PASSING]` / `✅ [STABILIZED]` / `⚠️ [NEEDS REVIEW]` based on result
4. `prTracker.trackTest(branch, prNumber, script)` — registers PR for webhook lookup
5. Body includes execution summary table, failure details (if failed), full generated Java source, review checklist

> **Requires GitHub configuration.** Set `TARGET_REPO_URL` and ensure a token is available
> via `TARGET_REPO_TOKEN` or the system git credential helper. See `GitHubService` below.

---

#### GitHubService
**`github/GitHubService.java`**

See [GitHub Layer](#github-layer) section below for full details.

---

#### BddScenarioStore (legacy)
**`github/BddScenarioStore.java`**

> **Superseded by `PrTracker`.** See [GitHub Layer](#github-layer) section below.

---

### GitHub Layer

#### GitHubService
**`github/GitHubService.java`**

Thin REST client wrapping the GitHub API v3.  Constructed as a Spring `@Service` singleton
and injected into `TestPrService` and `PrFeedbackService`.

**Token resolution — priority order:**

| Priority | Source | Notes |
|----------|--------|-------|
| 1 | `TARGET_REPO_TOKEN` env var | Explicit PAT — recommended for CI/production. For GitHub org repos, the PAT must have SSO authorized for the org. |
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
| `updateFile(branch, path, content, sha, msg)` | PUT `/contents/{path}` with existing file SHA — updates an existing file |
| `getFileContent(path, branch)` | GET `/contents/{path}?ref={branch}` — returns decoded string content or `null` |
| `getFileSha(path, branch)` | GET `/contents/{path}?ref={branch}` — returns the blob SHA needed for `updateFile` |
| `createPullRequest(title, body, head, base)` | POST `/pulls` — returns `GitHubPrResult(prNumber, url, branch)` |
| `getPrAllComments(prNumber)` | Fetches review comments + PR-level comments, concatenated as plain text |

All methods return `false` / `null` (not throw) when `isConfigured()` is false, so callers
(`TestPrService`, `PrFeedbackService`) can check and throw their own typed exceptions.

> **Branch SHA resolution:** Uses `GET /repos/{owner}/{repo}/branches/{branch}` (not
> `git/ref/heads/{branch}`) to avoid Spring `RestClient` URI template encoding converting
> `/` in `heads/main` to `%2F`, which GitHub does not decode and returns 404.

---

#### PrTracker
**`github/PrTracker.java`**

Thread-safe in-memory tracker for **all open QA pull requests** — both BDD review PRs and
final test code PRs. Replaces the legacy `BddScenarioStore` which only tracked BDD PRs.

```java
public enum PrType { BDD, TEST }

public record PrRecord(
    String branchName, int prNumber, PrType type,
    BddScenario bddScenario,  // non-null for BDD PRs
    TestScript testScript      // non-null for TEST PRs
) {}

public void trackBdd(String branch, int prNumber, BddScenario scenario)
public void trackTest(String branch, int prNumber, TestScript script)
public Optional<PrRecord> findByBranch(String branch)
public void remove(String branch)
public int size()
```

**Lifecycle of a BDD PR entry:**
```
TestPrService.createBddPr()
  → prTracker.trackBdd("qa/bdd/PR-xxx-abc", 42, scenario)

(human reviews BDD PR on GitHub)

GitHubWebhookController receives action=closed
  → prTracker.findByBranch("qa/bdd/PR-xxx-abc") → PrRecord(type=BDD)
  → prTracker.remove(...)
  → MERGED: publish to TestScriptsQueue
  → REJECTED: PrFeedbackService.handleBddRejection() [virtual thread]
               → createRevisedBddPr() → prTracker.trackBdd("qa/bdd/PR-xxx-rev-abc", 43, ...)
```

**Lifecycle of a TEST PR entry:**
```
StabilizationLoop passes → TestPrService.createFinalTestPr()
  → prTracker.trackTest("qa/tests/PR-xxx-def", 44, script)

(human reviews test code PR on GitHub)

GitHubWebhookController receives action=closed
  → prTracker.findByBranch("qa/tests/PR-xxx-def") → PrRecord(type=TEST)
  → prTracker.remove(...)
  → MERGED: status=TEST_PR_MERGED (pipeline complete)
  → REJECTED: PrFeedbackService.handleTestRejection() [virtual thread]
               → createRevisedTestPr() → prTracker.trackTest("qa/tests/PR-xxx-rev-def", 45, ...)
```

---

#### BddScenarioStore (legacy)
**`github/BddScenarioStore.java`**

> **Superseded by `PrTracker`.** This class remains in the codebase for backward compatibility
> but `GitHubWebhookController` no longer uses it. `PrTracker` tracks both BDD and TEST PRs
> in a single unified store and is the canonical tracking mechanism.

Original role: in-memory `ConcurrentHashMap<branchName, BddScenario>` bridging the time
between opening a BDD Review PR and receiving the GitHub merge webhook.

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
      │  No:  test repo access                │  Has: coverage index (inverted)
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
    refreshCache()    ← scan three module directories + load product expert context
                         builds coverage index per module, then merges
```

`refreshCache()` now also:
- `loadProductExpert(repoRoot)` — scans `productExpert/` subdirectories, reads all `.md` files per product, stores as `Map<String, ProductExpertContext>` in each `RepoContext`
- `loadAiqaContext(repoRoot)` — reads `.aiqa/context.md` if present, stored as `repoAiqaContext` string in each `RepoContext`

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

## AI-Native Feedback Loop

### Overview

`GitHubWebhookController` handles the `pull_request` webhook for **all four outcomes**:

| Event | PR Type | Action |
|-------|---------|--------|
| BDD PR merged | `PrType.BDD` | Publish `BddScenario` to `TestScriptsQueue` → codegen |
| BDD PR rejected (closed, not merged) | `PrType.BDD` | `PrFeedbackService.handleBddRejection()` [async] |
| TEST PR merged | `PrType.TEST` | Log `TEST_PR_MERGED` — pipeline complete |
| TEST PR rejected (closed, not merged) | `PrType.TEST` | `PrFeedbackService.handleTestRejection()` [async] |

`PrTracker.findByBranch(headBranch)` determines which PRs are QA-generated and what type they are. Any branch not in `PrTracker` gets response `NOT_A_QA_PR` (safe to ignore).

### BDD PR Rejection Flow

```
GitHub webhook: action=closed, merged=false, branch=qa/bdd/PR-xxx-abc
                          │
           PrTracker.findByBranch() → PrRecord(type=BDD, scenario=...)
           PrTracker.remove(branch)   ← prevent double-processing
                          │
           Thread.ofVirtual().start(() ->
             PrFeedbackService.handleBddRejection(record, prNumber)
           )
                          │
           ┌──────────────▼─────────────────────────────────────┐
           │  1. gitHubService.getPrAllComments(prNumber)        │
           │     → inline review comments + PR-level comments    │
           │                                                     │
           │  2. aiClient.complete(classify prompt)              │
           │     Response format: "KNOWLEDGE_GAP: <desc>"        │
           │                  or  "STYLE_ONLY: <reason>"         │
           │                                                     │
           │  3. IF KNOWLEDGE_GAP:                               │
           │       read productExpert/{product}/PRODUCT.md       │
           │       AI appends new knowledge section              │
           │       createBranch("qa/product-expert/...")         │
           │       createFile / updateFile                       │
           │       createPullRequest("[AI-QA] Product Expert Update")│
           │                                                     │
           │  4. regenerateBdd(original, feedback, context)      │
           │       AI mode: system prompt = product expert +     │
           │                aiqa context + agent instructions    │
           │       User prompt: "scenarios were REJECTED, here   │
           │                     is the feedback, improve them"  │
           │       Template fallback: embed feedback as comments  │
           │                                                     │
           │  5. createRevisedBddPr() → new branch qa/bdd/...-rev│
           │     prTracker.trackBdd(newBranch, newPrNumber, ...)  │
           └─────────────────────────────────────────────────────┘
                          │
           Webhook fires again when revised BDD PR is reviewed
           → same flow repeats until human approves
```

### Test Code PR Rejection Flow

```
GitHub webhook: action=closed, merged=false, branch=qa/tests/PR-xxx-def
                          │
           PrTracker.findByBranch() → PrRecord(type=TEST, script=...)
           PrTracker.remove(branch)
                          │
           Thread.ofVirtual().start(() ->
             PrFeedbackService.handleTestRejection(record, prNumber)
           )
                          │
           ┌──────────────▼─────────────────────────────────────┐
           │  1. gitHubService.getPrAllComments(prNumber)        │
           │                                                     │
           │  2. AI classify feedback (same as BDD flow)         │
           │                                                     │
           │  3. IF KNOWLEDGE_GAP → product expert update PR     │
           │                                                     │
           │  4. regenerateTestCode(original, feedback, context) │
           │       AI mode: system prompt includes product expert│
           │                + existing test patterns + aiqa ctx  │
           │       User prompt: "test code was REJECTED, here    │
           │                     is the feedback:                │
           │                     Original file: {fileName}       │
           │                     Original code: {scriptContent}  │
           │                     Feedback: {reviewComments}"     │
           │       Template fallback: embed feedback as comments  │
           │                                                     │
           │  5. createRevisedTestPr() → new branch qa/tests/...-rev│
           │     prTracker.trackTest(newBranch, newPrNumber, ...)  │
           └─────────────────────────────────────────────────────┘
                          │
           Webhook fires again when revised test PR is reviewed
           → same flow repeats until human approves
```

### Product Expert Update PR

When a knowledge gap is detected in either BDD or test rejection:

```
PR body example:
  ## 🧠 AI-Detected Product Knowledge Gap
  Source PR: PR-12345678
  Product: payments
  Gap identified: OAuth token refresh flow not covered in existing knowledge

  ### Context
  A QA scenario PR was rejected with feedback that revealed a gap in the product
  expert knowledge used to generate test scenarios. This PR updates the product
  expert file so future test generation is more accurate.

  ### Review Checklist
  - [ ] The added knowledge is accurate
  - [ ] Existing content is unchanged
  - [ ] The description is clear for future AI context use
```

The update PR is independent of the revised content PR — the two are created in parallel.
The product expert update feeds into *future* generation cycles, not the immediate retry.

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
Receives GitHub `pull_request` webhook events. Handles **all four outcomes**:
BDD merged, BDD rejected, TEST merged, TEST rejected.

**GitHub Setup:**
```
Repository Settings → Webhooks → Add webhook
  Payload URL : https://<your-host>/api/strategy/github-webhook
  Content type: application/json
  Secret      : value of GITHUB_WEBHOOK_SECRET env var
  Events      : Pull requests
```

**Flow:**
1. GitHub fires `pull_request` with `action=closed`
2. Controller verifies `X-Hub-Signature-256` HMAC-SHA256 header
3. `PrTracker.findByBranch(headBranch)` determines PR type (`BDD` or `TEST`)
4. Routes to `handleMerged()` or `handleRejected()` based on `merged` flag:
   - **Merged BDD** → publish to `TestScriptsQueue` → codegen
   - **Rejected BDD** → `PrFeedbackService.handleBddRejection()` [virtual thread]
   - **Merged TEST** → log and return `TEST_PR_MERGED`
   - **Rejected TEST** → `PrFeedbackService.handleTestRejection()` [virtual thread]

**Response examples:**
```json
{ "status": "CODEGEN_TRIGGERED",  "sourcePrId": "PR-XXXX", "scenarioId": "...", "mergedBranch": "qa/bdd/PR-XXXX-abc" }
{ "status": "TEST_PR_MERGED",     "sourcePrId": "PR-XXXX", "branch": "qa/tests/PR-XXXX-def" }
{ "status": "FEEDBACK_TRIGGERED", "prType": "BDD",  "prNumber": 42, "message": "Re-generation started — a new PR will be created shortly" }
{ "status": "FEEDBACK_TRIGGERED", "prType": "TEST", "prNumber": 44, "message": "Re-generation started — a new PR will be created shortly" }
{ "status": "NOT_A_QA_PR",        "branch": "feature/some-other-branch" }
{ "status": "IGNORED",            "event": "issues" }
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

# ── AI generation (BDD + test code + feedback classification) ─────────────────
# Set aiqa.ai.provider to select the AI backend.
# Without a credential the service uses enhanced template mode for all generation.
aiqa:
  ai:
    provider: ${AI_PROVIDER:copilot}               # openai | copilot
    openai:
      api-key:  ${OPENAI_API_KEY:}                # leave blank for template mode
      base-url: ${OPENAI_BASE_URL:https://api.openai.com}  # override for Azure/Ollama/GitHub Models
      model:    ${OPENAI_MODEL:gpt-4o}
    copilot:
      token:    ${GITHUB_COPILOT_TOKEN:}          # GitHub token with Copilot access
      base-url: ${COPILOT_BASE_URL:https://api.githubcopilot.com}
      model:    ${COPILOT_MODEL:gpt-4o}

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

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `TARGET_REPO_URL` | For PRs | HTTPS URL of the test repository |
| `TARGET_REPO_TOKEN` | For PRs | GitHub PAT with `repo` scope. For org repos: must have SSO authorized. |
| `TARGET_REPO_USERNAME` | For PRs | GitHub username paired with the PAT |
| `GITHUB_WEBHOOK_SECRET` | Recommended | HMAC-SHA256 secret — prevents unauthenticated triggers |
| `AI_PROVIDER` | No | AI backend: `openai` (default) or `copilot` |
| `OPENAI_API_KEY` | When `AI_PROVIDER=openai` | OpenAI or compatible API key. Without it, template mode is used. |
| `OPENAI_BASE_URL` | No | Override for Azure, Ollama, or GitHub Models. Default: `https://api.openai.com` |
| `OPENAI_MODEL` | No | Model name. Default: `gpt-4o` |
| `GITHUB_COPILOT_TOKEN` | When `AI_PROVIDER=copilot` | GitHub token with Copilot access |
| `COPILOT_BASE_URL` | No | Copilot endpoint override. Default: `https://api.githubcopilot.com` |
| `COPILOT_MODEL` | No | Copilot model. Default: `gpt-4o` |

### Product Expert Files in Test Repo

The service reads these files from the test repo at startup and on `refresh-context`:

```
{test-repo}/
  productExpert/
    {product-name}/
      PRODUCT.md       ← domain flows, business rules, known edge cases
      PATTERNS.md      ← preferred assertion patterns, test structure
      *.md             ← any additional knowledge files
  .aiqa/
    context.md         ← team-wide QA conventions
  .github/
    agents/
      api-*.md         ← API test agent instructions
      ui-*.md          ← UI test agent instructions
      *.md             ← shared conventions (applied to all test types)
```

None of these files are required — the service works with built-in templates when
they are absent. Add them progressively as the AI's initial output needs improvement.

