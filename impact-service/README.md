# impact-service

**Port:** `8081` · **Phase:** 1 — Deterministic Impact Analysis  
**Role:** Consumes `PullRequest` from Kafka, runs a fully rule-based impact pipeline
(parse → graph → detect → score → coverage), optionally refines the risk score with an LLM
in the gray zone, publishes `ImpactEnvelope` to Kafka.

> **Deterministic by default.** AI is opt-in and only runs when `aiqa.ai.enabled=true`
> AND the risk score falls in the configured gray zone (`0.30–0.75`).

---

## Package Structure

```
nz/co/eroad/qaisystem/
├── impact/       ImpactServiceApplication.java
├── ai/           AIImpactEvaluator.java        ← LLM last-resort (opt-in, gray-zone only)
├── config/       KafkaConfig.java · AIImpactProperties.java
├── kafka/        FeatureUpdatesConsumer.java · ImpactResultsProducer.java
├── engine/       ImpactEngine.java · GitDiffParser.java · DependencyGraph.java
│                 ChangeTypeDetector.java · RiskScorer.java
├── service/      TestCoverageService.java
└── controller/   ImpactController.java
```

---

## Pipeline

```
Kafka: FeatureUpdatesQueue (PullRequest)
    │
    ▼ FeatureUpdatesConsumer
    ▼ ImpactEngine.analyze()
      Step 1 – GitDiffParser       raw diff → List<GitDiff>
      Step 2 – DependencyGraph     imports → ImpactedComponent list
      Step 3 – ChangeTypeDetector  regex/keyword → List<ChangeType>
      Step 4 – RiskScorer          weighted score 0.0–1.0 → RiskLevel
      Step 4b– AIImpactEvaluator   [opt-in] refines score if in gray zone (max ±0.15)
      Step 5 – TestCoverageService component types → CoverageReport (level=UNKNOWN)
    ▼ ImpactResultsProducer
Kafka: ImpactResultsQueue  →  strategy-service
```

---

## Engine Components

### GitDiffParser
Parses unified `git diff` output into `List<GitDiff>`. Detects:
- **File type** from header: `new file mode`=ADDED, `deleted file mode`=DELETED, `rename`=RENAMED, default=MODIFIED
- **Line types**: `+`=ADDED, `-`=REMOVED, space=CONTEXT
- **Test files**: path contains `Test`, `Spec`, `IT`, `test/`, `spec/`

### DependencyGraph
Extracts `import` statements from changed lines to build `class → [imports]` graph.
Computes `impactScore = min(1.0, churnBase + callerBonus)` per component.
Assigns `ComponentType` from path keyword: `controller`, `service`, `repositor`/`dao`, `model`/`entity`/`dto`, `config`, `test`, `util`.

### ChangeTypeDetector
Classifies each diff file into `ChangeType` values. Default: `NEW_FEATURE`.

| Condition | ChangeType |
|-----------|-----------|
| `DiffType==ADDED`, not test | `NEW_FEATURE` |
| `DiffType==DELETED` | `REFACTORING` |
| Config/build path | `CONFIGURATION_CHANGE` / `DEPENDENCY_UPDATE` / `DATABASE_CHANGE` |
| Content: TODO/fix/bug | `BUG_FIX` |
| Content: @Deprecated/refactor/rename | `REFACTORING` |
| Content: security/auth/token | `SECURITY_FIX` |
| Content: cache/performance/async | `PERFORMANCE_IMPROVEMENT` |
| Content: @RestController/@*Mapping | `API_CHANGE` |
| Content: migration/schema/ALTER TABLE | `DATABASE_CHANGE` |
| `linesDeleted > 50` | `BREAKING_CHANGE` |

### RiskScorer
```
score = 0.25×churn + 0.30×changeTypeSeverity + 0.25×componentCriticality + 0.20×coverageGap
```
| Score | Level |
|-------|-------|
| ≥0.9 | CRITICAL |
| ≥0.7 | HIGH |
| ≥0.4 | MEDIUM |
| <0.4 | LOW |

### TestCoverageService (Phase 1)
Identifies **which components need integration tests** — does NOT check the test repo.
Sets `level=UNKNOWN` in the `CoverageReport`. Real level (GOOD/PARTIAL/NONE) is set by
`E2ECoverageAnalyzer` in strategy-service after scanning the cloned test repo.

---

## AI Last-Resort Evaluator

Only runs when `aiqa.ai.enabled=true` AND score is in the gray zone:

```
0.0 ────── 0.30 ──────────── 0.75 ────── 1.0
  Confident LOW   Gray zone   Confident HIGH
  (skip AI)   (AI refines)   (skip AI)
```

Any failure (timeout, bad JSON, network error) falls back to the deterministic result.
Max score adjustment: ±0.15. Adds missed `ChangeType` entries to the envelope.

**Enable:**
```yaml
aiqa.ai.enabled: true
aiqa.ai.api-key: ${AIQA_AI_API_KEY:}
aiqa.ai.model: gpt-4o-mini   # or gpt-4o for higher accuracy
```

For Azure: set `aiqa.ai.base-url: https://{resource}.openai.azure.com/...`  
For Ollama: `aiqa.ai.base-url: http://localhost:11434/v1`, `aiqa.ai.api-key: ollama`

---

## Kafka

| Direction | Topic | Payload |
|-----------|-------|---------|
| Consumes | `FeatureUpdatesQueue` | `PullRequest` JSON |
| Produces | `ImpactResultsQueue` | `ImpactEnvelope` JSON (key=`prId`) |

Consumer group: `impact-service-group`

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/impact/status` | `{service, status, timestamp}` |
| `POST` | `/api/impact/analyze` | Synchronous diff analysis — returns risk/coverage summary, no Kafka. |

### `POST /api/impact/analyze` sample response
```json
{
  "filesFound": 2,
  "changeTypes": ["API_CHANGE", "NEW_FEATURE"],
  "riskScore": "0.72",
  "riskLevel": "HIGH",
  "coverage": { "level": "UNKNOWN", "untestedComponents": ["AuthController"], "requiresNewTests": true },
  "aiInsight": { "applied": false, "reason": "AI disabled" }
}
```

> `level=UNKNOWN` is always correct here — real level is set downstream in strategy-service.

---

## Configuration (`application.yaml`)

```yaml
server.port: 8081
spring.kafka.bootstrap-servers: localhost:9092
spring.kafka.consumer.group-id: impact-service-group
kafka.topics:
  feature-updates: FeatureUpdatesQueue
  impact-results:  ImpactResultsQueue

aiqa.ai:
  enabled: false                              # set true to enable LLM refinement
  api-key: ${AIQA_AI_API_KEY:}
  model: gpt-4o-mini
  confidence-lower-bound: 0.30
  confidence-upper-bound: 0.75
  max-score-adjustment: 0.15
```

---

## Tests

| Class | Tests | Covers |
|-------|-------|--------|
| `GitDiffParserTest` | 7 | diff parsing, file type detection |
| `RiskScorerTest` | 11 | thresholds, weights, normalisation |
| `TestCoverageServiceTest` | 9 | coverage ratio, component filtering |
