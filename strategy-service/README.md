# strategy-service

**Port:** `8082` · **Phases:** 2–7 — Strategy, BDD Generation, Code Generation, Stabilisation, Feedback  
**Role:** Consumes `ImpactEnvelope`, decides test strategy, generates BDD scenarios, produces
executable test code, runs a bounded stabilisation loop, raises GitHub review PRs, and handles
rejection feedback for both BDD and test code PRs.

> Set `AI_PROVIDER` to select the AI backend:
> - `copilot-cli` (default) — uses `gh api`; run `gh auth login` once, no token needed
> - `copilot` + `GITHUB_COPILOT_TOKEN` — Copilot REST API
> - `openai` + `OPENAI_API_KEY` — any OpenAI-compatible endpoint (Azure, Ollama, GitHub Models)
>
> Without a credential, enhanced template mode is used for all generation.

---

## Table of Contents

1. [Package Structure](#package-structure)
2. [End-to-End Data Flow](#end-to-end-data-flow)
3. [Key Classes](#key-classes)
4. [Strategy Decision Logic](#strategy-decision-logic)
5. [Coverage Assessment (Two-Phase)](#coverage-assessment-two-phase)
6. [Stabilisation Loop](#stabilisation-loop)
7. [AI Feedback Loop](#ai-feedback-loop)
8. [RepoContextService](#repocontextservice)
9. [Kafka Topics](#kafka-topics)
10. [API Endpoints](#api-endpoints)
11. [Configuration](#configuration)

---

## Package Structure

```
nz/co/eroad/qaisystem/
├── strategy/     StrategyServiceApplication.java
├── config/       KafkaConfig.java · TargetRepoProperties.java
├── kafka/        ImpactResultsConsumer.java · TestScriptsConsumer.java · TestScriptsProducer.java
├── agent/        StrategyAgent.java · BddGenerator.java · PrFeedbackService.java
├── context/      ProductExpertContext.java
├── github/       GitHubService.java · PrTracker (→ common) · BddScenarioStore.java (legacy)
├── execution/    CodegenService.java · ApiTestRunner.java · UITestRunner.java
│                 MobileTestRunner.java · TestExecutionEngine.java · StabilizationLoop.java
│                 RepoContext.java
├── service/      E2ECoverageAnalyzer.java · RepoContextService.java · TestPrService.java
└── controller/   StrategyController.java · GitHubWebhookController.java
```

---

## End-to-End Data Flow

```
Kafka: ImpactResultsQueue (ImpactEnvelope)
    │
    ▼ ImpactResultsConsumer → StrategyAgent.decide()
      ① E2ECoverageAnalyzer.analyze()   → real GOOD/PARTIAL/NONE coverage level
      ② computeDecision()               → SKIP | UPDATE_TESTS | CREATE_TESTS
      ③ BddGenerator.generate()         → BddScenario (AI or template)
      ④ TestPrService.createBddPr()     → GitHub PR (qa/bdd/*)
         PrTracker.trackBdd()

─── Human reviews BDD PR on GitHub ──────────────────────────────────────────────

BDD MERGED (webhook) → PrTracker.findByBranch() → publish to TestScriptsQueue
BDD REJECTED         → PrFeedbackService.handleBddRejection() [virtual thread]
                        → revised BDD PR → PrTracker.trackBdd()

Kafka: TestScriptsQueue (BddScenario)  [or POST /api/strategy/approve-bdd]
    │
    ▼ TestScriptsConsumer → CodegenService → ApiTestRunner/UITestRunner/MobileTestRunner
      → StabilizationLoop (max 3 attempts)
      → TestPrService.createFinalTestPr() → GitHub PR (qa/tests/*)
         PrTracker.trackTest()

─── Human reviews test code PR ───────────────────────────────────────────────────

TEST MERGED   → pipeline complete (TEST_PR_MERGED)
TEST REJECTED → PrFeedbackService.handleTestRejection() [virtual thread]
                → revised test PR → PrTracker.trackTest()
```

---

## Key Classes

### Agent Layer

| Class | Responsibility |
|-------|----------------|
| `StrategyAgent` | Decides SKIP/UPDATE_TESTS/CREATE_TESTS using E2E coverage + risk rules (see [decision logic](#strategy-decision-logic)). Builds `TestStrategy` with test types, scenario hints, confidence score, priority (P0–P3). |
| `BddGenerator` | **AI mode:** builds rich system prompt from productExpert context, `.aiqa/context.md`, `.github/agents/*.md`, sample tests → calls `AiClient.complete()`. **Template mode:** generates up to 3 scenarios (happy path, error, boundary). Adds `@api/@ui/@mobile`, `@pr-{prId}`, `@auto-generated`, `@smoke` (HIGH/CRITICAL risk). |
| `PrFeedbackService` | Handles BDD + TEST rejections. Fetches GitHub review comments → AI classifies (`KNOWLEDGE_GAP`/`STYLE_ONLY`) → if gap: updates `productExpert/` + creates knowledge-update PR → re-generates with feedback → creates revised PR. Runs on virtual thread. |

### Execution Layer

| Class | Responsibility |
|-------|----------------|
| `CodegenService` | Routes `BddScenario` to correct runner by `testType` (API/UI/MOBILE). Loads `RepoContext` for each type before routing. |
| `ApiTestRunner` / `UITestRunner` / `MobileTestRunner` | Pure code generation. Uses `RepoContext` for actual package name, common imports, base class, naming convention. Generates RestAssured/Selenium/Appium + JUnit 5. |
| `TestExecutionEngine` | Compiles generated Java via `javax.tools.JavaCompiler`, runs via JUnit Platform Launcher in a temp dir. Returns `TestResult` with pass/fail counts + diagnostics. Requires JDK at runtime (use `eclipse-temurin:25-jdk`). |
| `StabilizationLoop` | `for attempt 1..3: compile → run → if fail: applyFix() → retry`. Fix strategy: attempt 1 = add timeout/retry headers; attempt 2 = wrap in retry block; attempt 3 = minimal smoke test. Creates final PR regardless of outcome (⚠️ NEEDS REVIEW on abandon). |

### Service Layer

| Class | Responsibility |
|-------|----------------|
| `E2ECoverageAnalyzer` | Cross-references `ImpactEnvelope.impactedComponents` against the coverage index (from `RepoContextService`). Produces GOOD/PARTIAL/NONE `CoverageReport`. Returns UNKNOWN when no repo is configured. |
| `RepoContextService` | Clones/pulls the test repo, scans test files, builds coverage index. See [RepoContextService](#repocontextservice). |
| `TestPrService` | Creates real GitHub PRs. `createBddPr()`: branch `qa/bdd/{prId}-{6chars}`, commits `.feature` file, title `[AI-QA] {prTitle}`. `createFinalTestPr()`: branch `qa/tests/…`, title `✅ [AI-QA] {prTitle}` / `⚠️ [NEEDS REVIEW] {prTitle}`. |

### GitHub Layer

| Class | Notes |
|-------|-------|
| `GitHubService` (common) | Token resolution: `TARGET_REPO_TOKEN` → `git credential fill` → startup failure. Branch SHA: uses `GET /repos/…/branches/{b}` (not `git/ref/`) to avoid URI encoding issues. |
| `PrTracker` (common) | `InMemoryPrTracker` (default) or `RedisPrTracker` (when `spring.data.redis.host` set). `findAll()` returns all tracked records — used by `GET /api/strategy/pending-bdd`. |
| `BddScenarioStore` | Legacy — superseded by `PrTracker`. Kept for backward compatibility only. |

---

## Strategy Decision Logic

`StrategyAgent.computeDecision(envelope, coverage)` — rules in priority order:

| Priority | Condition | Decision |
|----------|-----------|---------|
| 1 | `coverage.level==NONE` AND untested components exist | **CREATE_TESTS** (hard override) |
| 2 | All change types are CONFIG/DEPENDENCY AND riskLevel==LOW | **SKIP** |
| 3 | Only test files changed | **SKIP** |
| 4 | riskLevel==HIGH or CRITICAL | **CREATE_TESTS** |
| 5 | `NEW_FEATURE` in changeTypes | **CREATE_TESTS** |
| 6 | `coverage.level==PARTIAL` | **UPDATE_TESTS** |
| 7 | existingTestFiles not empty | **UPDATE_TESTS** |
| 8 | (default) | **CREATE_TESTS** |

**Post-decision fallback rules:**
- `confidenceScore < 0.4` → `fullRegressionRequired = true`
- `riskLevel == HIGH|CRITICAL` → `expandedScope = true` + add transitive deps to test areas

---

## Coverage Assessment (Two-Phase)

```
Phase 1 – impact-service / TestCoverageService
  No repo access. Identifies component types needing integration tests.
  Sets level=UNKNOWN, source=UNKNOWN.

Phase 2 – strategy-service / E2ECoverageAnalyzer
  Has cloned test repo + coverage index (componentName → test files).
  Replaces UNKNOWN with GOOD/PARTIAL/NONE based on real repo scan.
  StrategyAgent uses this real level for its decision.
```

**Coverage index** (built by `RepoContextService`): inverted map `componentName → [testFiles]`.
Integration/E2E files detected by: `.feature` extension, IT/IntegrationTest/E2ETest in name,
or content contains `@SpringBootTest`, `RestAssured`, `MockMvc`, `WebTestClient`.

---

## Stabilisation Loop

```
for attempt = 1 to maxRetries (default 3):
  result = testExecutionEngine.execute(script, attempt)
  if result.passed → createFinalTestPr() ✅, return
  if attempt < max → applyFix(script, result, attempt)

createFinalTestPr() ⚠️ NEEDS REVIEW, return
```

| Attempt | Fix | Trigger |
|---------|-----|---------|
| 1 | Add connection timeout + `@Timeout(30s)` | "Connection refused"/"503"/"timeout" |
| 2 | Wrap assertions in retry block + null guards | "AssertionError" |
| 3 | Replace with minimal smoke test | always (last resort) |

---

## AI Feedback Loop

When a QA PR is rejected on GitHub:

```
GitHubWebhookController (action=closed, merged=false)
    │
    ▼ [virtual thread]
PrFeedbackService.handleBddRejection() / handleTestRejection()
    1. gitHubService.getPrAllComments(prNumber)
    2. AI classify: KNOWLEDGE_GAP or STYLE_ONLY
    3. If KNOWLEDGE_GAP:
       → append to productExpert/{product}/PRODUCT.md
       → create knowledge-update PR for human review
    4. Re-generate BDD/test with reviewer feedback as context
    5. Create revised PR → prTracker.trackBdd/trackTest() → loop repeats
```

| PR type | Branch prefix | Title |
|---------|--------------|-------|
| BDD initial | `qa/bdd/*` | `[AI-QA] {prTitle}` |
| BDD revised | `qa/bdd/*-rev-*` | `[AI-QA] Revised: {prTitle}` |
| Test initial | `qa/tests/*` | `✅ [AI-QA] {prTitle}` |
| Test revised | `qa/tests/*-rev-*` | `[AI-QA] Revised Tests: {prTitle}` |

`{prTitle}` falls back to `"… for PR: {prId}"` when absent.

---

## RepoContextService

Owns the target test repo lifecycle. Connects to external systems.

**Startup:** `@PostConstruct` → `cloneOrPull()` → `refreshCache()` (scans API/UI/MOBILE modules,
loads productExpert context, builds coverage index). Refreshable: `POST /api/strategy/refresh-context`.

**Clone/Pull:** `git clone --depth 1` or `git pull`. Auth modes:

| `auth.type` | Behaviour |
|-------------|-----------|
| `none` (default) | System credential helper — osxkeychain on macOS picks up IntelliJ credentials automatically |
| `token` | PAT embedded in HTTPS URL: `https://{user}:{token}@github.com/...` |
| `ssh` | SSH agent (`~/.ssh/id_rsa` or `id_ed25519`) |

**Fallback:** if remote clone/pull fails, tries `fallback-local-path`. If both fail → uses built-in templates (`contextAvailable=false`).

**Context scanning** per module:
- `basePackage` — first `package` statement found
- `commonImports` — imports in ≥50% of test files
- `baseTestClass` — first `class X extends Y` found
- `testNamingConvention` — `Test*` prefix or `*Test` suffix (by majority)
- `sampleTests` — up to 3 files (skipping files >6000 chars)
- `productExpertSections` — all `.md` files under `productExpert/{product}/`
- `agentInstructions` — `.github/agents/*.md` files (by type: `api-*`, `ui-*`, etc.)

---

## Kafka Topics

| Direction | Topic | Payload |
|-----------|-------|---------|
| Consumes | `ImpactResultsQueue` | `ImpactEnvelope` JSON |
| Consumes | `TestScriptsQueue` | `BddScenario` JSON |
| Produces | `TestScriptsQueue` | `BddScenario` JSON (via `POST /approve-bdd` or webhook merge) |

Consumer group: `strategy-service-group`

---

## API Endpoints

### `GET /api/strategy/status`
```json
{ "service": "strategy-service", "status": "OPERATIONAL", "timestamp": "..." }
```

### `GET /api/strategy/pending-bdd`
Returns all tracked BDD scenarios waiting for approval (open BDD review PRs).
Used by `scripts/approve-bdd.sh`.

```bash
curl http://localhost:8082/api/strategy/pending-bdd
```
```json
[
  { "branch": "qa/bdd/PR-XXXX-abc", "prNumber": 6, "bddScenario": { "prId": "PR-XXXX", "prTitle": "VSF-3670: ...", "scenarios": [...] } }
]
```

Works with both `InMemoryPrTracker` and `RedisPrTracker` — Redis state persists across restarts.

### `POST /api/strategy/approve-bdd`
Manual codegen trigger — publishes `BddScenario` to `TestScriptsQueue`.
Use this locally instead of setting up an ngrok webhook.

```bash
curl -X POST http://localhost:8082/api/strategy/approve-bdd \
  -H "Content-Type: application/json" \
  -d '{ "scenarioId": "...", "prId": "PR-XXXX", "scenarios": [...], ... }'
```

### `POST /api/strategy/github-webhook`
Receives GitHub `pull_request` events (`action=closed`). Verifies `X-Hub-Signature-256`.
Routes by `PrTracker.findByBranch(headBranch)`:

| Outcome | Response status |
|---------|----------------|
| BDD merged | `CODEGEN_TRIGGERED` |
| BDD rejected | `FEEDBACK_TRIGGERED` |
| TEST merged | `TEST_PR_MERGED` |
| TEST rejected | `FEEDBACK_TRIGGERED` |
| Unknown branch | `NOT_A_QA_PR` |

**GitHub setup:**
```
Repository Settings → Webhooks → Add webhook
  Payload URL : https://<your-host>/api/strategy/github-webhook
  Content type: application/json
  Secret      : value of GITHUB_WEBHOOK_SECRET
  Events      : Pull requests
```

### `POST /api/strategy/refresh-context`
Re-runs `git pull` + re-scans the test repo without restart.
```bash
curl -X POST http://localhost:8082/api/strategy/refresh-context
```

---

## Configuration

```yaml
server.port: 8082

spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: strategy-service-group
      auto-offset-reset: earliest
      enable-auto-commit: false

kafka.topics:
  impact-results: ImpactResultsQueue
  test-scripts:   TestScriptsQueue

aiqa:
  ai:
    provider: ${AI_PROVIDER:copilot-cli}       # copilot-cli | copilot | openai
    copilot-cli:
      gh-cli-path: ${GH_CLI_PATH:gh}
      model:       ${COPILOT_CLI_MODEL:gpt-4o}
    copilot:
      token:    ${GITHUB_COPILOT_TOKEN:}
      base-url: ${COPILOT_BASE_URL:https://api.githubcopilot.com}
      model:    ${COPILOT_MODEL:gpt-4o}
    openai:
      api-key:  ${OPENAI_API_KEY:}
      base-url: ${OPENAI_BASE_URL:https://api.openai.com}
      model:    ${OPENAI_MODEL:gpt-4o}
  stabilization:
    max-retries:    3
    retry-delay-ms: 2000
  target-repo:
    url:    ${TARGET_REPO_URL:}
    branch: main
    local-path: /tmp/qa-context-repo
    fallback-local-path: ""       # e.g. /Users/yourname/projects/your-test-repo
    fallback-pull: false
    auth:
      type:     none              # none | token | ssh
      token:    ${TARGET_REPO_TOKEN:}
      username: ${TARGET_REPO_USERNAME:}
    modules:
      api:    tests/api
      ui:     tests/ui
      mobile: tests/mobile
```

### Environment Variables

| Variable | Required | Notes |
|----------|----------|-------|
| `TARGET_REPO_URL` | For PR creation | HTTPS URL of the test repo |
| `TARGET_REPO_TOKEN` | For PR creation | GitHub PAT with `repo` scope (SSO-authorise for org repos) |
| `TARGET_REPO_USERNAME` | For PR creation | GitHub username |
| `GITHUB_WEBHOOK_SECRET` | Recommended | HMAC-SHA256 secret — must match GitHub webhook settings |
| `AI_PROVIDER` | No | `copilot-cli` (default) · `copilot` · `openai` |
| `OPENAI_API_KEY` | When `openai` | API key (blank = template mode) |
| `GITHUB_COPILOT_TOKEN` | When `copilot` | GitHub token with Copilot access |

### Product Expert Files in Test Repo

```
{test-repo}/
  productExpert/{product}/
    PRODUCT.md    ← domain flows, business rules, edge cases
    PATTERNS.md   ← assertion patterns, test structure
    *.md          ← any additional knowledge
  .aiqa/context.md          ← team-wide QA conventions
  .github/agents/
    api-*.md  ui-*.md  *.md ← agent instructions per type
```

None required — the service works with built-in templates when absent.

---

## Tests

| Class | Tests | Covers |
|-------|-------|--------|
| `RepoContextTest` | 9 | helper methods, agent instruction loading |
| `ApiTestRunnerTest` (strategy) | 7 | code generation from BDD scenarios |
| `StrategyAgentTest` | 10 | SKIP/CREATE logic, fallback rules |
