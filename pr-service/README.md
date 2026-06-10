# pr-service

**Port:** `8080` · **Phase:** 0 — PR Ingestion & Enrichment  
**Role:** Receives Pull Request events (webhook or REST), validates and enriches them, publishes
`PullRequest` to Kafka. No AI — purely I/O: validate → enrich → publish.

---

## Package Structure

```
nz/co/eroad/qaisystem/
├── pr/           PrServiceApplication.java
├── controller/   PRController.java         ← 4 endpoints
├── service/      PRService.java            ← validate + enrich + publish
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
      1. enrich()  — defaults: prId=PR-{UUID8}, targetBranch=main, status=OPEN, diffs=[]
      2. validate() — title/author/repositoryName must not be blank; warns (not fails) if no diff
      3. featureUpdatesProducer.publishPullRequest(enriched)
    ▼
Kafka: FeatureUpdatesQueue  →  impact-service
```

---

## Classes

| Class | Responsibility |
|-------|----------------|
| `PRController` | Routes `/webhook` (no `@Valid`), `/submit` (`@Valid`), `/demo`, `/health` |
| `PRService` | Enrich → validate → publish. `createSamplePullRequest()` builds a JWT-auth demo PR |
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
```

---

## Tests

| Class | Tests | Covers |
|-------|-------|--------|
| `PRServiceTest` | 9 | enrichment, validation, Kafka publish |
| `PRControllerTest` | 4 | webhook, submit, demo, health endpoints |
| `PRControllerAdviceTest` | 5 | global exception handler |
| `ProductsFieldDeserializerTest` | 7 | JSON array, comma-string, whitespace trim, null/empty |
