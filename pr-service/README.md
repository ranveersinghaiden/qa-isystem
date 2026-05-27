# pr-service

**Port:** `8080`  
**Phase:** 0 — PR Ingestion & Enrichment  
**Package root:** `nz.co.eroad.qaisystem`  
**Role:** Receives Pull Request events (via webhook or REST API), validates and enriches them,
then publishes a `PullRequest` message to Kafka to start the pipeline.

> **No AI here.** This service is purely I/O — validate → enrich → publish.

---

## Table of Contents

1. [Package Structure](#package-structure)
2. [End-to-End Data Flow](#end-to-end-data-flow)
3. [Class-by-Class Breakdown](#class-by-class-breakdown)
4. [Sample PR Payload (Demo Mode)](#sample-pr-payload-demo-mode)
5. [API Endpoints](#api-endpoints)
6. [Configuration](#configuration)

---

## Package Structure

```
nz/co/eroad/qaisystem/
├── pr/
│   └── PrServiceApplication.java       ← Spring Boot entry point
├── controller/
│   └── PRController.java               ← HTTP endpoints (webhook, submit, demo, health)
├── service/
│   └── PRService.java                  ← Validate, enrich, and publish PullRequests
└── kafka/
    └── FeatureUpdatesProducer.java      ← Serialise and publish to FeatureUpdatesQueue
```

> **Note:** pr-service is producer-only. It writes to Kafka but never reads from it.
> There is no `KafkaConfig.java` or `@KafkaListener` here — only a `KafkaTemplate`
> auto-configured by Spring Boot from `application.yaml`.

---

## End-to-End Data Flow

```
  HTTP Client (Git webhook / curl / CI)
          │
          │  POST /api/pr/webhook    ← raw Git webhook body
          │  POST /api/pr/submit     ← manual PullRequest JSON
          │  POST /api/pr/demo       ← no body needed
          ▼
  ┌───────────────────────────────────────────────────────────┐
  │ PRController                                              │
  │  - /webhook → no @Valid (tolerant of missing fields)      │
  │  - /submit  → @Valid   (strict validation)                │
  │  - /demo    → calls PRService.createSamplePullRequest()   │
  │  - /health  → returns {"status":"UP"}                     │
  └──────────────────────┬────────────────────────────────────┘
                         │  PullRequest (may have null fields)
                         ▼
  ┌───────────────────────────────────────────────────────────┐
  │ PRService.processPullRequest(PullRequest)                  │
  │                                                           │
  │  1. enrich(pr)                                            │
  │       ├─ prId         → keep if present, else UUID prefix │
  │       ├─ targetBranch → keep if present, else "main"      │
  │       ├─ createdAt    → keep if present, else now()        │
  │       ├─ status       → keep if present, else OPEN         │
  │       └─ diffs        → keep if present, else empty list  │
  │                                                           │
  │  2. validate(enriched)                                    │
  │       ├─ title must not be blank                          │
  │       ├─ author must not be blank                         │
  │       ├─ repositoryName must not be blank                 │
  │       └─ warns (not fails) if diffs AND rawDiffContent    │
  │          are both absent                                  │
  │                                                           │
  │  3. featureUpdatesProducer.publishPullRequest(enriched)   │
  └──────────────────────┬────────────────────────────────────┘
                         │  enriched PullRequest
                         ▼
  ┌───────────────────────────────────────────────────────────┐
  │ FeatureUpdatesProducer                                    │
  │  Jackson → JSON string                                    │
  │  kafkaTemplate.send(topic, prId, json)                    │
  │  CompletableFuture.whenComplete → logs partition+offset   │
  └──────────────────────┬────────────────────────────────────┘
                         │
                         ▼
  Kafka: FeatureUpdatesQueue  ←── impact-service consumes
```

---

## Class-by-Class Breakdown

### PrServiceApplication
**`pr/PrServiceApplication.java`**

Standard `@SpringBootApplication` with an explicit `scanBasePackages = "nz.co.eroad.qaisystem"`
to ensure the component scan covers all sub-packages regardless of where the main class lives
relative to the other packages.

---

### PRController
**`controller/PRController.java`**

Four REST endpoints, all under `/api/pr`. Delegates 100% of logic to `PRService` — it does
nothing except call through and format the HTTP response.

| Method | Path | @Valid? | Returns |
|--------|------|---------|---------|
| `POST` | `/webhook` | No | `202 ACCEPTED` + `{status, prId, message}` |
| `POST` | `/submit` | **Yes** | `201 CREATED` + `{status, prId, message, details}` |
| `POST` | `/demo` | No body | `202 ACCEPTED` + `{status, prId, prTitle, diffsCount, message}` |
| `GET` | `/health` | — | `200 OK` + `{status:"UP", service:"PullRequestController"}` |

**`/webhook` vs `/submit`:**
- `/webhook` is lenient — no `@Valid`, accepts a raw Git webhook payload that may be missing optional fields
- `/submit` uses `@Valid` and Jakarta Bean Validation annotations on the `PullRequest` model for stricter enforcement

**`/demo`:** calls `PRService.createSamplePullRequest()` first, then `processPullRequest()`.
The sample represents a realistic JWT auth PR with three diff files (a new `AuthController`,
a modified `UserService`, and a new `JwtToken` model).

---

### PRService
**`service/PRService.java`**

The core business logic. Two public methods:

#### `processPullRequest(PullRequest pr)`
Three stages:

**1. `enrich(pr)`** — builds a new `PullRequest` instance (immutable pattern) with these defaults applied:

| Field | If present in input | If absent |
|-------|-------------------|-----------|
| `prId` | kept as-is | `"PR-" + UUID(8 chars uppercase)` |
| `targetBranch` | kept | `"main"` |
| `createdAt` | kept | `LocalDateTime.now()` |
| `status` | kept | `PrStatus.OPEN` |
| `diffs` | kept | empty `ArrayList` |

All other fields (`title`, `author`, `repositoryName`, `repositoryUrl`, `sourceBranch`,
`description`, `rawDiffContent`) are copied verbatim with no transformation.

**2. `validate(enriched)`** — collects errors into a list, then throws `IllegalArgumentException`
if any are found:
- `title` must not be null or blank
- `author` must not be null or blank
- `repositoryName` must not be null or blank
- If both `diffs` (empty list) and `rawDiffContent` (null/blank) are absent: logs a `WARN` but **does not throw** — the PR still goes through, impact analysis will just produce a minimal result

**3. `featureUpdatesProducer.publishPullRequest(enriched)`** — delegates to Kafka producer.

#### `createSamplePullRequest()`
Returns a pre-built `PullRequest` representing a JWT auth feature PR. Contains:
- `rawDiffContent` — a multi-file unified diff string
- `diffs` — three pre-built `GitDiff` objects with hunks and line-level detail

The sample diffs are rich enough to exercise every engine component in impact-service
(API_CHANGE detector, CONTROLLER component type, ADDED diff type, etc.).

---

### FeatureUpdatesProducer
**`kafka/FeatureUpdatesProducer.java`**

Single public method `publishPullRequest(PullRequest)`:
1. Serialises the `PullRequest` to a JSON string via Jackson `ObjectMapper`
2. `kafkaTemplate.send(topic, prId, json)` — uses `prId` as the Kafka message **key** so
   all messages for the same PR land on the same partition and are processed in order
3. Returns a `CompletableFuture<SendResult>` — `.whenComplete()` logs partition and offset
   on success, or the error message on failure
4. On `JsonProcessingException`: wraps in `RuntimeException` and re-throws (Spring will
   return a 500 to the caller)

The topic name is injected from `${kafka.topics.feature-updates}` — defaults to
`FeatureUpdatesQueue`.

> **No consumer, no listener container.** pr-service only produces. It uses Spring Boot's
> default `KafkaAutoConfiguration` which creates a `KafkaTemplate` from the YAML producer
> config, with no custom `KafkaConfig.java` needed.

---

## Sample PR Payload (Demo Mode)

Calling `POST /api/pr/demo` sends this payload through the pipeline:

```
PR title:  "feat: Add user authentication with JWT"
Author:    dev@example.com
Repo:      user-service
Branch:    feature/jwt-auth → main
```

**Three diff files:**

| File | DiffType | +Lines | −Lines | Triggers |
|------|---------|--------|--------|---------|
| `AuthController.java` | ADDED | 85 | 0 | `NEW_FEATURE`, `API_CHANGE` (has `@RestController`, `@PostMapping`) |
| `UserService.java` | MODIFIED | 45 | 12 | `BUG_FIX` (has `TODO` being removed), `API_CHANGE` (security keywords) |
| `JwtToken.java` | ADDED | 30 | 0 | `NEW_FEATURE` (MODEL component type) |

Expected impact-service output: `riskLevel=HIGH`, `coverage=NONE` (no test files in diff),
strategy-service decision: `CREATE_TESTS`.

---

## API Endpoints

### `POST /api/pr/webhook`
Accepts a raw Git webhook JSON. Missing fields are tolerated.

**Minimal body:**
```json
{
  "title": "feat: add payment gateway",
  "author": "dev@example.com",
  "repositoryName": "payment-service",
  "sourceBranch": "feature/payments",
  "rawDiffContent": "diff --git a/src/PaymentService.java ..."
}
```

**Response:**
```json
{ "status": "ACCEPTED", "prId": "PR-A1B2C3D4", "message": "PR accepted and queued for AI analysis" }
```

---

### `POST /api/pr/submit`
Same as webhook but with `@Valid` — all required fields enforced by Jakarta Bean Validation.

**Full body:**
```json
{
  "title": "feat: add payment gateway",
  "author": "dev@example.com",
  "repositoryName": "payment-service",
  "sourceBranch": "feature/payments",
  "targetBranch": "main",
  "jira_ids": ["PAY-123"],
  "rawDiffContent": "diff --git a/src/PaymentService.java ..."
}
```

**Response (201):**
```json
{
  "status": "QUEUED",
  "prId": "PR-A1B2C3D4",
  "message": "PR queued for AI-driven QA analysis",
  "details": { "sourceBranch": "feature/payments", "targetBranch": "main", "author": "dev@example.com" }
}
```

---

### `POST /api/pr/demo`
No body required. Triggers a pre-built sample PR.

```bash
curl -X POST http://localhost:8080/api/pr/demo
```

**Response (202):**
```json
{
  "status": "DEMO_TRIGGERED",
  "prId": "PR-A1B2C3D4",
  "prTitle": "feat: Add user authentication with JWT",
  "diffsCount": 3,
  "message": "Demo PR published — watch logs for full pipeline execution"
}
```

---

### `GET /api/pr/health`
```json
{ "status": "UP", "service": "PullRequestController" }
```

---

## Configuration (`application.yaml`)

```yaml
server:
  port: 8080

spring:
  application:
    name: pr-service
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      acks: all
      retries: 3
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
    listener:
      missing-topics-fatal: false
    properties:
      reconnect.backoff.ms: 1000
      reconnect.backoff.max.ms: 10000

kafka:
  topics:
    feature-updates: FeatureUpdatesQueue   # produced by this service

logging:
  level:
    nz.co.eroad.qaisystem: INFO
    org.apache.kafka: WARN
```
