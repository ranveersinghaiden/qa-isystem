# QA-ISystem: Complete Architecture & Design Reference

> **Audience:** This document is written for someone who is new to coding and solution design.
> Every concept is explained from first principles before diving into how it is applied here.

---

## 1. What Problem Does This System Solve?

When a developer writes new code and opens a Pull Request (PR), someone (or something) needs to answer:

> *"Does the test suite need updating? And if so, what tests should be written?"*

Traditionally, a human QA engineer looks at the changed code, figures out which components were affected, checks whether they are covered by tests, writes BDD scenarios, generates test code, runs it, fixes it, and opens a PR. This process can take hours to days.

**QA-ISystem automates this entire workflow:**

1. A PR is submitted → the system receives it, extracts external context (Jira tickets, Confluence docs, labels, products) and AI-compresses it
2. The system analyses the code change (which files changed, what kind of change, how risky it is)
3. It checks whether the changed components already have integration or end-to-end tests
4. It makes a decision: skip / update existing tests / create new tests
5. It generates BDD (Gherkin) scenarios for human review, enriched with Jira/Confluence context
6. After human approval, a dedicated code-generation service produces executable test code
7. It runs the test, self-heals minor failures, and opens a final PR
8. If a generated PR is **rejected**, the feedback loop re-generates improved content using the reviewer's comments and updates the product knowledge base

---

## 2. System Overview

The system comprises **five microservices** and one **shared library** (`common`). Each service runs independently and communicates via **Apache Kafka** (a message queue). External context is extracted and AI-compressed at ingestion and carried unchanged through the full pipeline.

```
  Developer opens PR
        │
        ▼
┌───────────────────────────────────────┐     Kafka: FeatureUpdatesQueue
│  pr-service  (port 8080)              │ ──────────────────────────────────►
│  • Webhook ingestion                  │
│  • Context extraction                 │
│    (Jira IDs, Confluence links,       │
│     labels, products → PrContext)     │
│  • AI context compression             │
│    (ContextCompressionService via     │
│     Copilot CLI → contextSummary)     │
└───────────────────────────────────────┘
                        │
          Kafka: FeatureUpdatesQueue
                        │
                        ▼
┌───────────────────────────────────────┐
│  impact-service  (port 8081)          │
│  • GitDiffParser → DependencyGraph    │
│  • ChangeTypeDetector → RiskScorer   │
│  • IntegrationTestScopeClassifier     │
│  • Optional AI gray-zone refinement   │
│  • Produces ImpactEnvelope + PrContext│
└───────────────────────────────────────┘
                        │
          Kafka: ImpactResultsQueue
                        │
                        ▼
┌───────────────────────────────────────┐
│  strategy-service  (port 8082)        │
│  • AiCallGate (rules — skip 40-60%)  │
│  • E2ECoverageAnalyzer (repo scan)    │
│  • StrategyAgent (SKIP/UPDATE/CREATE) │
│  • BddGenerator + PromptResponseCache │
│  • GitHub PR creation (qa/bdd/*)      │
│  • AiCostMonitor                      │
│  • GitHub webhook listener:           │
│    merge → TestScriptsQueue           │
│    reject → FeedbackQueue             │
└───────────────────────────────────────┘
         │                      │
TestScriptsQueue          FeedbackQueue
         │                      │
         ▼                      ▼
┌─────────────────┐   ┌───────────────────────────────────┐
│ codegen-service │   │  feedback-service  (port 8084)    │
│ (port 8083)     │   │  • Fetches GitHub review comments │
│ • API/UI/Mobile │   │  • Classifies: KNOWLEDGE_GAP /    │
│   test runners  │   │    STYLE_ONLY                     │
│ • Stabilization │   │  • Updates productExpert/*.md     │
│   loop (max 3×) │   │  • Re-generates with feedback     │
│ • Final test PR │   │  • Creates revised PR             │
│   (qa/tests/*)  │   └───────────────────────────────────┘
└─────────────────┘
```

---

## 3. What Is Apache Kafka and Why Is It Used Here?

**Apache Kafka** is a distributed message queue. Think of it like a post box: one service puts a message in, another service picks it up — but they don't need to be running at the same time or at the same speed.

### Why Kafka instead of direct HTTP calls?

| Option | Problem |
|--------|---------|
| pr-service calls impact-service directly | If impact-service is slow or down, pr-service hangs and the developer's webhook request times out |
| Impact-service calls strategy-service directly | Same coupling: one slow step blocks everything |
| **Kafka between each step** | Each service processes at its own pace. A slow strategy-service does not block a fast impact-service. Each service can have multiple consumers in parallel. Messages are durable — if strategy-service crashes, it picks up where it left off |

### Kafka topics

| Topic | Producer | Consumer | Payload |
|-------|----------|----------|---------|
| `FeatureUpdatesQueue` | pr-service | impact-service | `PullRequest` — with `contextSummary`, `products`, diff |
| `ImpactResultsQueue` | impact-service | strategy-service | `ImpactEnvelope` — carries `PrContext` + `prTitle` |
| `TestScriptsQueue` | strategy-service | codegen-service | `BddScenario` — carries `PrContext` + `prTitle` |
| `TestResultsQueue` | codegen-service | *(future consumers)* | `TestResult` |
| `FeedbackQueue` | strategy-service | feedback-service | `FeedbackEvent` — wraps `BddScenario` or `TestScript` |

> **`prTitle` propagation:** `PullRequest.title` → `ImpactEnvelope.prTitle` → `BddScenario.prTitle` → `TestScript.prTitle`.
> All QA-generated GitHub PR titles (initial + revised) derive from this field.

### Key Kafka concepts used here

| Term | Meaning in this system |
|------|----------------------|
| **Topic** | A named queue. `FeatureUpdatesQueue`, `ImpactResultsQueue`, `TestScriptsQueue`, `FeedbackQueue` |
| **Producer** | A service that puts messages in a topic |
| **Consumer** | A service that reads messages from a topic |
| **Consumer group** | Multiple instances of the same service that share the work |
| **Partition key** | Messages with the same key (e.g. `prId`) go to the same partition and are processed in order |
| **Manual ack** | The consumer explicitly tells Kafka "I finished processing this message." If the service crashes before acking, Kafka re-delivers |

---

## 4. The `common` Module

The `common` module is a shared Java library (JAR) compiled once and used by all five services. It contains all **data models**, shared **AI client interfaces**, **Kafka/Redis configuration**, and **shared services**.

### Key models

#### `PullRequest`
The input to the system. Represents a code change that a developer wants reviewed.

```
PullRequest
├── prId            "PR-A1B2C3D4"
├── title           "VSF-3670: Add payment gateway"
├── description     "Implements payment flow. See https://jira.example.com/browse/VSF-3670
│                    and https://wiki.example.com/wiki/spaces/PAYMENTS/pages/123"
├── author          "dev@example.com"
├── repositoryName  "payment-service"
├── sourceBranch    "feature/payments"
├── targetBranch    "main"
├── rawDiffContent  "diff --git a/src/PaymentService.java ..."
├── diffs           List<GitDiff>  (pre-parsed, optional)
├── jiraIds         ["VSF-3670"]              (explicit or extracted from title/desc)
├── jiraLinks       ["https://jira.../VSF-3670"] (extracted from description)
├── confluenceLinks ["https://wiki.../pages/123"] (extracted from description)
├── labels          ["bug", "payments", "high-priority"]  (GitHub PR labels)
├── products        ["payments", "auth"]      (explicit or inferred from labels)
└── contextSummary  "Adds JWT auth to payment flow…" (AI-compressed, set by ContextCompressionService)
```

**Context extraction** happens in `PrContextExtractor` during pr-service enrichment.
It scans the PR title, description, and labels using regex patterns:
- Jira IDs: `\b[A-Z]{2,10}-\d+\b` (e.g. VSF-3670, AUTH-12)
- Jira URLs: any URL containing `/browse/PROJECT-NNN`
- Confluence URLs: any URL containing `/wiki/`
- Product names: explicit `products` field + lower-case labels without special chars

**Context compression** happens immediately after extraction. `ContextCompressionService`
calls GitHub Copilot (via `gh` CLI) to distil the title + description + labels into a
focused plain-text summary stored in `contextSummary`. This is carried through Kafka so
all downstream AI calls receive lean, token-efficient context instead of verbose boilerplate.

#### `GitDiff`
Represents one changed file. A PR touching 5 files produces 5 `GitDiff` objects.

```
GitDiff
├── filePath        "src/main/java/PaymentService.java"
├── diffType        ADDED | MODIFIED | DELETED | RENAMED
├── linesAdded      45
├── linesDeleted    12
├── isTestFile      false
└── hunks           List<DiffHunk>
                      └── lines: List<DiffLine>
                                  ├── type: ADDED | REMOVED | CONTEXT
                                  └── content: the actual line of code
```

#### `PrContext`
Carries all external context extracted from the PR through the entire pipeline.
Created once by `PrContextExtractor` in pr-service and never modified downstream.

```
PrContext
├── jiraIds         ["VSF-3670", "AUTH-12"]  (merged from payload + regex extraction)
├── jiraLinks       ["https://jira.../browse/VSF-3670"]
├── confluenceLinks ["https://wiki.../pages/123"]
├── labels          ["bug", "payments", "high-priority"]
├── products        ["payments", "auth"]
└── summary         "Adds JWT auth to payment flow; risk areas: token refresh…" (from contextSummary)
```

`PrContext.asPromptSection()` formats this into a structured text block. When `summary` is
set, it is prepended as a `=== COMPRESSED CONTEXT SUMMARY ===` block before the structured
Jira/Confluence/label detail. All downstream AI prompts (BDD generation, code generation,
feedback re-generation) receive this section automatically.

#### `ImpactEnvelope`
The output of `impact-service`. Contains everything the strategy layer needs.

```
ImpactEnvelope
├── envelopeId            (UUID)
├── prId
├── prTitle               (copied from PullRequest.title — used for GitHub PR naming)
├── prContext             PrContext  (Jira, Confluence, labels, products, summary)
├── impactedComponents    List<ImpactedComponent>
│     └── componentName, filePath, type (CONTROLLER/SERVICE/...), impactScore, callers, callees
├── detectedChangeTypes   List<ChangeType>  (API_CHANGE, BUG_FIX, SECURITY_FIX, ...)
├── overallRiskScore      0.0 – 1.0
├── riskLevel             LOW | MEDIUM | HIGH | CRITICAL
├── coverageReport        CoverageReport (level=UNKNOWN at this point)
├── suggestedTestAreas    List<String>  (component names needing coverage)
├── changesSummary        Human-readable one-line description
└── aiInsight             AIInsight  (null when AI not triggered)
```

#### `CoverageReport`
The two-phase coverage model — starts UNKNOWN in impact-service, gets resolved to real data in strategy-service.

```
CoverageReport
├── source            UNKNOWN | REPO_SCAN
├── level             GOOD | PARTIAL | NONE | UNKNOWN
├── coverageRatio     0.0 – 1.0  (meaningful only after REPO_SCAN)
├── testedComponents  List of component names WITH existing integration tests
├── untestedComponents List of component names WITHOUT integration tests
├── requiredTestTypes  ["API", "INTEGRATION", "E2E"]
├── existingTestFiles  List of test filenames found in the repo
└── requiresNewTests  boolean
```

#### `BddScenario`
The output of `BddGenerator`. Carries the full scenario content plus context for codegen-service.

```
BddScenario
├── scenarioId    (UUID)
├── prId
├── prTitle       (copied from ImpactEnvelope — used for GitHub PR title)
├── prContext     PrContext  (forwarded unchanged from ImpactEnvelope)
├── testType      API | UI | MOBILE
├── featureTitle
├── scenarios     List<String>  (Gherkin text blocks)
└── tags          ["@api", "@pr-XXXX", "@auto-generated"]
```

#### `FeedbackEvent`
Published to `FeedbackQueue` when a BDD or test PR is rejected on GitHub.

```
FeedbackEvent
├── eventId       (UUID)
├── prId
├── feedbackType  BDD_REJECTED | TEST_REJECTED
├── prUrl         URL of the rejected GitHub PR
├── bddScenario   BddScenario  (present when feedbackType=BDD_REJECTED)
└── testScript    TestScript   (present when feedbackType=TEST_REJECTED)
```

### Shared services in `common`

| Class | Role |
|-------|------|
| `AiClient` (interface) | Abstraction over the LLM API. Methods: `complete(systemPrompt, userPrompt)`, `isAvailable()`, and default `completeWithHistory(systemPrompt, List<ChatMessage> history, newUserMessage)` for multi-turn conversations. |
| `CopilotCliClient` | Calls Copilot API via `gh api` subprocess — no token env var needed. Overrides `completeWithHistory()` to build a full multi-turn messages array for the GitHub Models API. |
| `AiClientConfig` | Creates the `CopilotCliClient` bean; gated on `aiqa.github.enabled=true` |
| `AiProviderProperties` | `@ConfigurationProperties(prefix="aiqa.ai")` |
| `GitHubService` | GitHub API calls: diff fetch, PR creation, webhook signature verification |
| `RepoContextService` | Clone + index test repo; build coverage index; extract conventions |
| `RedisPrTracker` | Redis-backed PR state (`@ConditionalOnProperty`) |
| `InMemoryPrTracker` | Fallback PR state tracker when Redis is unavailable |
| `GitDiffParser` | Parse raw unified diff string → `List<GitDiff>` |
| `KafkaConfig` | `ConcurrentKafkaListenerContainerFactory` with `MANUAL_IMMEDIATE` ack |
| `ChatMessage` | Java 25 record: `(String role, String content)`. Static factory methods: `system()`, `user()`, `assistant()`. |
| `ConversationHistory` | Java 25 record: `(String prId, List<ChatMessage> turns, int totalTurns, Instant lastUpdated)`. |
| `ConversationStore` (interface) | `save(conversationId, history)`, `load(conversationId)`, `remove(conversationId)`. |
| `RedisConversationStore` | `@ConditionalOnProperty(spring.data.redis.host)`. GZIP+Base64 compressed. Key pattern: `qa:chat:{conversationId}`. Configurable TTL (`aiqa.conversation.ttl-days`). 1 MB decompression OOM guard. Size management: compress first, then drop oldest turns if still over limit. |
| `InMemoryConversationStore` | `@ConditionalOnMissingBean` fallback. Non-persistent; state lost on JVM restart. |

---

## 5. pr-service — Phase 0: Ingestion, Context Extraction & Compression

**Port:** 8080 | **Role:** Receive PR events, validate, enrich, extract external context, AI-compress the context, publish to Kafka

### What it does

1. Developer (or CI webhook) sends a POST request with PR details
2. Service validates required fields (title, author, repository name)
3. Service fills in defaults for optional fields (prId, targetBranch, timestamps)
4. **`PrContextExtractor`** mines the PR's title, description, and labels for Jira IDs, Jira URLs, Confluence URLs, labels, and product names
5. **`ContextCompressionService`** AI-compresses the textual context (when enabled):
   - Calls GitHub Copilot via the `gh` CLI subprocess (no extra token required)
   - Strips PR template boilerplate, markdown checklists, screenshots, redundant phrasing
   - Stores the result in `PullRequest.contextSummary` (max ~250 words)
   - Original `description` is preserved — compression is additive
   - Raw diff and structured fields (`jiraIds`, `labels`, etc.) are **never compressed**
   - Best-effort: on failure or when disabled, the PR is passed through unchanged
6. The enriched `PullRequest` (with `contextSummary` set) is published to `FeatureUpdatesQueue`

### Context compression configuration

```yaml
aiqa:
  ai:
    compression:
      enabled: ${AIQA_COMPRESSION_ENABLED:false}   # set true to enable
      gh-cli-path: ${GH_CLI_PATH:gh}
      model: ${COPILOT_CLI_MODEL:gpt-5}
      timeout-seconds: ${COPILOT_CLI_TIMEOUT:60}
```

> **Why isolated from shared `AiClientConfig`:** The shared config requires `aiqa.github.enabled=true`,
> which must NOT be set in pr-service (it would trigger GitHub/AI beans requiring credentials at startup).
> The compression service creates its own `CopilotCliClient` instance directly.

### Classes

| Class | Role |
|-------|------|
| `PrServiceApplication` | Spring Boot entry point |
| `PRController` | HTTP: 4 endpoints (`/webhook`, `/submit`, `/demo`, `/health`) |
| `PRService` | Enrich → extract context → compress → validate → publish |
| `PrContextExtractor` | Regex-based extraction of Jira, Confluence, labels, products |
| `ContextCompressionService` | AI compression via Copilot CLI; stores result in `contextSummary` |
| `CompressionConfig` | Creates `ContextCompressionService` bean with its own `CopilotCliClient` |
| `CompressionProperties` | `@ConfigurationProperties(prefix = "aiqa.ai.compression")` |
| `FeatureUpdatesProducer` | Serialise + send to FeatureUpdatesQueue |

### Endpoints

| Endpoint | Use case | Notes |
|----------|----------|-------|
| `POST /api/pr/webhook` | GitHub webhook integration | Lenient — no strict validation |
| `POST /api/pr/submit` | Programmatic submission | Strict `@Valid` annotation enforcement |
| `POST /api/pr/demo` | Quick local testing | Uses a built-in JWT auth sample PR |
| `GET /api/pr/health` | Health check | Returns `{"status":"UP"}` |

---

## 6. impact-service — Phase 1: Deterministic Analysis

**Port:** 8081 | **Role:** Parse the diff, classify the change, score risk, identify coverage gaps

> **No AI here by default.** Every step is a deterministic algorithm. The same diff always produces the same output. Optional AI gray-zone refinement is available but off by default.

### The 5-step pipeline

```
PullRequest  →  [GitDiffParser]  →  [DependencyGraph]  →  [ChangeTypeDetector]
             →  [RiskScorer]     →  [IntegrationTestScopeClassifier]  →  ImpactEnvelope
```

### Step 1: GitDiffParser — Reading the raw diff

A **git diff** is a plain-text format that shows what changed in a file. Example:

```
diff --git a/src/PaymentService.java b/src/PaymentService.java
@@ -10,6 +10,8 @@ public class PaymentService {
     private final PaymentRepository repo;
+    private final AuditService audit;
+
     public void processPayment(Payment p) {
```

The parser reads this line by line:
- `diff --git` → start of a new file
- `@@ ... @@` → start of a hunk (a changed region within the file)
- Line starting with `+` → added line
- Line starting with `-` → removed line
- Line starting with space → unchanged context line

**Output:** A structured `List<GitDiff>` where each diff has typed hunks and lines,
plus metadata like `linesAdded`, `linesDeleted`, and whether it is a test file.

### Step 2: DependencyGraph — Who depends on what?

For every changed file, `DependencyGraph` extracts Java `import` statements from the
changed lines and builds a map of callers and callees. A class with many callers has a
wider impact ("blast radius") and gets a higher impact score.

**ComponentType detection** (from file path keywords):

| Path keyword | ComponentType | Significance |
|-------------|---------------|--------------|
| `controller` | CONTROLLER | HTTP endpoint — highest user-facing risk |
| `service` | SERVICE | Business logic |
| `repositor` or `dao` | REPOSITORY | Database access |
| `model`, `entity`, `dto` | MODEL | Data structure |
| `config` | CONFIG | System configuration |
| `test` or `spec` | TEST | Test file |
| `util` or `helper` | UTILITY | Helper code |

**Impact score formula:**
```
base         = min(1.0, (linesAdded + linesDeleted) / 200)
callerBonus  = min(0.3, numberOfCallers × 0.1)
impactScore  = min(1.0, base + callerBonus)
```

### Step 3: ChangeTypeDetector — What kind of change is this?

Uses regex patterns on file paths and diff content to classify the change. Multiple types can be detected per PR.

| File path matches | ChangeType |
|-------------------|-----------|
| `application.yml`, `.env`, `config/` | `CONFIGURATION_CHANGE` |
| `pom.xml`, `build.gradle`, `package.json` | `DEPENDENCY_UPDATE` |
| `migration`, `flyway`, `liquibase`, `.sql` | `DATABASE_CHANGE` |

| Keywords found in changed lines | ChangeType |
|----------------|-----------|
| `TODO`, `FIXME`, `bug`, `fix`, `patch` | `BUG_FIX` |
| `@Deprecated`, `rename`, `refactor` | `REFACTORING` |
| `security`, `auth`, `token`, `password`, `secret` | `SECURITY_FIX` |
| `performance`, `cache`, `async`, `parallel` | `PERFORMANCE_IMPROVEMENT` |
| `@RestController`, `@GetMapping`, `@PostMapping`, etc. | `API_CHANGE` |

Special rules: new non-test file → `NEW_FEATURE`; deleted file → `REFACTORING`; >50 lines deleted → `BREAKING_CHANGE`.

### Step 4: RiskScorer — How risky is this change?

```
riskScore = (0.25 × churnScore)
          + (0.30 × changeTypeScore)
          + (0.25 × componentScore)
          + (0.20 × coverageScore)
```

| ChangeType | Severity |
|-----------|---------|
| BREAKING_CHANGE | 1.0 |
| SECURITY_FIX | 0.9 |
| DATABASE_CHANGE | 0.8 |
| API_CHANGE | 0.7 |
| DEPENDENCY_UPDATE | 0.6 |
| NEW_FEATURE / BUG_FIX | 0.5 |
| PERFORMANCE_IMPROVEMENT | 0.4 |
| CONFIGURATION_CHANGE / REFACTORING | 0.3 |

**Risk levels:** CRITICAL (≥0.9) · HIGH (≥0.7) · MEDIUM (≥0.4) · LOW (<0.4)

### Step 4b: AIImpactEvaluator — AI Last Resort (optional)

Applied only in the gray zone [0.30, 0.75] where the deterministic system has the least signal.
The LLM returns `adjustedRiskScore` (clamped ±0.15), `additionalChangeTypes`, and `reasoning`.
Every failure mode is caught and returns the deterministic result unchanged.

### Step 5: IntegrationTestScopeClassifier

Phase 1 of the two-phase coverage assessment — identifies which component types need integration
tests but cannot say whether those tests exist. Sets `CoverageReport.level=UNKNOWN` in the `ImpactEnvelope`.

| ComponentType | Needs integration test? |
|--------------|------------------------|
| CONTROLLER | ✅ Yes → API/E2E tests |
| SERVICE | ✅ Yes → INTEGRATION tests |
| REPOSITORY | ✅ Yes → INTEGRATION tests |
| CONFIG | ✅ Yes → SMOKE tests |
| MODEL | ❌ No |
| UTILITY | ❌ No |

---

## 7. strategy-service — Phases 2–4: Strategy, BDD Generation, GitHub PR

**Port:** 8082 | **Role:** Real coverage check → decision → BDD generation → GitHub PR → approval gate → trigger codegen or feedback

### 7.1 AI Cost Gating — Three Layers Before Any API Call

Every PR that arrives is passed through three layers before making a network request to an LLM:

```
ImpactEnvelope
      │
      ▼
① AiCallGate  (zero-cost rule engine, runs first)
      ├─ SKIP         → docs-only, test-only PRs, trivial <10-line diffs, version bumps
      ├─ RULE_HANDLED → low-risk + existing tests, config-only changes → template, no AI
      └─ NEEDS_AI ──────────────────────────────────────────────────────────┐
                                                                            ▼
② PromptResponseCache  (Redis, 24h TTL, keyed on changeType+componentType+risk+repo)
      ├─ HIT  → return cached gherkin, no AI call
      └─ MISS ──────────────────────────────────────────────────────────────┐
                                                                            ▼
③ AiClient.complete()  (copilot-cli)
   → store response in cache for future identical patterns
```

| Layer | Estimated saving |
|-------|-----------------|
| `AiCallGate` | 40–60% of calls eliminated |
| `PromptResponseCache` | 20–30% additional |
| **Combined** | **60–75% fewer AI calls** |

Metrics are exposed at `GET /api/qa/cost/report` via `AiCostMonitor` (Micrometer).

### 7.2 RepoContextService — Cloning and Indexing the Test Repository

`RepoContextService` runs on startup and keeps a local clone of the target test repository fresh:
- **Context provider** — extracts coding conventions for the test code generators
- **Coverage index builder** — scans which components already have integration/E2E tests

**Inverted index** maps component names → test files covering them:
```
coverageIndex:
  "PaymentController" → ["PaymentControllerIT.java", "CheckoutE2ETest.java"]
  "OrderService"      → ["OrderServiceIntTest.java"]
```
This makes coverage lookup **O(1)** per component at query time.

**Integration/E2E test identification heuristics:**

| Signal | What it means |
|--------|--------------|
| `.feature` extension | Gherkin file — always acceptance/E2E |
| Filename ends in `IT`, `IntegrationTest`, `IntTest` | Java naming convention |
| Filename ends in `E2ETest`, `AcceptanceTest` | Explicit E2E naming |
| Content has `@SpringBootTest` | Full application context — not a unit test |
| Content has `RestAssured`, `MockMvc`, `WebTestClient` | HTTP-level testing |

**Conductor agent:** `loadAgentInstructions()` reads all `.github/agents/*.md` files and identifies the one whose content contains "conductor" (case-insensitive). This file is the conductor agent, accessible via `RepoContext.hasConductorAgent()` / `getConductorAgentContent()`, and is used as the primary AI role directive in `BddGenerator`.

**Security:** `runGit()` sanitises all git output before logging or propagating exceptions (strips `https://token@` patterns). `refresh()` returns a generic error message in the API response — raw git error detail is never exposed externally.

### 7.3 E2ECoverageAnalyzer — Phase 2 Coverage Assessment

Receives the `ImpactEnvelope` with `level=UNKNOWN` and replaces it with a real assessment:

| Level | Meaning | StrategyAgent response |
|-------|---------|----------------------|
| `GOOD` | All testable components have integration tests | Follow other rules (may SKIP) |
| `PARTIAL` | Some components covered, some not | UPDATE_TESTS |
| `NONE` | No integration tests found for any component | Force CREATE_TESTS |
| `UNKNOWN` | No test repo configured | Conservative — follow other rules |

### 7.4 StrategyAgent — The Decision Maker

Decision tree (first match wins):

| Priority | Condition | Decision |
|----------|-----------|----------|
| 1 | `coverage.level == NONE` AND `untestedComponents` not empty | `CREATE_TESTS` |
| 2 | All changes are config/dependency AND risk is LOW | `SKIP` |
| 3 | PR only changes test files | `SKIP` |
| 4 | Risk is HIGH or CRITICAL | `CREATE_TESTS` |
| 5 | `NEW_FEATURE` detected | `CREATE_TESTS` |
| 6 | `coverage.level == PARTIAL` | `UPDATE_TESTS` |
| 7 | PR includes test file changes | `UPDATE_TESTS` |
| 8 | Default | `CREATE_TESTS` |

**Fallback rules:** `confidenceScore < 0.4` → `fullRegressionRequired=true`; CRITICAL/HIGH risk → expanded scope.

### 7.5 BddGenerator — Creating Human-Readable Test Scenarios

Produces BDD scenarios in Gherkin syntax by delegating to the target repository's
**Conductor** agent via `ConductorAgentRunner`. No AI API is called directly and no other
agent is ever invoked — the Conductor orchestrates any internal sub-delegation itself.

#### Single Conductor delegation (only generation path)

```
copilot --allow-all --autopilot --silent --max-autopilot-continues 5
        --agent=Conductor -p "<Gherkin generation request>"
  Reads: target repo's .github/agents/Conductor.agent.md (automatically)
  Produces: Gherkin feature file starting with "Feature:"
```

The subprocess runs in the **cloned target repo directory** as its working directory, so the
Conductor loads the repo's own `.github/agents/` instruction files naturally and gathers any
context it needs (impacted modules, conventions, product knowledge) by itself — strategy-service
no longer scans `tests/api|ui|mobile` modules for context.

#### Live monitoring

`ConductorAgentRunner` parses each stdout line for `agent_message_chunk` JSON-RPC events and
logs the text at INFO in real time, making pipeline progress visible in service logs without
polling. Stderr is always drained on a separate virtual thread to prevent OS pipe-buffer deadlock.

#### Concurrency and process lifecycle

- `Semaphore(maxConcurrentAgents=3)` limits simultaneous subprocess launches (configurable).
- `process.destroyForcibly()` is called on timeout **and** in the `finally` block as defensive
  cleanup — it is a no-op for already-terminated processes, preventing orphaned Node.js processes
  if an unexpected exception occurs after `pb.start()`.
- Each subprocess runs on virtual threads for stdout/stderr I/O.

#### No fallback

There is no `AiClient` fallback and no template placeholder. `BddGenerator.generate()` throws
`IllegalStateException` when the Conductor produces no Gherkin output.

The generated `BddScenario` carries the full `PrContext` forward to codegen-service.

Tags added automatically: `@api`/`@ui`/`@mobile`, `@pr-{prId}`, `@auto-generated`, `@smoke` (HIGH/CRITICAL).


### 7.6 GitHub PR Workflow

After BDD generation, `TestPrService` creates a GitHub PR (`qa/bdd/*`).
The strategy-service GitHub webhook listens for PR events on the target repo:

```
BDD PR created  →  Human reviews
      ├─ MERGED   → publishes BddScenario to TestScriptsQueue → codegen-service generates test code
      └─ CLOSED   → publishes FeedbackEvent to FeedbackQueue  → feedback-service re-generates
```

Local development bypass (no ngrok required):
```bash
curl -X POST http://localhost:8082/api/strategy/approve-bdd \
  -H "Content-Type: application/json" \
  -d '{...BddScenario JSON...}'
```

### 7.7 Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /api/strategy/status` | Status + pending BDD review count |
| `GET /api/strategy/pending-bdd` | List all tracked BDD scenarios waiting for approval |
| `POST /api/strategy/approve-bdd` | Manually trigger codegen from BDD JSON |
| `POST /api/strategy/github-webhook` | GitHub `pull_request` webhook (BDD/test PR merge/reject) — signature verification enforced by default (`require-secret: true`) |
| `POST /api/strategy/refresh-context` | Re-pull target test repo, refresh coverage cache |
| `GET /api/qa/cost/report` | AI call gating / cache / cost metrics |

---

## 8. codegen-service — Phase 5: Test Code Generation

**Port:** 8083 | **Role:** Receive approved BDD scenarios, generate executable test code, stabilise, open test PR

codegen-service is a dedicated service for test code generation and stabilisation. It consumes
`TestScriptsQueue` (published by strategy-service after BDD PR merge) and handles the full
code generation + execution + healing cycle independently.

> **AI model:** Claude Sonnet 4.6 (default via `${COPILOT_CLI_MODEL:claude-sonnet-4.6}`)

### What it does

1. Consumes `BddScenario` from `TestScriptsQueue`
2. For each scenario, delegates test-code generation to the target repository's **Conductor**
   agent via `ConductorCodeGenerator` → `ConductorAgentRunner`
3. The Conductor subprocess runs in the cloned target repo directory and gathers any context
   it needs itself (no `tests/api|ui|mobile` module scan, no `IllegalStateException` on missing context)
4. Captures the generated Java source as a string
5. Runs a `StabilizationLoop` (up to 3 attempts)
6. Creates a final GitHub PR (`qa/tests/*`) — even on failure (`⚠️ [NEEDS REVIEW]`)

### Conductor-delegated generation

All test code (API / UI / MOBILE) is generated by a single Conductor agent subprocess — there
are no per-type template runners and no direct AI API calls. The `testType` and BDD steps are
passed in the prompt; the Conductor produces idiomatic test code following the repository's own
framework, base classes, package layout, and naming conventions.

| `testType` | Dependencies recorded on the `TestScript` |
|-----------|--------------------------------------------|
| `API` | `restassured`, `junit5`, `assertj` |
| `UI` | `selenium`, `webdriver-manager` |
| `MOBILE` | `appium`, `selenium` |


### StabilizationLoop — Run, Fail, Fix

```
for attempt = 1 to maxRetries (default 3):
  result = testExecutionEngine.execute(script, attempt)
  if PASSED:
    mark script as PASSED, create final PR, return
  if attempt < maxRetries:
    apply progressive fix
    wait retryDelayMs (default 2000ms)
mark script as ABANDONED, create ⚠️ PR for human review
```

**Three progressive fixes:**
- **Attempt 1** — Timeout and connection fixes (RestAssured config + `@Timeout`)
- **Attempt 2** — Retry wrapper + null guards around assertions
- **Attempt 3** — Abandon original, generate minimal smoke test (health check only)

**BDD step → assertion translation (API runner):**

| BDD `then` step contains | Generated Java assertion |
|--------------------------|--------------------------|
| `"200"` | `.statusCode().isEqualTo(200)` |
| `"4xx"` or `"5xx"` | `.statusCode().isGreaterThanOrEqualTo(400)` |
| `"2000ms"` | `response.time().isLessThan(2000L)` |
| anything else | `// {step}` (placeholder comment for human fill-in) |

### GitHub PR title format

| Status | Branch | Title |
|--------|--------|-------|
| Passed | `qa/tests/*` | `✅ [AI-QA] {prTitle}` |
| Abandoned | `qa/tests/*` | `⚠️ [NEEDS REVIEW] [AI-QA] {prTitle}` |
| Revised (after feedback) | `qa/tests/*-rev-*` | `[AI-QA] Revised Tests: {prTitle}` |

---

## 9. feedback-service — AI Feedback Loop

**Port:** 8084 | **Role:** Process rejected QA PRs, update product knowledge base, re-generate with feedback

When a QA-generated BDD or test PR is **closed/rejected** on GitHub, the system automatically
learns from the reviewer's comments and re-generates improved content.

### What it does

1. Consumes `FeedbackEvent` from `FeedbackQueue`
2. Fetches all review comments from the rejected GitHub PR via `GitHubService`
3. Classifies the rejection reason via AI:
   - `KNOWLEDGE_GAP` — the system lacked domain knowledge (e.g. missing business rules, edge cases)
   - `STYLE_ONLY` — formatting or naming issues only
4. If `KNOWLEDGE_GAP`:
   - Appends new knowledge to `productExpert/{product}/PRODUCT.md` in the target repo
   - Opens a separate knowledge-update PR for human review
5. Re-generates the rejected content with feedback as additional context in the AI prompt
6. Creates a revised PR (`-rev-*` suffix in the branch name)
7. Records the rejected scenario classes (REGRESSION + NEGATIVE per capability) in the cross-PR
   `RejectionLedger` so `CoveragePlanner` forces those gaps into future coverage plans
   (`aiqa.feedback.recurrence-threshold`, default 2; TTL `aiqa.feedback.ledger-ttl-days`, default 90)

The entire feedback cycle runs on a **virtual thread** so the GitHub webhook HTTP response
is returned immediately without blocking.

### Multi-turn conversation context

`PrFeedbackService` uses stateless multi-turn history to give the AI full context of every
prior generation and rejection cycle for a given PR:

- **BDD generation** (`BddGenerator` in strategy-service) saves the initial `system` + `user` + `assistant` turns to `ConversationStore` at key `{prId}:bdd` after each scenario is produced.
- **Test code generation** (`CodegenService` in codegen-service) saves turns at key `{prId}:test`.
- On each PR rejection, `PrFeedbackService` loads the stored history from Redis (key `{prId}:bdd` or `{prId}:test`) and passes it to `AiClient.completeWithHistory()`, so the model sees every prior generation attempt and rejection comment.
- The updated history (including the new user feedback turn and assistant response) is saved back to `ConversationStore` after each AI response.
- Because history is serialised to Redis, any pod can handle any PR — no long-lived processes or sticky sessions are required.

### PR lifecycle

| Event | Branch | Title format |
|-------|--------|--------------|
| BDD initial | `qa/bdd/*` | `[AI-QA] {prTitle}` |
| BDD revised (after rejection) | `qa/bdd/*-rev-*` | `[AI-QA] Revised: {prTitle}` |
| Test initial | `qa/tests/*` | `✅ [AI-QA] {prTitle}` |
| Test revised (after rejection) | `qa/tests/*-rev-*` | `[AI-QA] Revised Tests: {prTitle}` |

### Target repo knowledge structure

```
{test-repo}/
  productExpert/
    payments/
      PRODUCT.md    ← domain flows, business rules, edge cases (updated by feedback-service)
      PATTERNS.md   ← assertion patterns, test structure
  .aiqa/
    context.md      ← team-wide QA conventions
  .github/
    agents/
      api-conventions.md   ← agent instruction files (read by RepoContextService)
```

---

## 10. AI Provider Configuration

All services that use AI share the same provider configuration, loaded via `AiProviderProperties`
when `aiqa.github.enabled=true` is set.

The only supported AI provider is **Copilot CLI** (`CopilotCliClient`), which calls the Copilot
API via the `gh api` subprocess. Run `gh auth login` once — no token env var is required.
`aiqa.ai.provider` is hardcoded to `copilot-cli` and is not overridable via an environment variable.

BDD and test-code generation use **`ConductorAgentRunner`** (in `common`, shared by
strategy-service, codegen-service, and feedback-service), which launches a `copilot` subprocess
that always delegates to the repository's **Conductor** agent. This is separate from
`CopilotCliClient` and uses a different executable path (`copilot`) and concurrency model. No
other agent is ever invoked, and no AI API is called directly for generation.

### Per-service model defaults

| Service | Default model | Why |
|---------|--------------|-----|
| strategy-service (BDD generator) | `gpt-5` | Best scenario quality for human review |
| codegen-service (test generator) | `claude-sonnet-4.6` | Strong code generation capabilities |
| feedback-service | `gpt-5` | Reasoning over review comments |
| impact-service (gray-zone AI) | `gpt-4o` | Lightweight — called rarely, only in gray zone |
| pr-service (compression) | `gpt-5` | Compression uses its own isolated config |

### Full config reference

```yaml
aiqa:
  ai:
    provider: copilot-cli                      # only supported provider
    copilot-cli:
      gh-cli-path: ${GH_CLI_PATH:gh}
      model:       ${COPILOT_CLI_MODEL:gpt-5}
      timeout-seconds: ${COPILOT_CLI_TIMEOUT:120}
    copilot-agent:                             # strategy-service only — two-phase BDD pipeline
      copilot-cli-path: ${COPILOT_CLI_PATH:copilot}
      max-concurrent-agents: ${COPILOT_MAX_CONCURRENT:3}      # semaphore limit
      agent-timeout-seconds: ${COPILOT_AGENT_TIMEOUT:300}     # hard kill per subprocess
      max-output-chars: ${COPILOT_MAX_OUTPUT_CHARS:200000}    # accumulated text cap
      max-autopilot-continues: ${COPILOT_MAX_AUTOPILOT_CONTINUES:5}  # CLI continuation limit
      working-dir: ${COPILOT_AGENT_WORKING_DIR:}              # blank = target-repo local-path
  conversation:
    ttl-days:             ${AIQA_CONV_TTL_DAYS:30}          # Redis TTL for conversation history; 0 = no expiry
    max-history-chars:    ${AIQA_CONV_MAX_CHARS:65536}      # uncompressed size threshold; exceeded → compress first, then drop oldest turns
    max-compressed-bytes: ${AIQA_CONV_MAX_BYTES:32768}      # compressed size limit; if full history fits within this after GZIP, all turns are kept
```

> `aiqa.conversation.*` applies to feedback-service, strategy-service, and codegen-service.

### Context trace capture (debug / observability)

The `ConductorAgentRunner` delegation boundary can be instrumented for inspection. When
`aiqa.trace.enabled=true` (default **false**), each `copilot --agent=Conductor` invocation
persists — under `aiqa.trace.dir` (default `./logs/context-traces`) — the exact prompt sent
(`prompt.txt`), the **full raw JSON-RPC agent stream** that is otherwise capped and discarded
(`raw-stream.jsonl`: tool calls, file reads, sub-agent handoffs), the final assembled output
(`final.txt`), and a `meta.json` (task type, PR id, exit code, sizes, timing), plus a
`trace-index.jsonl`. Purpose: inspect and optimise the context shared with the LLM. The capture
is **best-effort** (never throws into the pipeline), secrets are redacted best-effort, files are
owner-only, and the raw stream is size-capped. See README § "Context Trace Capture" and
`.env.example` (`AIQA_TRACE_*`) for configuration.

---

## 11. The Two-Phase Coverage Assessment — In Depth

### The problem it solves

The question *"does `PaymentController` have integration test coverage?"* requires:
1. Knowing what `PaymentController` is (from the PR diff in impact-service)
2. Scanning the test repository to find tests that cover it (in strategy-service)

They are separate services and should not be coupled.

### Phase 1 in impact-service

`IntegrationTestScopeClassifier` produces a `CoverageReport` with `level=UNKNOWN`:

```
Input:  [PaymentController (CONTROLLER), JwtToken (MODEL), PaymentService (SERVICE)]
Filter: CONTROLLER → needs integration test
        MODEL      → does NOT need integration test
        SERVICE    → needs integration test
Output: CoverageReport(level=UNKNOWN, untestedComponents=["PaymentController", "PaymentService"])
```

### Phase 2 in strategy-service

`E2ECoverageAnalyzer.analyze(envelope)` replaces UNKNOWN with a real assessment:

```
coverageIndex: "PaymentService"    → ["PaymentServiceIT.java"]
               "PaymentController" → (no entry)

→ PaymentController: UNCOVERED
→ PaymentService:    COVERED

covered (1) >= uncovered (1) → PARTIAL
```

`StrategyAgent` uses this: `PARTIAL` → `UPDATE_TESTS`.

### Phase 3 in strategy-service — CoveragePlanner (scenario-class gap matrix)

`E2ECoverageAnalyzer` only answers *does this component have a test?* `CoveragePlanner.plan(...)`
upgrades that to *is it tested well?* It maps each `ChangeType` to a required `ScenarioClass` set
(e.g. `NEW_FEATURE` → happy+alternate+boundary+negative+auth+error; `API_CHANGE` →
happy+negative+auth+compat+error; `BUG_FIX` → regression+negative+boundary) and builds a
`ScenarioMatrix` (capability × class). Covered components mark their HAPPY_PATH cell COVERED; the
rest are PLANNED. Classes that keep recurring in `RejectionLedger` are forced back in. `recall =
covered / (covered + planned)`. `BddGenerator` then writes one scenario per PLANNED cell instead of
open-ended prose, so coverage is deterministic and gap-driven.

---

## 12. Kafka Manual Acknowledgement — Why It Matters

impact-service, strategy-service, codegen-service, and feedback-service all consume Kafka messages
with `AckMode.MANUAL_IMMEDIATE`.

**Why not use auto-commit?**
With auto-commit, if the service crashes *after* the offset was advanced but *before* processing
completed, the message is lost. With manual ack, Kafka re-delivers the message on restart.

This requires explicit `KafkaConfig.java` in each consuming service:

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    factory.setConcurrency(3);  // 3 parallel consumer threads
    return factory;
}
```

---

## 13. Multi-Module Maven Build

```
QA-ISystem/                 ← parent pom.xml (groupId: nz.co.eroad, version: 0.0.1-SNAPSHOT)
├── common/                 ← shared models, AI clients, Kafka/Redis config JAR
├── pr-service/             ← depends on common  (port 8080)
├── impact-service/         ← depends on common  (port 8081)
├── strategy-service/       ← depends on common  (port 8082)
├── codegen-service/        ← depends on common  (port 8083)
└── feedback-service/       ← depends on common  (port 8084)
```

**Build command (all modules, dependency order):**
```bash
./mvnw clean package -DskipTests
# or with tests:
./mvnw clean install
```

**Package namespace:** `nz.co.eroad.qaisystem` — all classes in all modules use this as their root package.

---

## 14. Docker Compose Setup

### Infrastructure containers (`docker-compose.yml`)

| Image | Version | Container | Purpose |
|-------|---------|-----------|---------|
| `confluentinc/cp-zookeeper` | `7.6.0` | `qa-zookeeper` | Kafka metadata coordinator. Required by the Confluent Kafka broker for leader election and topic metadata. Pinned to match the Kafka image version exactly. |
| `confluentinc/cp-kafka` | `7.6.0` | `qa-kafka` | Event bus for all inter-service communication. Single-broker, single-partition config suitable for local dev (`REPLICATION_FACTOR=1`). |
| `redis:7-alpine` | `7-alpine` | `qa-redis` | In-memory state store used by `RedisPrTracker` (PR state machine), `PromptResponseCache` (dedup AI calls), and `RedisConversationStore` (conversation history). Alpine variant keeps it ~30 MB. Persistence disabled (`--save ""`). |
| `provectuslabs/kafka-ui` | `latest` | `qa-kafka-ui` | Optional dev-only web UI for inspecting Kafka topics and messages at `http://localhost:8090`. Only started when `start-local.sh --with-kafka-ui` is used. Not included in prod. |

### Application image build stages (all five Dockerfiles)

All service Dockerfiles use a two-stage build:

| Stage | Image | Used by | Why |
|-------|-------|---------|-----|
| **Build** | `eclipse-temurin:25-jdk` | All 5 services | Full JDK to compile and package Maven modules inside the container. Dependencies are pre-fetched in a separate `dependency:go-offline` layer so source changes don't re-download jars. |
| **Runtime** | `eclipse-temurin:25-jre` | `pr-service`, `impact-service`, `feedback-service` | JRE-only — smaller image (~200 MB vs ~450 MB for JDK). These services only run the Spring Boot JAR; no runtime compilation required. |
| **Runtime** | `eclipse-temurin:25-jdk` | `codegen-service` | Full JDK required. `codegen-service` uses `javax.tools.JavaCompiler` at runtime to compile AI-generated test code into `.class` files before execution. A JRE cannot do this. |
| **Runtime** | `eclipse-temurin:25-jre` + Python 3 + `headroom-ai[proxy]` | `strategy-service`, `codegen-service` | These two services run the optional headroom proxy sidecar for LLM token compression. Python 3 and `headroom-ai[proxy]` are installed at image build time; `build-essential` and `python3-dev` are purged afterwards to keep the layer lean. |

### Production images (`docker-compose.prod.yml`)

CI builds each service and pushes to GitHub Container Registry. Tags default to `latest` but can be pinned per service.

| Image | Tag env var | Service |
|-------|------------|---------|
| `ghcr.io/${GHCR_ORG}/qa-isystem/pr-service` | `${PR_SERVICE_TAG:-latest}` | pr-service |
| `ghcr.io/${GHCR_ORG}/qa-isystem/impact-service` | `${IMPACT_SERVICE_TAG:-latest}` | impact-service |
| `ghcr.io/${GHCR_ORG}/qa-isystem/strategy-service` | `${STRATEGY_SERVICE_TAG:-latest}` | strategy-service |
| `ghcr.io/${GHCR_ORG}/qa-isystem/codegen-service` | `${CODEGEN_SERVICE_TAG:-latest}` | codegen-service |
| `ghcr.io/${GHCR_ORG}/qa-isystem/feedback-service` | `${FEEDBACK_SERVICE_TAG:-latest}` | feedback-service |

```yaml
# local dev — infrastructure containers only:
services:
  qa-zookeeper:        # Kafka's metadata coordinator
  qa-kafka:            # The message broker
  qa-redis:            # Redis — PromptResponseCache + RedisPrTracker + RedisConversationStore
  qa-kafka-ui:         # (optional) Kafka topic inspector at :8090
```

The five Java services run as separate JVM processes outside Docker during local development.
Start infrastructure first, then run services via the JARs.

### Redis key reference

| Key pattern | Owner | Description |
|-------------|-------|-------------|
| `qa:pr:{branchName}` | `RedisPrTracker` | Serialised `PrRecord` (BDD or TEST PR state) |
| `qa:prompt:{hash}` | `PromptResponseCache` | Cached AI prompt → Gherkin response (24 h TTL) |
| `qa:chat:{prId}:bdd` | `RedisConversationStore` | GZIP-compressed BDD generation conversation history (TTL: `aiqa.conversation.ttl-days`) |
| `qa:chat:{prId}:test` | `RedisConversationStore` | GZIP-compressed test code generation conversation history (TTL: `aiqa.conversation.ttl-days`) |

> **Stale ZooKeeper fix:** If Kafka crashes with `NodeExistsException`, run:
> ```bash
> docker compose down -v && docker compose up -d
> ```
> This clears stale ZooKeeper ephemeral node state from a previous run.

---

## 15. Testing the System Locally

### Quick start

```bash
# 1. Start infrastructure
docker compose up -d
docker compose ps   # wait for qa-kafka, qa-redis to show healthy

# 2. Build all JARs
./mvnw clean package -DskipTests

# 3. Start services (separate terminals or use start-local.sh)
java -jar pr-service/target/pr-service-0.0.1-SNAPSHOT.jar
java -jar impact-service/target/impact-service-0.0.1-SNAPSHOT.jar
java -jar strategy-service/target/strategy-service-0.0.1-SNAPSHOT.jar
java -jar codegen-service/target/codegen-service-0.0.1-SNAPSHOT.jar
java -jar feedback-service/target/feedback-service-0.0.1-SNAPSHOT.jar

# 4. Trigger the pipeline
curl -X POST http://localhost:8080/api/pr/demo

# 5. Submit the sample PR payload
curl -X POST http://localhost:8080/api/pr/submit \
  -H "Content-Type: application/json" \
  -d @pr-webhook-sample.json

# 6. Approve pending BDD scenarios → triggers codegen-service
./scripts/approve-bdd.sh --list
./scripts/approve-bdd.sh --yes
```

### Automated script

```bash
./scripts/start-local.sh                          # full start: infra + build + all 5 services + health checks
./scripts/start-local.sh --fresh                  # clean start (clears stale ZooKeeper state)
./scripts/start-local.sh --stop                   # stop all services
./scripts/start-local.sh --skip-build             # use existing JARs
./scripts/start-local.sh --with-kafka-ui          # include Kafka UI at http://localhost:8090
```

### GitHub PR creation (optional)

```bash
export TARGET_REPO_URL=https://github.com/your-org/your-test-repo
export TARGET_REPO_TOKEN=ghp_...
export TARGET_REPO_USERNAME=your_username
export GITHUB_WEBHOOK_SECRET=...
```

### Running tests

```bash
./mvnw test                                        # all modules
./mvnw test -pl pr-service -am                    # pr-service only
./mvnw test -pl strategy-service,codegen-service -am
```

Current test counts (106 total, 0 failures, zero Mockito):

| Module | Tests |
|--------|-------|
| pr-service | 37 (PRService, ContextCompression, PRController, PRControllerAdvice, ProductsDeserializer) |
| impact-service | 36 (GitDiffParser, RiskScorer, IntegrationTestScopeClassifier, TestCoverage) |
| strategy-service | 26 (StrategyAgent, ApiTestRunner, RepoContext) |
| codegen-service | 7 (ApiTestRunner) |

---

## 16. Summary of Heuristics and Why They Were Chosen

| Heuristic | Where | Why this approach |
|-----------|-------|-------------------|
| Jira ID regex `\b[A-Z]{2,10}-\d+\b` | `PrContextExtractor` | Standard Jira key format; works for any project key |
| Confluence URL pattern `/wiki/` | `PrContextExtractor` | All Confluence Cloud/Server instances use `/wiki/` in page URLs |
| Product inference from labels | `PrContextExtractor` | GitHub labels like "payments" reliably indicate product areas |
| AI compression at ingestion | `ContextCompressionService` | Compressing once at the boundary means all downstream AI prompts receive lean, token-efficient context |
| PrContext forwarded as-is | impact → strategy → codegen → feedback | Immutable propagation prevents drift; keeps each service stateless |
| Compressed `summary` prepended in AI prompt | `PrContext.asPromptSection()` | LLM receives focused context first, then structured detail |
| PrContext in generated Javadoc | `ApiTestRunner`, `UITestRunner`, `MobileTestRunner` | Embeds traceability directly in the file |
| PascalCase regex for class names | `RepoContextService.extractClassReferences` | Java class names are always PascalCase. Faster than full AST parsing |
| Inverted index (component → test files) | `RepoContextService.buildCoverageIndex` | O(1) lookup per component at query time |
| AiCallGate rules before any LLM call | `strategy-service` | Eliminates 40–60% of AI calls with zero cost |
| PromptResponseCache (Redis, 24h TTL) | `strategy-service` | Identical change patterns produce identical BDD scenarios |
| Coverage level PARTIAL when covered ≥ uncovered | `E2ECoverageAnalyzer` | If more than half is covered, updating is cheaper than creating from scratch |
| Stabilisation: attempt 3 simplifies to smoke test | `StabilizationLoop` | A smoke test that checks reachability is better than abandoning silently with no PR |
| Feedback classification: KNOWLEDGE_GAP vs STYLE_ONLY | `feedback-service` | Knowledge gaps update the product expert file; style issues update the prompt only |
| AI score adjustment capped ±0.15 | `AIImpactEvaluator` | Prevents the LLM from completely overriding well-reasoned deterministic scoring |
| Low temperature (0.1–0.2) for AI prompts | All AI callers | Near-zero temperature makes output consistent — critical for a pipeline that must behave reliably |
| Virtual threads for feedback processing | `feedback-service` | Long-running cycle; virtual threads let the HTTP response return immediately |

---

## 17. Class Reference Table

### common module

| Class | Package | Role |
|-------|---------|------|
| `PullRequest` | model | Input: PR details including jiraIds, jiraLinks, confluenceLinks, labels, products, contextSummary |
| `PrContext` | model | Extracted external context: Jira, Confluence, labels, products, summary — flows through pipeline |
| `GitDiff` | model | One changed file with line-level detail |
| `DiffHunk` | model | One changed region within a file |
| `DiffLine` | model | One line: ADDED, REMOVED, or CONTEXT |
| `ImpactEnvelope` | model | Output of impact-service; includes prContext + prTitle |
| `ImpactEnvelope.ImpactedComponent` | model (nested) | One changed class with type, score, callers |
| `ImpactEnvelope.ChangeType` | model (enum) | API_CHANGE, BUG_FIX, SECURITY_FIX, NEW_FEATURE, … |
| `ImpactEnvelope.RiskLevel` | model (enum) | LOW, MEDIUM, HIGH, CRITICAL |
| `ImpactEnvelope.AIInsight` | model (nested) | AI refinement record: model, scores, added types, reasoning |
| `CoverageReport` | model | Two-phase coverage assessment |
| `CoverageReport.CoverageLevel` | model (enum) | GOOD, PARTIAL, NONE, UNKNOWN |
| `CoverageReport.CoverageSource` | model (enum) | REPO_SCAN, UNKNOWN |
| `TestStrategy` | model | Decision + requirements from StrategyAgent |
| `BddScenario` | model | Gherkin scenarios + prContext + prTitle for codegen-service |
| `TestScript` | model | Generated test code + metadata |
| `TestResult` | model | Execution result from StabilizationLoop |
| `FeedbackEvent` | model | Rejected PR details; wraps BddScenario or TestScript for feedback-service |
| `AiClient` | agent (interface) | Abstraction over LLM API; `complete()`, `isAvailable()`, default `completeWithHistory()` |
| `CopilotCliClient` | agent | `gh api` subprocess — overrides `completeWithHistory()` for multi-turn GitHub Models API calls |
| `AiClientConfig` | config | Creates `CopilotCliClient` bean; gated on `aiqa.github.enabled=true` |
| `AiProviderProperties` | config | `@ConfigurationProperties(prefix="aiqa.ai")` |
| `ChatMessage` | model | Java 25 record `(String role, String content)`; factory methods `system()`, `user()`, `assistant()` |
| `ConversationHistory` | model | Java 25 record `(String prId, List<ChatMessage> turns, int totalTurns, Instant lastUpdated)` |
| `ConversationStore` | service (interface) | `save()`, `load()`, `remove()` — conversation history persistence abstraction |
| `RedisConversationStore` | service | `@ConditionalOnProperty(spring.data.redis.host)`; GZIP+Base64 compressed; key `qa:chat:{id}`; configurable TTL; 1 MB OOM guard |
| `InMemoryConversationStore` | service | `@ConditionalOnMissingBean` fallback; non-persistent |
| `GitHubService` | service | GitHub API: diff fetch, PR creation, webhook signature verification |
| `RepoContextService` | service | Clone + index test repo; build coverage index; extract conventions |
| `RedisPrTracker` | service | Redis-backed PR state tracker (`@ConditionalOnProperty`) |
| `InMemoryPrTracker` | service | Fallback PR state tracker when Redis is unavailable |
| `GitDiffParser` | parser | Raw unified diff string → `List<GitDiff>` |
| `KafkaConfig` | config | `ConcurrentKafkaListenerContainerFactory` with `MANUAL_IMMEDIATE` ack |

### pr-service

| Class | Package | Role |
|-------|---------|------|
| `PrServiceApplication` | pr | Spring Boot entry point |
| `PRController` | controller | HTTP: /webhook, /submit, /demo, /health |
| `PRService` | service | Enrich → extract context → compress → validate → publish |
| `PrContextExtractor` | service | Regex mining of Jira IDs, Jira URLs, Confluence URLs, labels, products |
| `ContextCompressionService` | service | AI compression via Copilot CLI; sets `contextSummary`; pass-through on failure |
| `CompressionConfig` | config | Creates `ContextCompressionService` with its own `CopilotCliClient` |
| `CompressionProperties` | config | `@ConfigurationProperties(prefix="aiqa.ai.compression")` |
| `FeatureUpdatesProducer` | kafka | Serialise + send to FeatureUpdatesQueue |
| `CommaSeparatedListDeserializer` | model | Jackson: `products` accepts JSON array or comma-separated string |

### impact-service

| Class | Package | Role |
|-------|---------|------|
| `ImpactServiceApplication` | impact | Spring Boot entry point |
| `KafkaConfig` | config | Explicit consumer/producer/factory beans with MANUAL_IMMEDIATE ack |
| `AIImpactProperties` | config | Typed binding for `aiqa.ai` YAML block |
| `AIImpactEvaluator` | ai | LLM last-resort gray-zone refinement (fail-safe) |
| `FeatureUpdatesConsumer` | kafka | Consume PullRequest → trigger ImpactEngine |
| `ImpactResultsProducer` | kafka | Publish ImpactEnvelope to ImpactResultsQueue |
| `ImpactEngine` | engine | Orchestrate 5-step pipeline + optional AI step |
| `DependencyGraph` | engine | Import graph + component typing |
| `ChangeTypeDetector` | engine | Regex-based change classification |
| `RiskScorer` | engine | Weighted score → RiskLevel |
| `IntegrationTestScopeClassifier` | service | Phase 1 coverage: identify components by type → UNKNOWN |
| `ImpactController` | controller | REST: /analyze (sync), /status |

### strategy-service

| Class | Package | Role |
|-------|---------|------|
| `StrategyServiceApplication` | strategy | Spring Boot entry point |
| `KafkaConfig` | config | Explicit consumer/producer/factory beans |
| `TargetRepoProperties` | config | Typed binding for `aiqa.target-repo` YAML |
| `ImpactResultsConsumer` | kafka | Consume ImpactEnvelope → trigger StrategyAgent |
| `TestScriptsProducer` | kafka | Publish BddScenario to TestScriptsQueue |
| `FeedbackProducer` | kafka | Publish FeedbackEvent to FeedbackQueue |
| `AiCallGate` | agent | Rule-based gate: SKIP / RULE_HANDLED / NEEDS_AI |
| `StrategyAgent` | agent | Decision tree + fallback rules |
| `BddGenerator` | agent | Template/AI Gherkin builder; injects PrContext.asPromptSection() into AI prompt |
| `PromptResponseCache` | service | Redis cache (24h TTL) for AI prompt responses |
| `AiCostMonitor` | monitor | Micrometer metrics: gate skips, cache hits, AI call count |
| `E2ECoverageAnalyzer` | service | Phase 2 coverage: scan repo index → GOOD/PARTIAL/NONE |
| `CoveragePlanner` | service | Phase 3: ChangeType → required ScenarioClass set → capability × class matrix (COVERED/PLANNED); folds recurring `RejectionLedger` classes; recall = covered/required |
| `TestPrService` | service | GitHub PR creation for BDD scenarios |
| `StrategyController` | controller | REST: /status, /pending-bdd, /approve-bdd, /github-webhook, /refresh-context, /cost/report |

### codegen-service

| Class | Package | Role |
|-------|---------|------|
| `CodegenServiceApplication` | codegen | Spring Boot entry point |
| `KafkaConfig` | config | Explicit consumer/factory beans with MANUAL_IMMEDIATE ack |
| `TestScriptsConsumer` | kafka | Consume BddScenario → trigger CodegenService |
| `TestResultsProducer` | kafka | Publish TestResult to TestResultsQueue |
| `CodegenService` | service | Route BddScenario to correct runner; orchestrate stabilisation |
| `ApiTestRunner` | execution | Generate RestAssured + JUnit 5 test code; embeds PrContext in Javadoc |
| `UITestRunner` | execution | Generate Selenium test code; embeds PrContext in Javadoc |
| `MobileTestRunner` | execution | Generate Appium test code; embeds PrContext in Javadoc |
| `TestExecutionEngine` | execution | Execute the generated test (compile + run) |
| `StabilizationLoop` | execution | Bounded run → fail → fix cycle (max 3 retries) |
| `RepoContext` | execution | Value object: conventions extracted from test repo |
| `TestPrService` | service | GitHub PR creation for generated test code |

### feedback-service

| Class | Package | Role |
|-------|---------|------|
| `FeedbackServiceApplication` | feedback | Spring Boot entry point |
| `KafkaConfig` | config | Explicit consumer/factory beans with MANUAL_IMMEDIATE ack |
| `FeedbackConsumer` | kafka | Consume FeedbackEvent → trigger PrFeedbackService |
| `PrFeedbackService` | service | Orchestrate full feedback loop: fetch comments → classify → update knowledge → re-generate; records rejected scenario classes in `RejectionLedger` |
| `FeedbackClassifier` | service | AI classification: KNOWLEDGE_GAP vs STYLE_ONLY |
| `ProductExpertUpdater` | service | Append new knowledge to `productExpert/{product}/PRODUCT.md` in target repo |
| `RejectionLedger` *(common)* | service | Cross-PR rejection memory (Redis/NoOp); recurring classes feed `CoveragePlanner` |
| `FeedbackController` | controller | REST: /status |

