# pr-service

**Port:** `8080` · **Phase:** 0 — PR Ingestion, Context Extraction & Compression  
**Role:** Receives Pull Request events (webhook or REST), validates and enriches them, extracts external context, **AI-compresses the context via Copilot CLI**, publishes `PullRequest` to Kafka.

---

## Package Structure

```
nz/co/eroad/qaisystem/
├── pr/           PrServiceApplication.java
├── controller/   PRController.java              ← 4 endpoints
├── service/      PRService.java                 ← validate + enrich + compress + publish
│                 PrContextExtractor.java         ← regex extraction (Jira, Confluence, labels)
│                 ContextCompressionService.java  ← AI compression via Copilot CLI
├── config/       CompressionConfig.java          ← creates ContextCompressionService bean
│                 CompressionProperties.java      ← aiqa.ai.compression.* properties
└── kafka/        FeatureUpdatesProducer.java
```

---

## Data Flow

```
POST /api/pr/{webhook|submit|demo}
    │
    ▼ PRController
    │ PullRequest (raw)
    ▼ PRService
      1. enrich()           — defaults: prId=PR-{SHA8}, targetBranch=main, status=OPEN, diffs=[]
      2. parseDiffIfNeeded()— parses raw_diff string → List<GitDiff>
      3. PrContextExtractor — mines title/description/labels for Jira IDs, Confluence links, products
      4. ContextCompressionService.compress()
                            — Copilot CLI call: distils title+description+labels into contextSummary
                            — best-effort: pass-through if disabled (AIQA_COMPRESSION_ENABLED=false)
                            — raw diff and structured fields (jiraIds, labels) are NEVER compressed
      5. validate()         — title/author/repositoryName must not be blank
      6. featureUpdatesProducer.publishPullRequest(enriched)
    ▼
Kafka: FeatureUpdatesQueue  →  impact-service
```

---

## Classes

| Class | Responsibility |
|-------|----------------|
| `PRController` | Routes `/webhook` (no `@Valid`), `/submit` (`@Valid`), `/demo`, `/health` |
| `PRService` | Enrich → extract context → compress → validate → publish |
| `PrContextExtractor` | Regex extraction: Jira IDs/URLs, Confluence URLs, labels, products |
| `ContextCompressionService` | Calls Copilot CLI; sets `PullRequest.contextSummary`; pass-through on failure |
| `CompressionConfig` | Creates `ContextCompressionService` with its own `CopilotCliClient` (independent of shared `AiClientConfig`) |
| `CompressionProperties` | Binds `aiqa.ai.compression.*` config properties |
| `FeatureUpdatesProducer` | `kafkaTemplate.send(topic, prId, json)` — keyed by `prId` for partition ordering |
| `CommaSeparatedListDeserializer` | Jackson deserializer: `products` accepts JSON array **or** comma-separated string |

---

## API Endpoints

| Method | Path | `@Valid` | Response |
|--------|------|---------|---------|
| `POST` | `/api/pr/webhook` | No | `202` `{status, prId, message}` |
| `POST` | `/api/pr/submit` | **Yes** | `201` `{status, prId, message, details}` |
| `POST` | `/api/pr/demo` | — | `202` `{status, prId, prTitle, diffsCount, message}` |
| `GET`  | `/api/pr/health` | — | `200` `{status:"UP"}` |

### `PullRequest` payload fields

| Field | Type | Notes |
|-------|------|-------|
| `title` | `String` | **Required.** Becomes GitHub PR title downstream: `[AI-QA] {title}` |
| `author` | `String` | **Required.** |
| `repositoryName` | `String` | **Required.** |
| `raw_diff` | `String` | Unified diff — parsed into `GitDiff` list |
| `products` | `String` \| `String[]` | Optional. JSON array or comma-separated string. Normalised to `List<String>`. |
| `jira_ids` | `String[]` | Optional. |
| `pr_id` | `String` | Optional. Auto-generated as `PR-{UUID8}` if absent. |

**Minimal body:**
```json
{
  "title": "feat: add payment gateway",
  "author": "dev@example.com",
  "repositoryName": "payment-service",
  "rawDiffContent": "diff --git a/src/PaymentService.java ..."
}
```

---

## Configuration (`application.yaml`)

```yaml
server.port: 8080
spring.kafka.bootstrap-servers: localhost:9092
spring.kafka.producer.acks: all
kafka.topics.feature-updates: FeatureUpdatesQueue

aiqa:
  ai:
    compression:
      enabled: ${AIQA_COMPRESSION_ENABLED:false}   # true to enable Copilot CLI compression
      gh-cli-path: ${GH_CLI_PATH:gh}
      model: ${COPILOT_CLI_MODEL:gpt-5}
      timeout-seconds: ${COPILOT_CLI_TIMEOUT:60}
```

**Enabling compression:** set `AIQA_COMPRESSION_ENABLED=true` (or `aiqa.ai.compression.enabled: true` in your local yaml).  
Prerequisites: `gh` CLI installed and authenticated (`gh auth login`). Compression is disabled by default so the service starts cleanly in environments without the CLI.

> **Note:** `aiqa.github.enabled` must **not** be set in pr-service. That flag activates the shared `AiClientConfig`, `GitHubService`, and `RepoContextService` beans — none of which belong in an ingestion service. The compression service uses its own isolated `CopilotCliClient` instance.

---

## Tests

| Class | Tests | Covers |
|-------|-------|--------|
| `PRServiceTest` | 9 | enrichment, validation, Kafka publish |
| `ContextCompressionServiceTest` | 10 | null client, unavailable client, valid compression, field preservation, blank response, exception swallowing |
| `PRControllerTest` | 4 | webhook, submit, demo, health endpoints |
| `PRControllerAdviceTest` | 5 | global exception handler |
| `ProductsFieldDeserializerTest` | 7 | JSON array, comma-string, whitespace trim, null/empty |
