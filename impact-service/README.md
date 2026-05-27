# impact-service

**Port:** `8081`  
**Phase:** 1 — Impact Analysis (deterministic + optional AI refinement)  
**Package root:** `nz.co.eroad.qaisystem`  
**Role:** Consumes PR events from Kafka, runs a fully rule-based impact pipeline, then optionally
calls an LLM to refine the risk score when the deterministic result is ambiguous. Publishes an
`ImpactEnvelope` for the strategy layer.

> **Deterministic by default.** The same diff always produces the same envelope.
> AI is **opt-in** and **last-resort only** — it runs solely when the deterministic risk score
> falls in the configured gray zone (`0.30–0.75` by default) where the rule-based system
> has the least signal. Outside that range, the deterministic result is used as-is.

---

## Table of Contents

1. [Package Structure](#package-structure)
2. [End-to-End Data Flow](#end-to-end-data-flow)
3. [Class-by-Class Breakdown](#class-by-class-breakdown)
4. [How TestCoverageService Works](#how-testcoverageservice-works)
5. [AI Last-Resort Evaluator](#ai-last-resort-evaluator)
6. [Kafka Flow](#kafka-flow)
7. [API Endpoints](#api-endpoints)
8. [Configuration](#configuration)

---

## Package Structure

```
nz/co/eroad/qaisystem/
├── impact/
│   └── ImpactServiceApplication.java   ← Spring Boot entry point
├── ai/
│   └── AIImpactEvaluator.java          ← LLM last-resort refinement (opt-in, gray-zone only)
├── config/
│   ├── KafkaConfig.java                ← Explicit consumer/producer/factory beans
│   └── AIImpactProperties.java         ← @ConfigurationProperties for aiqa.ai
├── kafka/
│   ├── FeatureUpdatesConsumer.java     ← Consumes PullRequest from Kafka
│   └── ImpactResultsProducer.java      ← Publishes ImpactEnvelope to Kafka
├── engine/
│   ├── ImpactEngine.java               ← Orchestrates the full pipeline (steps 1–5 + AI)
│   ├── GitDiffParser.java              ← Raw unified diff → List<GitDiff>
│   ├── DependencyGraph.java            ← Import-graph analysis, component typing
│   ├── ChangeTypeDetector.java         ← Regex-based change classification
│   └── RiskScorer.java                 ← Weighted risk score (0.0–1.0) → RiskLevel
├── service/
│   └── TestCoverageService.java        ← Identifies components needing integration/E2E tests by type; level=UNKNOWN
└── controller/
    └── ImpactController.java           ← Synchronous REST endpoints for debugging
```

---

## End-to-End Data Flow

```
  Kafka: FeatureUpdatesQueue
          │
          │  PullRequest JSON
          ▼
  ┌───────────────────────────────────────────────────────────┐
  │ FeatureUpdatesConsumer                                    │
  │  deserialise → hand PullRequest to ImpactEngine           │
  └───────────────┬───────────────────────────────────────────┘
                  │
                  ▼
  ┌───────────────────────────────────────────────────────────┐
  │ ImpactEngine.analyze(PullRequest)                         │
  │                                                           │
  │  Step 1 ─ GitDiffParser                                   │
  │    rawDiffContent (string)                                │
  │        └─► List<GitDiff>  (files, hunks, added/removed)   │
  │                                                           │
  │  Step 2 ─ DependencyGraph                                 │
  │    List<GitDiff>                                          │
  │        └─► List<ImpactedComponent>  (type, callers,       │
  │                                      callees, score)      │
  │            Map<className, List<imports>>                  │
  │                                                           │
  │  Step 3 ─ ChangeTypeDetector                              │
  │    List<GitDiff>                                          │
  │        └─► List<ChangeType>  (API_CHANGE, BUG_FIX, ...)   │
  │                                                           │
   │  Step 4 ─ RiskScorer                                      │
   │    diffs + changeTypes + components                       │
   │        └─► double riskScore (0.0–1.0)                     │
   │            RiskLevel  (LOW / MEDIUM / HIGH / CRITICAL)    │
   │                                                           │
   │  Step 4b ─ AIImpactEvaluator (last resort, optional)      │
   │    Only runs if enabled=true AND score in gray zone       │
   │    Calls LLM → may adjust riskScore (±0.15 max)           │
   │             → may add missed ChangeTypes                  │
   │    Falls back silently on any error                       │
   │    Records AIInsight in the envelope                      │
   │                                                           │
   │  Step 5 ─ TestCoverageService                             │
   │    components + diffs                                     │
   │        └─► CoverageReport  (level=UNKNOWN, source=UNKNOWN │
   │                             untestedComponents,            │
   │                             requiredTestTypes)             │
   │    ⚠ Does NOT check the test repo — level is intentionally│
   │      UNKNOWN until strategy-service performs the scan.    │
  │                                                           │
  │  Assemble ImpactEnvelope ──────────────────────────────►  │
  └───────────────────────────────────────────────────────────┘
                  │
                  │  ImpactEnvelope JSON (keyed by prId)
                  ▼
  ┌───────────────────────────────────────────────────────────┐
  │ ImpactResultsProducer                                     │
  │  serialise → kafkaTemplate.send(ImpactResultsQueue)       │
  └───────────────────────────────────────────────────────────┘
                  │
                  ▼
  Kafka: ImpactResultsQueue   ←── strategy-service consumes
```

---

## Class-by-Class Breakdown

### ImpactServiceApplication
Standard `@SpringBootApplication` entry point. No custom logic — Spring component
scan picks up all beans in the `nz.co.eroad.qaisystem` package tree.

---

### KafkaConfig
**`config/KafkaConfig.java`**

Declares four explicit beans. This class exists because Spring Boot auto-config
does **not** create `kafkaListenerContainerFactory` when manual acknowledgement is
required — it must be owned explicitly.

| Bean | Purpose |
|------|---------|
| `consumerFactory` | Kafka consumer with `StringDeserializer`, `enable.auto.commit=false` |
| `kafkaListenerContainerFactory` | **Named exactly** so `@KafkaListener` resolves it. `AckMode=MANUAL_IMMEDIATE`, concurrency=3 |
| `producerFactory` | Kafka producer with `acks=all`, retries=3 |
| `kafkaTemplate` | Used by `ImpactResultsProducer.publish()` |

---

### FeatureUpdatesConsumer
**`kafka/FeatureUpdatesConsumer.java`**

Listens on `FeatureUpdatesQueue` with three parallel consumer threads.

Per message:
1. Deserialise JSON → `PullRequest`
2. `impactEngine.analyze(pr)` → `ImpactEnvelope`
3. `impactResultsProducer.publish(envelope)`
4. `ack.acknowledge()` — commits the Kafka offset

On exception: logs the error and **still acknowledges** to avoid a poison-pill loop.
A DLQ is recommended for production.

---

### ImpactResultsProducer
**`kafka/ImpactResultsProducer.java`**

Serialises `ImpactEnvelope` to JSON and sends to `ImpactResultsQueue`.
Uses `prId` as the Kafka message key so all events for the same PR land on the
same partition in order. Logs partition + offset on success via `CompletableFuture.whenComplete()`.

---

### ImpactEngine
**`engine/ImpactEngine.java`**

The **orchestrator** — owns no analysis logic itself, only wires the pipeline and
assembles the final `ImpactEnvelope`. Also computes inline metrics:

- `serviceConfidence` — per-component HIGH/MEDIUM/LOW string from `impactScore`
- `totalLinesAdded` / `totalLinesDeleted`
- `directDependencies` / `transitiveDependencies` — flattened from dep graph
  (transitive = deps-of-deps that are also in the graph)
- `changesSummary` — one-line human-readable log string

---

### GitDiffParser
**`engine/GitDiffParser.java`**

Parses a raw `git diff` unified-format string into `List<GitDiff>`. Driven by two
compiled `Pattern`s:

| Pattern | Triggers | Action |
|---------|---------|--------|
| `^diff --git a/(.*) b/(.*)$` | File header | Flush previous diff, start new `GitDiff` |
| `^@@ -(n),(m) +(p),(q) @@` | Hunk header | Start new `DiffHunk`, set line counters |

**Line types per hunk:**

| Prefix | `LineType` | Counter behaviour |
|--------|-----------|------------------|
| `+` (not `+++`) | `ADDED` | `linesAdded++`, `lineCounter++` |
| `-` (not `---`) | `REMOVED` | `linesDeleted++` (lineCounter unchanged) |
| space or other | `CONTEXT` | `lineCounter++` only |

**DiffType markers (before hunk section):**

| Line starts with | `DiffType` |
|-----------------|-----------|
| `new file mode` | `ADDED` |
| `deleted file mode` | `DELETED` |
| `rename` | `RENAMED` |
| *(default)* | `MODIFIED` |

**Test file detection:** path contains any of `Test`, `Spec`, `IT`, `test/`, `spec/`

---

### DependencyGraph
**`engine/DependencyGraph.java`**

Builds a lightweight in-memory graph of class-level dependencies from Java `import`
statements present in the diff's changed lines.

**`buildAndAnalyse(diffs)` → `List<ImpactedComponent>`:**
1. Regex `^import\s+([\w.]+);$` extracts imports from every added/removed line
2. Builds `className → [importedClasses]` map
3. **Callers** = other changed classes that import this one (blast-radius measure)
4. `impactScore = min(1.0, churnBase + callerBonus)` where
   `churnBase = min(1.0, lines/200)` and `callerBonus = min(0.3, callers×0.1)`
5. **ComponentType** detected from file path keyword:

| Path keyword | ComponentType |
|-------------|---------------|
| `controller` | CONTROLLER |
| `service` | SERVICE |
| `repositor` / `dao` | REPOSITORY |
| `model` / `entity` / `dto` | MODEL |
| `config` | CONFIG |
| `test` / `spec` | TEST |
| `util` / `helper` | UTILITY |
| *(default)* | SERVICE |

> **Scope note:** graph only covers classes *in the PR diff*. Classes outside the
> diff that call changed code are invisible.

---

### ChangeTypeDetector
**`engine/ChangeTypeDetector.java`**

Classifies each diff file into one or more `ChangeType` values using five ordered
rules. Results are de-duplicated by a `LinkedHashSet`. Default if nothing matches:
`NEW_FEATURE`.

| # | Condition | ChangeType |
|---|-----------|-----------|
| 1 | `DiffType == ADDED` and not a test | `NEW_FEATURE` |
| 2 | `DiffType == DELETED` | `REFACTORING` |
| 3 | File path matches config/build pattern | `CONFIGURATION_CHANGE` / `DEPENDENCY_UPDATE` / `DATABASE_CHANGE` |
| 4 | Changed content matches keyword pattern | See below |
| 5 | `linesDeleted > 50` | `BREAKING_CHANGE` |

**Content keyword → ChangeType:**

| Keywords (case-insensitive) | ChangeType |
|-----------------------------|-----------|
| `TODO`, `FIXME`, `bug`, `fix`, `patch`, `issue`, `defect` | `BUG_FIX` |
| `@Deprecated`, `rename`, `extract`, `move`, `refactor` | `REFACTORING` |
| `security`, `auth`, `crypt`, `token`, `password`, `secret` | `SECURITY_FIX` |
| `performance`, `cache`, `index`, `optimiz`, `async`, `parallel` | `PERFORMANCE_IMPROVEMENT` |
| `@RestController`, `@RequestMapping`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping` | `API_CHANGE` |
| `migration`, `schema`, `ALTER TABLE`, `CREATE TABLE`, `DROP TABLE`, `liquibase`, `flyway` | `DATABASE_CHANGE` |

---

### RiskScorer
**`engine/RiskScorer.java`**

Produces a normalised `0.0–1.0` score from four weighted factors:

```
score = (0.25 × churnScore)
      + (0.30 × changeTypeScore)
      + (0.25 × componentScore)
      + (0.20 × coverageScore)
```

| Factor | Weight | Calculation |
|--------|--------|------------|
| Churn | 25% | `min(1.0, totalLines / 300)` — 300 lines → max |
| Change type severity | 30% | Average across all types: BREAKING=1.0, SECURITY=0.9, DB=0.8, API=0.7, DEPENDENCY=0.6, NEW_FEATURE/BUG_FIX=0.5, PERFORMANCE=0.4, CONFIG/REFACTORING=0.3 |
| Component criticality | 25% | Type-weighted avg: CONTROLLER=0.8, REPO=0.7, SERVICE=0.6, CONFIG=0.5, MODEL=0.4, TEST=0.1, default=0.3 + caller bonus `min(0.2, avgCallers×0.05)` |
| Coverage gap | 20% | `min(0.9, integrationTestableComponents / nonTestComponents × 0.9)` — more CONTROLLER/SERVICE/REPOSITORY components = more integration-coverage uncertainty = higher risk. Capped at 0.9 because actual test existence is unknown until E2ECoverageAnalyzer scans the repo. |

**Risk levels (configurable thresholds):**

| Score | Level |
|-------|-------|
| ≥ 0.9 | `CRITICAL` |
| ≥ 0.7 (`risk-threshold-high`) | `HIGH` |
| ≥ 0.4 (`risk-threshold-medium`) | `MEDIUM` |
| < 0.4 | `LOW` |

---

### ImpactController
**`controller/ImpactController.java`**

Two synchronous REST endpoints that bypass Kafka — useful for CI pipelines, manual
debugging, and the `/api/impact/analyze` call from the root README:

| Method | Path | Details |
|--------|------|---------|
| `GET` | `/api/impact/status` | Returns `{"service":"impact-service","status":"OPERATIONAL","timestamp":"..."}` |
| `POST` | `/api/impact/analyze` | Runs parse→detect→score→coverage pipeline on a raw diff; returns summary JSON. Does **not** publish to Kafka. |

---

## How TestCoverageService Works

### Short answer: it identifies *which components need integration tests* — not whether they have them

`TestCoverageService` **does not connect to any repo, call GitHub, or read the filesystem**.
Its sole job is to look at the impacted components from the PR diff and answer:
*"Which of these components are the kind of thing that needs integration or E2E tests?"*

The **actual test coverage check** (does a test already exist in the test repo?) is done
downstream in `strategy-service` by `E2ECoverageAnalyzer`, which has access to the cloned
test repository.

### Two-phase coverage design

```
Phase 1 (impact-service / TestCoverageService):
  ─ No test repo access
  ─ Filter components by type (CONTROLLER, SERVICE, REPOSITORY, CONFIG)
  ─ Set level = UNKNOWN, source = UNKNOWN
  ─ Populate untestedComponents and requiredTestTypes
  ─ Publish ImpactEnvelope with this UNKNOWN report to Kafka

Phase 2 (strategy-service / E2ECoverageAnalyzer):
  ─ Has cloned test repo (via RepoContextService)
  ─ Scans test repo for integration/E2E tests referencing each component name
  ─ Replaces UNKNOWN with real level: GOOD / PARTIAL / NONE
  ─ Populates testedComponents and existingTestFiles
  ─ StrategyAgent then makes its CREATE/UPDATE/SKIP decision with real data
```

### Algorithm

```
assess(components, diffs)
  │
  ├─ needsIntegrationTest = components filtered by INTEGRATION_TEST_REQUIRED types:
  │     CONTROLLER  → needs API and/or E2E tests
  │     SERVICE     → needs INTEGRATION tests
  │     REPOSITORY  → needs INTEGRATION tests (real DB)
  │     CONFIG      → needs SMOKE tests
  │  (MODEL and UTILITY are excluded — covered by unit tests in the PR)
  │
  ├─ requiredTestTypes = resolveRequiredTestTypes(components, diffs):
  │     hasControllers        → add "API"
  │     hasServices/Repos     → add "INTEGRATION"
  │     diff contains DB keywords (flyway, migration, ALTER TABLE, ...)
  │                           → add "INTEGRATION"
  │     diff contains security keywords (auth, oauth, jwt, token, ...)
  │                           → add "E2E"
  │     (empty result)        → default "API"
  │
  └─ return CoverageReport:
       source            = UNKNOWN
       level             = UNKNOWN  ← real assessment deferred to E2ECoverageAnalyzer
       coverageRatio     = 0.0
       testedComponents  = []        ← unknown until repo scan
       untestedComponents = needsIntegrationTest
       requiredTestTypes  = requiredTestTypes
       existingTestFiles  = []       ← unknown until repo scan
       requiresNewTests   = !needsIntegrationTest.isEmpty()
```

### Concrete example

PR diff contains:
```
src/PaymentController.java    (ADDED — CONTROLLER type)
src/PaymentService.java       (ADDED — SERVICE type)
src/JwtToken.java             (ADDED — MODEL type)
```

| Component | Type | Needs integration test? | In untestedComponents? |
|-----------|------|------------------------|------------------------|
| `PaymentController` | CONTROLLER | ✅ Yes (API/E2E) | **Yes** |
| `PaymentService` | SERVICE | ✅ Yes (INTEGRATION) | **Yes** |
| `JwtToken` | MODEL | ❌ No (unit test suffices) | No |

Phase 1 output from TestCoverageService:
```json
{
  "source": "UNKNOWN",
  "level": "UNKNOWN",
  "coverageRatio": 0.0,
  "untestedComponents": ["PaymentController", "PaymentService"],
  "requiredTestTypes": ["API", "INTEGRATION", "E2E"],
  "existingTestFiles": [],
  "requiresNewTests": true
}
```

Phase 2 output after E2ECoverageAnalyzer scans the test repo:
```json
{
  "source": "REPO_SCAN",
  "level": "PARTIAL",
  "coverageRatio": 0.5,
  "testedComponents": ["PaymentService"],
  "untestedComponents": ["PaymentController"],
  "requiredTestTypes": ["API", "INTEGRATION", "E2E"],
  "existingTestFiles": ["PaymentServiceIT.java"],
  "requiresNewTests": true
}
```

### How strategy-service uses this

`StrategyAgent` calls `E2ECoverageAnalyzer.analyze(envelope)` first to get the real level:

```
coverage.level == NONE AND untestedComponents not empty   → force CREATE_TESTS
coverage.level == PARTIAL                                 → UPDATE_TESTS
coverage.level == GOOD                                    → follow other rules
coverage.level == UNKNOWN (no repo configured)            → follow other rules (conservative)
```

### Why not just check the test repo directly in impact-service?

**Separation of concerns + topology.** impact-service is a fast, stateless processing stage
that runs immediately on every PR event. Cloning and scanning a test repository would:
1. Make it stateful (cached clone)
2. Add significant startup and runtime latency
3. Couple impact detection to the deployment configuration of the test repo

`strategy-service` already owns that concern — it manages the clone lifecycle via
`RepoContextService` at startup and keeps the clone fresh with `POST /api/strategy/refresh-context`.

---

## AI Last-Resort Evaluator

### Why AI as last resort?

The deterministic pipeline (regex, file paths, import graphs, fixed weights) is excellent at
**structural signals** but blind to **semantic context**. Examples of where it falls short:

| Scenario | Deterministic sees | Reality |
|----------|-------------------|---------|
| 8 lines added to `PaymentController.chargeCustomer()` | LOW churn, MEDIUM risk | Touches payment critical path → HIGH risk |
| 8 lines added to `PaymentController.getReceiptHtml()` | Same score as above | Cosmetic change → LOW risk |
| Rename `processOrder` → `fulfillOrder` with 60+ deletes | `BREAKING_CHANGE` (deletion threshold) | Actually a safe internal refactoring |
| New JWT claim validation added inline | `SECURITY_FIX` (keyword) | Could also be `API_CHANGE` — AI can disambiguate |

The semantic questions ("is this a risky change?") are exactly what LLMs excel at. But
calling an LLM on every PR would be slow, expensive, and unnecessary for clear-cut cases.

### The gray zone principle

```
Risk score:  0.0 ─────── 0.30 ─────────────────── 0.75 ─────── 1.0
                  Confident LOW    Gray zone         Confident HIGH
                  (skip AI)    (AI refines here)    (skip AI)
```

- Below `0.30`: deterministic confidently says LOW risk. AI would add cost with no benefit.
- Above `0.75`: deterministic confidently says HIGH/CRITICAL. AI cannot make it worse and is not needed.
- Between `0.30` and `0.75`: the system has real uncertainty. This is where the LLM earns its place.

Both bounds are configurable in `application.yaml`.

### Fail-safe design

Every possible failure mode is handled gracefully:

| Failure | Behaviour |
|---------|-----------|
| AI disabled (`enabled: false`) | Skip entirely, no log noise beyond DEBUG |
| API key not set | Skip entirely |
| Score outside gray zone | Skip, log at DEBUG |
| Network timeout | Log WARN, use deterministic result |
| HTTP non-200 from API | Log WARN, use deterministic result |
| Malformed JSON in response | Log WARN, use deterministic result |
| Unknown ChangeType in response | Skip that type, keep valid ones |
| Score adjustment > ±0.15 | Clamped to ±0.15 |

The pipeline **never fails** because of AI. The worst outcome is falling back to the
deterministic result as if AI was disabled.

### Classes

#### `AIImpactProperties`
**`config/AIImpactProperties.java`** — `@ConfigurationProperties(prefix = "aiqa.ai")`

| Property | Default | Meaning |
|----------|---------|---------|
| `enabled` | `false` | Master switch — must be `true` to activate |
| `api-key` | `""` | Injected from `${AIQA_AI_API_KEY}` env var |
| `model` | `gpt-4o-mini` | Any OpenAI chat-completions model |
| `base-url` | `https://api.openai.com/v1` | Override for Azure OpenAI, Ollama, etc. |
| `confidence-lower-bound` | `0.30` | Min score that triggers AI |
| `confidence-upper-bound` | `0.75` | Max score that triggers AI |
| `max-diff-chars` | `3000` | Truncate large diffs before sending |
| `timeout-seconds` | `15` | HTTP timeout — fail-fast |
| `max-score-adjustment` | `0.15` | Maximum score change the AI can make |

#### `AIImpactEvaluator`
**`ai/AIImpactEvaluator.java`**

```
evaluate(pr, diffs, changeTypes, components, riskScore):
  if not configured → return empty
  if score outside gray zone → return empty
  build prompt (PR summary + truncated diff + deterministic findings)
  POST to /v1/chat/completions (OpenAI-compatible)
  parse JSON response:
    adjustedRiskScore → clamp to [original ± maxScoreAdjustment]
    additionalChangeTypes → filter unknowns, deduplicate
    reasoning → 1-2 sentences
  return AIInsight
  (any exception → log WARN, return empty)
```

**Prompt design:** Low temperature (`0.1`) for consistent output. `response_format: json_object`
to guarantee parseable JSON. Instructs the LLM to prefer minimal changes when the deterministic
result looks correct, and explicitly states the maximum score adjustment allowed.

### What gets added to the ImpactEnvelope

The `ImpactEnvelope.aiInsight` field is populated whenever the evaluator ran (even if no
changes were made). When AI is disabled or skipped, `aiInsight` is `null`.

```json
{
  "aiInsight": {
    "applied": true,
    "model": "gpt-4o-mini",
    "originalRiskScore": 0.52,
    "adjustedRiskScore": 0.67,
    "addedChangeTypes": ["SECURITY_FIX"],
    "reasoning": "The JWT claim validation logic directly affects auth enforcement — warrants HIGH risk scrutiny."
  }
}
```

### Enabling AI

```bash
# Set the API key in your environment
export AIQA_AI_API_KEY=sk-proj-...

# In application.yaml
aiqa:
  ai:
    enabled: true
    model: gpt-4o-mini          # or gpt-4o for higher accuracy
```

For Azure OpenAI:
```yaml
aiqa:
  ai:
    enabled: true
    base-url: https://{your-resource}.openai.azure.com/openai/deployments/{deployment}
    api-key: ${AIQA_AI_API_KEY:}
    model: gpt-4o
```

For local Ollama (free, no API key):
```yaml
aiqa:
  ai:
    enabled: true
    base-url: http://localhost:11434/v1
    api-key: ollama              # Ollama ignores the key but the header is required
    model: llama3.1:8b           # or mistral, qwen2.5-coder, etc.
```

---

## Kafka Flow

| Direction | Topic | Payload |
|-----------|-------|---------|
| **Consumes** | `FeatureUpdatesQueue` | `PullRequest` JSON |
| **Produces** | `ImpactResultsQueue` | `ImpactEnvelope` JSON (key = `prId`) |

Consumer group: `impact-service-group`

---

## API Endpoints

### `GET /api/impact/status`
```json
{ "service": "impact-service", "status": "OPERATIONAL", "timestamp": "2026-05-27T10:00:00" }
```

### `POST /api/impact/analyze`
**Request:**
```json
{ "diff": "diff --git a/src/AuthController.java b/src/AuthController.java\n..." }
```

**Response (AI disabled or score outside gray zone):**
```json
{
  "filesFound": 2,
  "changeTypes": ["API_CHANGE", "NEW_FEATURE"],
  "riskScore": "0.72",
  "riskLevel": "HIGH",
  "coverage": {
    "source": "UNKNOWN",
    "level": "UNKNOWN",
    "untestedComponents": ["AuthController", "UserService"],
    "requiredTestTypes": ["API", "E2E"],
    "requiresNewTests": true
  },
  "aiInsight": {
    "applied": false,
    "reason": "AI evaluation disabled (set aiqa.ai.enabled=true)"
  }
}
```

**Response (AI enabled, score in gray zone, refinement applied):**
```json
{
  "filesFound": 2,
  "changeTypes": ["API_CHANGE", "NEW_FEATURE", "SECURITY_FIX"],
  "riskScore": "0.78",
  "riskLevel": "HIGH",
  "coverage": { "..." : "..." },
  "aiInsight": {
    "applied": true,
    "model": "gpt-4o-mini",
    "originalRiskScore": "0.63",
    "adjustedRiskScore": "0.78",
    "additionalChangeTypes": ["SECURITY_FIX"],
    "reasoning": "The JWT token handling changes introduce new auth logic that should be treated as HIGH risk."
  }
}
```

> **Note:** `level=UNKNOWN` is correct here. The real level (GOOD/PARTIAL/NONE) is set
> by `E2ECoverageAnalyzer` in strategy-service after scanning the test repo.

---

## Configuration (`application.yaml`)

```yaml
server:
  port: 8081

spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: impact-service-group
      auto-offset-reset: earliest
      enable-auto-commit: false

kafka:
  topics:
    feature-updates: FeatureUpdatesQueue
    impact-results: ImpactResultsQueue

aiqa:
  strategy:
    risk-threshold-high: 0.7     # score >= this → HIGH
    risk-threshold-medium: 0.4   # score >= this → MEDIUM

  # AI last-resort evaluator (opt-in)
  ai:
    enabled: false                              # set to true + provide api-key to activate
    api-key: ${AIQA_AI_API_KEY:}               # export AIQA_AI_API_KEY=sk-proj-...
    model: gpt-4o-mini
    base-url: https://api.openai.com/v1        # override for Azure/Ollama/etc.
    confidence-lower-bound: 0.30               # AI only runs when score >= this
    confidence-upper-bound: 0.75               # AI only runs when score <= this
    max-diff-chars: 3000
    timeout-seconds: 15
    max-score-adjustment: 0.15                 # AI can change score by at most ±0.15

logging:
  level:
    nz.co.eroad.qaisystem: DEBUG
    org.apache.kafka: WARN
```
