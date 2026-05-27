# common

Shared library module. Contains all domain models, shared Spring configuration beans,
and Kafka infrastructure. Every service depends on this module; it is never deployed
independently.

---

## Contents

### Models (`qaisystem.model`)

| Class | Description |
|-------|-------------|
| `PullRequest` | Incoming PR event. Fields: `prId`, `title`, `author`, `sourceBranch`, `targetBranch`, `repositoryName`, `rawDiffContent`, `diffs`, `jiraIds`, `changedFiles`, `status`. |
| `GitDiff` | Single file diff: `filePath`, `diffType` (ADDED/MODIFIED/DELETED/RENAMED), `hunks`, `linesAdded`, `linesDeleted`, `fileExtension`, `isTestFile`. |
| `ImpactEnvelope` | Full impact analysis result produced by impact-service. Contains `riskLevel`, `overallRiskScore`, `detectedChangeTypes`, `impactedComponents`, `serviceConfidence`, `coverageReport`, dependency graph, and strategy hints. |
| `CoverageReport` | Test coverage snapshot: `level` (GOOD/PARTIAL/NONE), `coverageRatio`, `existingTestFiles`, `missingCoverageAreas`, `requiresNewTests`. |
| `TestStrategy` | Decision from StrategyAgent: `decision` (CREATE/UPDATE/SKIP), `confidenceScore`, `fullRegressionRequired`, `expandedScope`, `expandedAreas`, `newTestRequirements`. |
| `BddScenario` | Gherkin feature file model: `featureTitle`, `scenarios` (each with given/when/then steps, tags, examples). |
| `TestScript` | Generated test code ready for execution: `scriptContent`, `testType` (API/UI/MOBILE), `status`, `retryCount`. |
| `TestResult` | Execution outcome: `passed`, `attemptNumber`, `executionTimeMs`, `errorMessage`, `stabilized`, `finalScriptContent`. |

### Configuration (`qaisystem.config`)

| Class | Description |
|-------|-------------|
| `AppConfig` | `ObjectMapper` bean with `JavaTimeModule` (ISO-8601 dates), async task executor (`qa-async-*` threads). |
| `KafkaConfig` | Producer factory, consumer factory, `KafkaTemplate`, `KafkaAdmin`, and all four topic declarations. Configured from `application.yaml`. |

---

## Key Design Decisions

- **No `@SpringBootApplication`** — this is a plain library jar, not a deployable service.
- Topic declarations are **idempotent**: all services declare all topics so any service can
  start first without Kafka errors.
- Models use **Jackson `@JsonProperty`** on snake_case fields (`pr_id`, `jira_ids`,
  `changed_files`) so they map correctly to standard Git webhook payloads.
- All models are `@Data @Builder @NoArgsConstructor @AllArgsConstructor` — safe for Kafka
  JSON serialisation and deserialisation.

