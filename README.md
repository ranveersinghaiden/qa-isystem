# QA-ISystem — Autonomous AI-Driven QA Pipeline

An intelligent QA platform that watches your Git pull requests, performs deterministic
impact analysis, makes minimal AI-driven test strategy decisions, generates BDD scenarios,
produces executable test code, and stabilises failing tests — all without human bottlenecks.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Module Overview](#module-overview)
3. [Kafka Topics](#kafka-topics)
4. [End-to-End Pipeline Flow](#end-to-end-pipeline-flow)
5. [Quick Start](#quick-start)
   - [1 — Start Kafka](#1--start-kafka)
   - [2 — Build all modules](#2--build-all-modules)
   - [3 — Configure environment variables](#3--configure-environment-variables)
   - [4 — Start the three services](#4--start-the-three-services-separate-terminals)
   - [5 — Verify all services are up](#5--verify-all-services-are-up)
   - [6 — Trigger the full pipeline](#6--trigger-the-full-pipeline)
6. [Running Tests Locally](#running-tests-locally)
7. [API Reference](#api-reference)
8. [Configuration](#configuration)
9. [CI/CD Pipelines](#cicd-pipelines)
10. [Technology Stack](#technology-stack)

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         QA-ISystem  ·  Event-Driven Microservices            │
│                                                                              │
│   Git / Webhook                                                              │
│        │  POST /api/pr/webhook                                               │
│        ▼                                                                     │
│  ┌─────────────┐                                                             │
│  │  pr-service │  :8080   Validates & enriches PR                            │
│  │             │──────────────────────────────────────────────►              │
│  └─────────────┘              FeatureUpdatesQueue (Kafka)                    │
│                                         │                                    │
│                                         ▼                                    │
│  ┌────────────────────────────────────────────────────────┐                  │
│  │  impact-service  :8081   Phase 1 — NO AI               │                  │
│  │                                                        │                  │
│  │  GitDiffParser → DependencyGraph → ChangeTypeDetector  │                  │
│  │       └──────────────────────────────────► RiskScorer  │                  │
│  │                                                │       │                  │
│  │                               TestCoverageService      │                  │
│  │                          (is this area already tested?)│                  │
│  │                                                │       │                  │
│  │                                    ImpactEnvelope      │                  │
│  └────────────────────────────────────────────────────────┘                  │
│                                         │                                    │
│                                ImpactResultsQueue (Kafka)                    │
│                                         │                                    │
│                                         ▼                                    │
│  ┌────────────────────────────────────────────────────────┐                  │
│  │  strategy-service  :8082   Phases 2-6                  │                  │
│  │                                                        │                  │
│  │  StrategyAgent ──► SKIP / UPDATE_TESTS / CREATE_TESTS  │                  │
│  │       │              │             │                   │                  │
│  │  Fallback Rules  BddGenerator  handleUpdateTests       │                  │
│  │  (fullRegression  (templates)   (inline delta)         │                  │
│  │   expandedScope)      │                                │                  │
│  │                  BDD Review PR ◄── Human reviews       │                  │
│  │                       │  POST /api/strategy/approve-bdd│                  │
│  │                       ▼                                │                  │
│  │              TestScriptsQueue (Kafka)                  │                  │
│  │                       │                                │                  │
│  │               CodegenService                           │                  │
│  │            ┌──────┬───┴────┐                           │                  │
│  │         API Runner  UI  Mobile                         │                  │
│  │            └──────┴───┬────┘                           │                  │
│  │               StabilizationLoop                        │                  │
│  │            (run → fail → fix, max 3×)                  │                  │
│  │                       │                                │                  │
│  │               Final Test PR ◄── Human reviews          │                  │
│  └────────────────────────────────────────────────────────┘                  │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Module Overview

| Module | Port | Responsibility |
|--------|------|----------------|
| [`common`](common/README.md) | — | Shared models, Kafka config, Jackson config |
| [`pr-service`](pr-service/README.md) | 8080 | PR ingestion — webhook receiver, validation, Kafka publisher |
| [`impact-service`](impact-service/README.md) | 8081 | Deterministic impact analysis — no AI or LLM |
| [`strategy-service`](strategy-service/README.md) | 8082 | AI strategy, BDD generation, codegen, test stabilisation |

---

## Kafka Topics

| Topic | Producer | Consumer | Payload |
|-------|----------|----------|---------|
| `FeatureUpdatesQueue` | pr-service | impact-service | `PullRequest` JSON |
| `ImpactResultsQueue` | impact-service | strategy-service | `ImpactEnvelope` JSON |
| `TestScriptsQueue` | strategy-service (approve-bdd) | strategy-service (codegen) | `BddScenario` JSON |
| `TestResultsQueue` | strategy-service (stabilisation) | *(future consumers)* | `TestResult` JSON |

---

## End-to-End Pipeline Flow

```
Phase 1 — PR Ingestion (pr-service)
  1. Git webhook fires → POST /api/pr/webhook
  2. PRService validates, enriches (assigns prId, defaults targetBranch)
  3. FeatureUpdatesProducer → FeatureUpdatesQueue

Phase 2 — Impact Analysis (impact-service)  ← NO AI
  4. FeatureUpdatesConsumer deserialises PullRequest
  5. GitDiffParser     — parses raw unified diff → List<GitDiff>
  6. DependencyGraph   — extracts import graph, identifies callers/callees
  7. ChangeTypeDetector— classifies: NEW_FEATURE, API_CHANGE, BUG_FIX, etc.
  8. RiskScorer        — weighted score (churn + type + component + coverage)
  9. TestCoverageService — checks which impacted components LACK test files
 10. ImpactEnvelope built (risk level, coverage report, service confidence)
 11. ImpactResultsProducer → ImpactResultsQueue

Phase 3 — Strategy Decision (strategy-service)  ← minimal AI
 12. ImpactResultsConsumer deserialises ImpactEnvelope
 13. StrategyAgent.decide():
       Coverage=NONE           → force CREATE_TESTS
       Only infra + LOW risk   → SKIP
       All files are tests     → SKIP
       HIGH/CRITICAL risk      → CREATE_TESTS
       New feature detected    → CREATE_TESTS
       Existing tests in diff  → UPDATE_TESTS
       Default                 → CREATE_TESTS
 14. Fallback rules applied:
       confidence < 0.4  → fullRegressionRequired = true
       HIGH/CRITICAL     → expandedScope = true (widens test areas)

Phase 4 — BDD Generation (strategy-service)
 15. BddGenerator generates Gherkin scenarios (template-based)
     — happy path, error path, boundary outline (if API_CHANGE)
 16. TestPrService creates a BDD Review PR for human approval
 17. Human reviews and approves → POST /api/strategy/approve-bdd

Phase 5 — Code Generation (strategy-service)
 18. approve-bdd publishes BddScenario → TestScriptsQueue
 19. CodegenService routes by testType:
       API    → ApiTestRunner    (RestAssured + JUnit 5)
       UI     → UITestRunner     (Selenium + ChromeDriver)
       Mobile → MobileTestRunner (Appium + AndroidDriver)

Phase 6 — Test Stabilisation (strategy-service)
 20. StabilizationLoop.execute() — up to 3 attempts:
       Attempt 1: add timeouts, retry-after config
       Attempt 2: add null guards, assertion retry wrapper
       Attempt 3: simplify to minimal smoke test
 21. On pass (any attempt): TestPrService creates Final Test PR
 22. On 3× fail:            marked ABANDONED, PR raised for human review
```

---

## Quick Start

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java | 25+ | `java -version` |
| Maven | 3.9+ | or use the `./mvnw` wrapper (included) |
| Docker Desktop | any | must be running |

### 1 — Start Kafka

```bash
# From the project root (docker-compose.yml is here)
docker compose up -d

# Poll until both containers show "(healthy)" — takes ~30-45 s
docker compose ps
```

Expected:
```
NAME            STATUS
qa-zookeeper    Up (healthy)
qa-kafka        Up (healthy)
```

> ⚠️ Do not start the Spring services until `qa-kafka` shows `(healthy)`. Starting services before Kafka is ready causes consumer group joins to fail silently.

> **Optional — Kafka UI** at `http://localhost:8090`:
> ```bash
> docker compose --profile debug up -d
> ```

### 2 — Build all modules

```bash
./mvnw clean package -DskipTests
```

### 3 — Configure environment variables

For a minimal local run **no environment variables are required** — all services start with sensible defaults. Set the variables below to unlock optional features before launching the services.

| Variable | Service | Purpose | Default |
|----------|---------|---------|---------|
| `TARGET_REPO_URL` | strategy-service | URL of the test repository to clone for coverage context | *(none — coverage will be `UNKNOWN`)* |
| `TARGET_REPO_TOKEN` | strategy-service | Personal access token (PAT) for a private test repo | *(none — falls back to system credential store / osxkeychain)* |
| `TARGET_REPO_USERNAME` | strategy-service | GitHub username paired with the PAT | *(none)* |
| `AIQA_AI_ENABLED` | impact-service | Set `true` to enable AI-assisted risk scoring in the gray zone | `false` |
| `AIQA_AI_API_KEY` | impact-service | OpenAI (or compatible) API key — required when AI is enabled | *(none)* |
| `AIQA_AI_MODEL` | impact-service | Model used for AI scoring | `gpt-4o-mini` |
| `KAFKA_HOST` | all (production only) | Kafka broker hostname advertised to external clients | `localhost` |

Export the variables you need in each terminal **before** starting a service:

```bash
# ── Optional: target test repository (strategy-service) ──────────────────────
export TARGET_REPO_URL=https://github.com/your-org/your-test-repo
export TARGET_REPO_TOKEN=ghp_your_personal_access_token
export TARGET_REPO_USERNAME=your_github_username

# ── Optional: AI-assisted risk scoring (impact-service) ──────────────────────
export AIQA_AI_ENABLED=true
export AIQA_AI_API_KEY=sk-...
export AIQA_AI_MODEL=gpt-4o-mini   # or gpt-4o for higher accuracy
```

> **Local only — no `.env` file needed.** These variables map to the `${VAR:}` placeholders in each service's `application.yaml`. You can also hard-code non-secret values directly in `application.yaml` for local development, but **never commit secrets to source control**.
>
> **Production / Docker deployments** — copy `.env.example` to `.env` at your `DEPLOY_PATH` and fill in all values. The CD workflow picks this file up automatically via `docker-compose.prod.yml`.

### 4 — Start the three services (separate terminals)

```bash
# Terminal 1 — pr-service on :8080
./mvnw spring-boot:run -pl pr-service

# Terminal 2 — impact-service on :8081
./mvnw spring-boot:run -pl impact-service

# Terminal 3 — strategy-service on :8082
./mvnw spring-boot:run -pl strategy-service
```

Watch for this line in each service log — it confirms the Kafka consumer is registered:
```
INFO  o.s.k.l.ConcurrentMessageListenerContainer - started
```

### 5 — Verify all services are up

```bash
curl http://localhost:8080/api/pr/health        # {"status":"UP","service":"PullRequestController"}
curl http://localhost:8081/api/impact/health    # {"status":"UP"}
curl http://localhost:8082/api/strategy/health  # {"status":"UP"}
```

### 6 — Trigger the full pipeline

```bash
curl -X POST http://localhost:8080/api/pr/demo
```

Expected response:
```json
{"status":"DEMO_TRIGGERED","prId":"PR-XXXXXXXX","diffsCount":3,...}
```

You should see logs flow through **all three** services:
- `pr-service` → `[FeatureUpdatesProducer] PR 'PR-...' sent → partition=X, offset=Y`
- `impact-service` → `[FeatureUpdatesConsumer] Analyzing PR '...'` then `[ImpactEngine]` logs
- `strategy-service` → `[ImpactResultsConsumer] Strategy decision for PR '...'` then `[StrategyAgent]` logs

### 7 — Submit a real PR payload

```bash
curl -X POST http://localhost:8080/api/pr/webhook \
  -H "Content-Type: application/json" \
  -d '{
    "title": "feat: add payment gateway",
    "author": "dev@example.com",
    "repositoryName": "payment-service",
    "sourceBranch": "feature/payments",
    "git_diff": []
  }'
```

### 8 — (Optional) Configure target test repo

Edit `strategy-service/src/main/resources/application.yaml`:

```yaml
aiqa:
  target-repo:
    url: "https://github.com/your-org/your-test-repo"
    branch: main
    auth:
      type: token
      token: ${TARGET_REPO_TOKEN:}
    modules:
      api: tests/api
      ui:  tests/ui
      mobile: tests/mobile
```

The service clones the repo on startup and reads `.github/agents/*.md` files for
coding conventions, which are embedded into every generated test file.

### 9 — Approve BDD scenarios (human gate)

After strategy-service generates BDD and raises a PR, approve it to trigger codegen:

```bash
curl -X POST http://localhost:8082/api/strategy/approve-bdd \
  -H "Content-Type: application/json" \
  -d '{"prId": "PR-XXXXXXXX", "scenarioId": "SC-XXXXXXXX", "approved": true}'
```

### 10 — Refresh repo context at runtime

```bash
curl -X POST http://localhost:8082/api/strategy/refresh-context
# Returns: {"status":"OK","api":"N tests, pkg=...","ui":"...","mobile":"..."}
```

### 11 — Shut down

```bash
# Ctrl+C in each Spring terminal, then:
docker compose down
```

---

## API Reference

### pr-service  `localhost:8080`

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/pr/webhook` | Receive a Git webhook payload |
| `POST` | `/api/pr/submit` | Manually submit a `PullRequest` JSON |
| `POST` | `/api/pr/demo` | Trigger pipeline with built-in sample PR |
| `GET` | `/api/pr/health` | Health check |

**Submit PR body:**
```json
{
  "title": "feat: Add payment gateway",
  "author": "dev@example.com",
  "repositoryName": "payment-service",
  "sourceBranch": "feature/payment",
  "targetBranch": "main",
  "jira_ids": ["PAY-123"],
  "rawDiffContent": "diff --git a/PaymentService.java ..."
}
```

---

### impact-service  `localhost:8081`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/impact/status` | Service health and status |
| `POST` | `/api/impact/analyze` | Analyze a raw diff synchronously |

**Analyze body:**
```json
{ "diff": "diff --git a/src/AuthController.java ..." }
```

**Analyze response:**
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

### strategy-service  `localhost:8082`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/strategy/status` | Service health and status |
| `POST` | `/api/strategy/approve-bdd` | Simulate BDD PR merge → triggers codegen |

**Approve BDD body:** the full `BddScenario` JSON logged by the strategy-service when it creates the BDD PR.

---

## Configuration

Each service has its own `application.yaml`. Common properties:

| Property | Default | Description |
|----------|---------|-------------|
| `spring.kafka.bootstrap-servers` | `localhost:9092` | Kafka broker address |
| `kafka.topics.feature-updates` | `FeatureUpdatesQueue` | PR ingestion topic |
| `kafka.topics.impact-results` | `ImpactResultsQueue` | Impact→Strategy handoff topic |
| `kafka.topics.test-scripts` | `TestScriptsQueue` | Codegen trigger topic |
| `aiqa.stabilization.max-retries` | `3` | Max fix-and-retry attempts |
| `aiqa.strategy.risk-threshold-high` | `0.7` | Score above which risk is HIGH |
| `aiqa.strategy.risk-threshold-medium` | `0.4` | Score above which risk is MEDIUM |

---

## Technology Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 25, Spring Boot 4.0.6 |
| Messaging | Apache Kafka via Spring Kafka 3.2 |
| Serialisation | Jackson (JSR-310 for dates) |
| Boilerplate reduction | Lombok |
| Validation | Jakarta Bean Validation |
| Test generation | RestAssured (API), Selenium (UI), Appium (Mobile) |
| Build | Maven 3.9 multi-module |
| Observability | Spring Boot Actuator |
| Unit tests | JUnit 5, Mockito, AssertJ, Spring MockMvc (standalone) |


## Running Tests Locally

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java | 25+ | `java -version` |
| Maven | 3.9+ | or use the `./mvnw` wrapper (included) |

> **No Kafka or running services needed** — all tests are pure unit tests that run offline.

### Run all tests (all 3 services at once)

```bash
./mvnw test
```

Expected output:

```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0   ← pr-service
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0   ← impact-service
[INFO] Tests run: 23, Failures: 0, Errors: 0, Skipped: 0   ← strategy-service
[INFO] BUILD SUCCESS
```

### Run tests for a single service

```bash
# pr-service only
./mvnw test -pl pr-service

# impact-service only
./mvnw test -pl impact-service

# strategy-service only
./mvnw test -pl strategy-service
```

### Run a specific test class

```bash
./mvnw test -pl impact-service -Dtest=RiskScorerTest
./mvnw test -pl pr-service -Dtest=PRControllerTest
./mvnw test -pl strategy-service -Dtest=StrategyAgentTest
```

### Test coverage breakdown

| Module | Test Class | Tests | What it covers |
|--------|-----------|-------|----------------|
| **pr-service** | `PRServiceTest` | 8 | enrichment, validation, Kafka publish |
| **pr-service** | `PRControllerTest` | 4 | `/webhook`, `/submit`, `/demo`, `/health` endpoints |
| **impact-service** | `GitDiffParserTest` | 7 | diff parsing, file types, extensions |
| **impact-service** | `RiskScorerTest` | 11 | level thresholds, factor weights, normalisation |
| **impact-service** | `TestCoverageServiceTest` | 6 | coverage ratio, NONE/GOOD/PARTIAL levels |
| **strategy-service** | `RepoContextTest` | 9 | helper methods, agent instructions header |
| **strategy-service** | `ApiTestRunnerTest` | 7 | code generation, repo context, agent header |
| **strategy-service** | `StrategyAgentTest` | 7 | SKIP/CREATE logic, fallback rules, coverage override |

**Total: 59 tests, 0 failures**

---
<!-- end of README -->


---

## CI/CD Pipelines

The `.github/workflows/` directory contains **8 workflow files** (2 reusable + 6 per-service):

```
.github/workflows/
├── _service-build.yml      ← Reusable: build, test, push Docker image to GHCR
├── _service-deploy.yml     ← Reusable: SSH deploy to a target server
│
├── pr-service-ci.yml       ← Triggers on pr-service/** or common/** changes
├── impact-service-ci.yml   ← Triggers on impact-service/** or common/** changes
├── strategy-service-ci.yml ← Triggers on strategy-service/** or common/** changes
│
├── pr-service-cd.yml       ← Deploys after CI succeeds (auto) or manually
├── impact-service-cd.yml   ← Same for impact-service
└── strategy-service-cd.yml ← Same for strategy-service
```

### CI flow per service (on push or PR)

```
push/PR to main|develop
      │
      │  path filter: {service}/** or common/**
      ▼
┌─────────────────────────────────────────────────────────┐
│  _service-build.yml (reusable)                          │
│                                                         │
│  1. Checkout + set up Java 25 (Temurin)                 │
│  2. Cache Maven .m2 (by pom.xml hash)                   │
│  3. mvn install -pl common                              │
│  4. mvn verify -pl {service}  (build + tests)           │
│  5. Upload Surefire test reports as artifacts           │
│  6. docker/metadata-action → tags: sha-xxxxx, latest,  │
│     branch name, pr-N                                   │
│  7. docker/build-push-action → build Docker image      │
│     Push to GHCR only on push (not on PRs)             │
└─────────────────────────────────────────────────────────┘
```

### CD flow per service (auto on main/develop, or manual)

```
CI workflow completes (conclusion == success)
      │
      │  branch == main   → environment = production
      │  branch == develop → environment = staging
      ▼
┌─────────────────────────────────────────────────────────┐
│  _service-deploy.yml (reusable)                         │
│                                                         │
│  Uses GitHub Environments (production / staging)        │
│  — set required reviewers in Settings → Environments   │
│    to add human approval gate for production            │
│                                                         │
│  1. SSH into DEPLOY_HOST as DEPLOY_USER                 │
│  2. docker login ghcr.io on the server                 │
│  3. docker pull {image}:{sha-tag}                      │
│  4. docker compose -f docker-compose.yml               │
│               -f docker-compose.prod.yml               │
│       up -d --no-deps --force-recreate {service}       │
│  5. Health check loop (30s max)                        │
│  6. Write job summary with deploy result               │
└─────────────────────────────────────────────────────────┘
```

### Image naming convention

```
ghcr.io/{owner}/{repo}/pr-service:sha-a1b2c3d
ghcr.io/{owner}/{repo}/pr-service:latest        ← only on main
ghcr.io/{owner}/{repo}/pr-service:develop       ← on develop branch
ghcr.io/{owner}/{repo}/pr-service:pr-42         ← on pull requests (not pushed)
```

### Required repository secrets

| Secret | Environment | Purpose |
|--------|-------------|---------|
| `GHCR_TOKEN` | (global) | Push/pull images to GitHub Container Registry |
| `DEPLOY_HOST` | production | Production server IP or hostname |
| `DEPLOY_USER` | production | SSH username on the production server |
| `DEPLOY_SSH_KEY` | production | SSH private key for production server |
| `STAGING_DEPLOY_HOST` | staging | Staging server IP or hostname |
| `STAGING_DEPLOY_USER` | staging | SSH username on the staging server |
| `STAGING_DEPLOY_SSH_KEY` | staging | SSH private key for staging server |

### Required repository variables

| Variable | Environment | Example |
|----------|-------------|---------|
| `DEPLOY_PATH` | production | `/opt/qa-isystem` |
| `DEPLOY_PATH` | staging | `/opt/qa-isystem-staging` |

> **Tip:** Set secrets in **Settings → Secrets and variables → Actions**.
> Scope production secrets to the `production` environment to enforce branch policies.

### Deployment server setup

```bash
# On the deployment server:
mkdir -p /opt/qa-isystem
cd /opt/qa-isystem

# Copy docker-compose.yml and docker-compose.prod.yml from this repo
# Create a .env file from the template
cp .env.example .env
nano .env   # fill in your values

# First time: pull Kafka + Zookeeper and start them
docker compose -f docker-compose.yml up -d zookeeper kafka

# Subsequent deploys are handled automatically by the CD workflow
```

