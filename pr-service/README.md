# pr-service

**Port:** `8080`  
**Phase:** 1 — PR Ingestion  
**Role:** The public-facing entry point. Receives PR events from Git webhooks or manual
API calls, validates and enriches the payload, then publishes it to Kafka for downstream
processing.

---

## Responsibilities

- Accept `PullRequest` payloads via REST (`/webhook`, `/submit`, `/demo`)
- Assign a `prId` if missing, default `targetBranch` to `main`, set `createdAt`
- Validate required fields (`title`, `author`, `repositoryName`)
- Publish enriched `PullRequest` to **`FeatureUpdatesQueue`**

> **No analysis happens here.** This service is intentionally thin — its only job is
> reliable ingestion and publishing.

---

## Package Structure

```
qaisystem/
├── pr/
│   └── PrServiceApplication.java      ← Spring Boot entry point
├── controller/
│   └── PRController.java              ← REST endpoints
├── service/
│   └── PRService.java                 ← Validation, enrichment, sample data
└── kafka/
    └── FeatureUpdatesProducer.java     ← Publishes PullRequest to Kafka
```

---

## API Endpoints

### `POST /api/pr/webhook`
Receive a raw Git webhook. No validation — accepts any `PullRequest` JSON.

**Request:**
```json
{
  "title": "feat: Add JWT auth",
  "author": "dev@example.com",
  "repositoryName": "user-service",
  "sourceBranch": "feature/jwt-auth",
  "targetBranch": "main",
  "rawDiffContent": "diff --git a/AuthController.java ...",
  "jira_ids": ["AUTH-42"]
}
```

**Response `202 Accepted`:**
```json
{ "status": "ACCEPTED", "prId": "PR-A1B2C3D4", "message": "PR accepted and queued for AI analysis" }
```

---

### `POST /api/pr/submit`
Manually submit a PR for analysis. Validated with Bean Validation (`@Valid`).

**Response `201 Created`:**
```json
{
  "status": "QUEUED",
  "prId": "PR-A1B2C3D4",
  "message": "PR queued for AI-driven QA analysis",
  "details": { "sourceBranch": "feature/jwt-auth", "targetBranch": "main", "author": "dev@example.com" }
}
```

---

### `POST /api/pr/demo`
Triggers a complete pipeline run using built-in sample data (JWT auth feature with
controller, service, and model diffs). No request body required.

**Response `202 Accepted`:**
```json
{ "status": "DEMO_TRIGGERED", "prId": "PR-DEMO1234", "diffsCount": 3, ... }
```

---

### `GET /api/pr/health`
```json
{ "status": "UP", "service": "PullRequestController" }
```

---

## Kafka Output

| Topic | Key | Value |
|-------|-----|-------|
| `FeatureUpdatesQueue` | `prId` | Serialised `PullRequest` JSON |

Messages are keyed by `prId` so all events for the same PR land on the same partition,
preserving order.

---

## Configuration (`application.yaml`)

```yaml
server:
  port: 8080
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: pr-service-group
kafka:
  topics:
    feature-updates: FeatureUpdatesQueue
```

