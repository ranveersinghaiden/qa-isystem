# impact-service

**Port:** `8081`  
**Phase:** 1 — Deterministic Impact Analysis  
**Role:** Consumes PR events from Kafka, runs a fully rule-based impact pipeline
(**zero AI / zero LLM**), then publishes an `ImpactEnvelope` for the strategy layer.

---

## Responsibilities

- Parse raw unified git diffs into structured `GitDiff` objects
- Build a lightweight dependency graph from import statements
- Classify change types (API_CHANGE, NEW_FEATURE, BUG_FIX, etc.) via regex heuristics
- Score risk on four weighted axes: churn, change severity, component criticality, coverage gap
- Assess existing test coverage to determine **which areas actually lack tests**
- Publish `ImpactEnvelope` to **`ImpactResultsQueue`**

> **No AI here.** Every decision in this service is deterministic and reproducible.
> The same diff will always produce the same envelope.

---

## Package Structure

```
qaisystem/
├── impact/
│   └── ImpactServiceApplication.java      ← Spring Boot entry point
├── engine/
│   ├── ImpactEngine.java                  ← Orchestrates the full analysis pipeline
│   ├── GitDiffParser.java                 ← Parses unified diff format → List<GitDiff>
│   ├── DependencyGraph.java               ← Import-graph analysis, caller/callee mapping
│   ├── ChangeTypeDetector.java            ← Regex-based change classification
│   └── RiskScorer.java                    ← Weighted risk score (0.0–1.0) → RiskLevel
├── service/
│   └── TestCoverageService.java           ← Checks which impacted areas lack test files
├── kafka/
│   ├── FeatureUpdatesConsumer.java        ← Consumes PullRequest from Kafka
│   └── ImpactResultsProducer.java         ← Publishes ImpactEnvelope to Kafka
└── controller/
    └── ImpactController.java              ← Synchronous analysis REST endpoints
```

---

## Engine Components

### `GitDiffParser`
Parses `git diff` unified format into `List<GitDiff>`. Handles:
- New files (`new file mode`) → `DiffType.ADDED`
- Deletions (`deleted file mode`) → `DiffType.DELETED`
- Renames (`rename`) → `DiffType.RENAMED`
- Hunk headers (`@@ -n,m +p,q @@`) → `DiffHunk` with per-line added/removed/context tracking
- Test file detection by path patterns: `Test`, `Spec`, `IT`, `test/`, `spec/`

### `DependencyGraph`
- Extracts Java `import` statements from added/modified lines
- Builds a `Map<className, List<importedClasses>>` graph
- Identifies **callers** (other changed classes that import this one) to model blast radius
- Assigns each file a `ComponentType`: CONTROLLER / SERVICE / REPOSITORY / MODEL / CONFIG / TEST / UTILITY

### `ChangeTypeDetector`
Uses two sets of `Pattern` maps:

| Strategy | Examples |
|----------|---------|
| File path patterns | `application.yml` → CONFIGURATION_CHANGE; `pom.xml` → DEPENDENCY_UPDATE |
| Content patterns | `@RestController` → API_CHANGE; `migration` → DATABASE_CHANGE; `security` → SECURITY_FIX |
| Structural rules | New non-test file → NEW_FEATURE; deletion → REFACTORING; > 50 deleted lines → BREAKING_CHANGE |

### `RiskScorer`
Four weighted factors produce a normalised `0.0–1.0` score:

| Factor | Weight | Calculation |
|--------|--------|-------------|
| Churn | 25% | `min(1.0, totalLines / 300)` |
| Change type severity | 30% | Average severity across detected types (BREAKING=1.0 … REFACTORING=0.3) |
| Component criticality | 25% | CONTROLLER=0.8, REPOSITORY=0.7, SERVICE=0.6 … + caller-count bonus |
| Coverage gap | 20% | Higher score when test files are absent |

Risk levels: `CRITICAL (≥0.9)` / `HIGH (≥0.7)` / `MEDIUM (≥0.4)` / `LOW`

### `TestCoverageService` ⭐
The intelligence upgrade that makes this system a smart QA layer rather than a blind
test generator.

Given the impacted components and all diffs it:
1. Counts `testFiles / srcFiles` → `coverageRatio`
2. For each non-test component, checks for a matching `*Test.java`, `*Spec.java`, or `*IT.java` in the diff
3. Returns a `CoverageReport` with `level` (GOOD ≥80% / PARTIAL ≥20% / NONE <20%)
   and the list of `missingCoverageAreas`

The strategy-service uses this to **force `CREATE_TESTS`** even when other signals would
have produced `UPDATE_TESTS` or `SKIP`.

---

## Kafka Flow

| Direction | Topic | Payload |
|-----------|-------|---------|
| **Consumes** | `FeatureUpdatesQueue` | `PullRequest` JSON |
| **Produces** | `ImpactResultsQueue` | `ImpactEnvelope` JSON |

---

## API Endpoints

### `GET /api/impact/status`
```json
{ "service": "impact-service", "status": "OPERATIONAL", "timestamp": "..." }
```

### `POST /api/impact/analyze`
Synchronously analyze a raw diff without going through Kafka. Useful for debugging
and integration with CI pipelines.

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
kafka:
  topics:
    feature-updates: FeatureUpdatesQueue
    impact-results: ImpactResultsQueue
aiqa:
  strategy:
    risk-threshold-high: 0.7
    risk-threshold-medium: 0.4
logging:
  level:
    qaisystem: DEBUG
```

