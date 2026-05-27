# impact-service

**Port:** `8081`  
**Phase:** 1 — Deterministic Impact Analysis  
**Package root:** `nz.co.eroad.qaisystem`  
**Role:** Consumes PR events from Kafka, runs a fully rule-based impact pipeline
(**zero AI / zero LLM**), then publishes an `ImpactEnvelope` for the strategy layer.

> **No AI here.** Every decision is deterministic and reproducible.
> The same diff will always produce the same envelope.

---

## Table of Contents

1. [Package Structure](#package-structure)
2. [End-to-End Data Flow](#end-to-end-data-flow)
3. [Class-by-Class Breakdown](#class-by-class-breakdown)
4. [How TestCoverageService Works](#how-testcoverageservice-works)
5. [Kafka Flow](#kafka-flow)
6. [API Endpoints](#api-endpoints)
7. [Configuration](#configuration)

---

## Package Structure

```
nz/co/eroad/qaisystem/
├── impact/
│   └── ImpactServiceApplication.java   ← Spring Boot entry point
├── config/
│   └── KafkaConfig.java                ← Explicit consumer/producer/factory beans
├── kafka/
│   ├── FeatureUpdatesConsumer.java     ← Consumes PullRequest from Kafka
│   └── ImpactResultsProducer.java      ← Publishes ImpactEnvelope to Kafka
├── engine/
│   ├── ImpactEngine.java               ← Orchestrates the full 5-step pipeline
│   ├── GitDiffParser.java              ← Raw unified diff → List<GitDiff>
│   ├── DependencyGraph.java            ← Import-graph analysis, component typing
│   ├── ChangeTypeDetector.java         ← Regex-based change classification
│   └── RiskScorer.java                 ← Weighted risk score (0.0–1.0) → RiskLevel
├── service/
│   └── TestCoverageService.java        ← PR-diff-based coverage gap detection
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
  │  Step 5 ─ TestCoverageService                             │
  │    components + diffs                                     │
  │        └─► CoverageReport  (ratio, level, missing areas)  │
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
| Coverage gap | 20% | `max(0.1, 0.9 − ratio×0.8)` where `ratio = testFiles/srcFiles` in diff |

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

### Short answer: it does NOT connect to any external repo

`TestCoverageService` is **entirely self-contained within the PR diff**. It does
not clone a repo, call GitHub, or read the filesystem. All its inputs are:
- `List<ImpactedComponent>` — produced by `DependencyGraph`
- `List<GitDiff>` — produced by `GitDiffParser`

Both of those come solely from the `rawDiffContent` string in the `PullRequest`.

### Algorithm

```
assess(components, diffs)
  │
  ├─ srcFiles  = count diffs where isTestFile == false
  ├─ testFiles = count diffs where isTestFile == true
  │
  ├─ coverageRatio = testFiles / srcFiles  (0.0 if no src files)
  │
  ├─ CoverageLevel:
  │     ratio ≥ 0.80  →  GOOD
  │     ratio ≥ 0.20  →  PARTIAL
  │     ratio  < 0.20  →  NONE
  │
  └─ missingCoverageAreas:
       for each non-TEST ImpactedComponent:
         search diffs for a file whose path contains any of:
           <componentName>Test
           <componentName>Spec
           <componentName>IT
         if NONE found → add componentName to missingCoverageAreas
```

### Concrete example

PR diff contains three files:
```
src/PaymentService.java       (ADDED — srcFile)
src/PaymentController.java    (MODIFIED — srcFile)
test/PaymentServiceTest.java  (ADDED — testFile)
```

| Component | Matching test in diff? | In missingCoverageAreas? |
|-----------|----------------------|--------------------------|
| `PaymentService` | ✅ `PaymentServiceTest.java` | No |
| `PaymentController` | ❌ nothing | **Yes** |

```json
{
  "level": "PARTIAL",
  "coverageRatio": 0.33,
  "existingTestFiles": ["test/PaymentServiceTest.java"],
  "missingCoverageAreas": ["PaymentController"],
  "requiresNewTests": true
}
```

### How the strategy-service uses this

`StrategyAgent` treats `missingCoverageAreas` as a hard override:

```
coverage.level == NONE           → force CREATE_TESTS
!missingCoverageAreas.isEmpty()  → CREATE_TESTS for those areas
```

This is what makes the system a *smart* QA layer rather than a blind generator —
if your PR already includes tests alongside the code, the agent may decide to
`UPDATE_TESTS` or even `SKIP` instead of generating from scratch.

### Known limitations

| Limitation | Implication |
|-----------|-------------|
| Only sees files **in the PR diff** | A test that already exists in the repo but wasn't modified is invisible — it will show as "missing" |
| Name-based matching only (`*Test`, `*Spec`, `*IT`) | Unconventionally-named tests (e.g. `PaymentControllerShould.java`) will not match |
| No assertion-depth checking | A test file with zero assertions counts as "covered" |

The first limitation is intentional by design — it encourages developers to include
or update test files in every PR. A future enhancement could cross-reference the
target test repo cloned by `strategy-service`'s `RepoContextService`.

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

**Response:**
```json
{
  "filesFound": 2,
  "changeTypes": ["API_CHANGE", "NEW_FEATURE"],
  "riskScore": "0.72",
  "riskLevel": "HIGH",
  "coverage": {
    "level": "NONE",
    "ratio": "0.00",
    "missingTests": ["AuthController", "UserService"]
  }
}
```

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

logging:
  level:
    nz.co.eroad.qaisystem: DEBUG
    org.apache.kafka: WARN
```
