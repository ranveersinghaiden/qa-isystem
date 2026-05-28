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
11. [Future Roadmap](#future-roadmap)
    - [Feedback Service — Self-Improving Agents](#feedback-service--self-improving-agents)

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
│  │                       │  (merge triggers feedback loop)│                  │
│  └────────────────────────────────────────────────────────┘                  │
│                                                                              │
│  ┌──────────────────────────────────────────────────────┐                    │
│  │  feedback-service  :8083   Phase 7 (PLANNED)         │                    │
│  │                                                      │                    │
│  │  On BDD PR merge:  AI blob vs merged blob            │                    │
│  │  On Test PR merge: AI blob vs merged blob            │                    │
│  │       │                                              │                    │
│  │  FeedbackAnalyser (Gherkin + AST diff)               │                    │
│  │       │                                              │                    │
│  │  FeedbackClassifier (label each delta)               │                    │
│  │       │                                              │                    │
│  │  ContextWriter → commits .qa-agent/instructions.md  │                    │
│  │                  back to the test repository         │                    │
│  │                  (improves next generation cycle)    │                    │
│  └──────────────────────────────────────────────────────┘                    │
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
| `TestScriptsQueue` | strategy-service (approve-bdd / webhook) | strategy-service (codegen) | `BddScenario` JSON |
| `TestResultsQueue` | strategy-service (stabilisation) | *(future consumers)* | `TestResult` JSON |
| `FeedbackQueue` | feedback-service *(planned)* | feedback-service *(planned)* | `FeedbackBatch` JSON |

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

Phase 7 — Feedback Loop (feedback-service)  ← PLANNED
 23. Human merges BDD PR or Final Test PR on GitHub
 24. GitHub webhook fires to feedback-service
 25. FeedbackAnalyser diffs AI-generated blob vs human-merged blob
       — Gherkin structural diff for BDD scenarios
       — AST diff (JavaParser) for test code
 26. FeedbackClassifier labels each delta:
       WRONG_ASSERTION | MISSING_CONTEXT | WRONG_TEST_TYPE |
       WRONG_ENDPOINT  | STYLE_PREFERENCE | COVERAGE_GAP
 27. ContextWriter commits .qa-agent/instructions.md to test repo
       — growing rule set derived from real human corrections
       — read by RepoContextService on next generation cycle
 28. Next generation run starts with richer context → converges toward
       zero-edit AI output over time
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

# Subsequent deploys are handled automatically by the CD workflow
docker compose -f docker-compose.yml up -d zookeeper kafka

# Subsequent deploys are handled automatically by the CD workflow
```

---

## Future Roadmap

### Feedback Service — Self-Improving Agents

> **Status:** Planned — not yet implemented.

#### Motivation

Every time a human reviews a BDD PR or a final test-code PR, they may edit, rewrite, or
reject parts of what the AI generated.  Those edits are the most valuable signal in the
entire pipeline because they reveal exactly where the AI's understanding of the codebase
diverges from the team's intent.  Currently that signal is thrown away.

The **Feedback Service** closes the loop: it captures what the human changed, reasons
about *why* those changes were needed, and writes updated context documents back to the
test repository so that the next generation run is measurably better.

---

#### How It Will Work

```
BDD PR (AI-generated)                Final Test PR (AI-generated)
        │                                        │
  Human edits & merges                   Human edits & merges
        │                                        │
        └──────────────────┬─────────────────────┘
                           │
                 GitHub pull_request webhook
                 (action=closed, merged=true)
                           │
                           ▼
               ┌────────────────────────┐
               │    feedback-service    │
               │                        │
               │  1. Fetch both versions│
               │     (AI blob vs merged │
               │      blob via GH API)  │
               │                        │
               │  2. Diff analyser      │
               │     — which scenarios  │
               │       were removed?    │
               │     — which steps were │
               │       rewritten?       │
               │     — which assertions │
               │       were tightened?  │
               │                        │
               │  3. Pattern classifier │
               │     tag each delta with│
               │     a feedback label:  │
               │     WRONG_ASSERTION,   │
               │     MISSING_CONTEXT,   │
               │     WRONG_TEST_TYPE,   │
               │     WRONG_ENDPOINT,    │
               │     STYLE_PREFERENCE   │
               │                        │
               │  4. Context writer     │
               │     commit updated     │
               │     agent instruction  │
               │     files to test repo │
               └────────────────────────┘
```

---

#### Detailed Design

##### Step 1 — Capture Both Versions

When a QA-generated PR (BDD or final test code) is merged, the `GitHubWebhookController`
already fires.  It will be extended to also record:

| Field | Source |
|-------|--------|
| `ai_blob` | The file content at the **head of the QA branch** (what the AI committed) |
| `human_blob` | The file content at **merge commit** (what the human actually merged) |
| `pr_type` | `BDD_REVIEW` or `FINAL_TEST_CODE` |
| `source_pr_id` | The original feature PR that triggered generation |
| `component_names` | Which components the scenarios/tests cover |

Both blobs are fetched via `GET /repos/{owner}/{repo}/contents/{path}?ref={sha}`.

##### Step 2 — Diff Analysis

A `FeedbackAnalyser` computes the structural diff between the two blobs:

- **For BDD scenarios (`.feature` files):** parse both with a Gherkin parser; compare at
  the scenario, step, and tag level — not just line-by-line.
- **For Java test code:** parse the AST (using JavaParser); compare at the method,
  assertion, and annotation level to detect meaningful semantic changes rather than
  whitespace noise.

Each delta is represented as a `FeedbackDelta`:

```
FeedbackDelta {
  deltaType:   SCENARIO_REMOVED | STEP_REWRITTEN | ASSERTION_CHANGED |
               TAG_ADDED | ENDPOINT_CORRECTED | DEPENDENCY_ADDED | ...
  aiVersion:   String   // what AI produced
  humanVersion: String  // what the human replaced it with
  component:   String   // affected component / feature area
  confidence:  double   // 0..1 — how consistently this pattern appears
}
```

##### Step 3 — Pattern Classification

The `FeedbackClassifier` groups deltas into labelled patterns:

| Label | Meaning | Example |
|-------|---------|---------|
| `WRONG_ASSERTION` | AI asserted the wrong HTTP status or field | AI: `status 200`, Human: `status 201` for a create endpoint |
| `MISSING_CONTEXT` | AI did not know about an existing base class or helper | Human added `extends BaseApiTest` |
| `WRONG_TEST_TYPE` | AI generated API test for a UI component | Human changed framework to Selenium |
| `WRONG_ENDPOINT` | AI used a placeholder path that doesn't exist | Human corrected `/api/v1/foo` |
| `STYLE_PREFERENCE` | Naming, formatting, or structural choice | Human renamed `shouldReturnFoo` to `getFoo_returnsExpected` |
| `COVERAGE_GAP` | Human added scenarios AI didn't generate | Extra edge-case scenario added |

##### Step 4 — Context Writer

The `ContextWriter` translates classified patterns into **agent instruction files** committed
directly to the test repository:

```
{test-repo}/
  .qa-agent/
    instructions.md          ← natural-language rules the agent must follow
    patterns/
      wrong-assertions.md    ← list of corrected assertion examples
      naming-conventions.md  ← inferred from STYLE_PREFERENCE deltas
      base-classes.md        ← discovered base test classes
      endpoint-map.md        ← verified real endpoint paths
    history/
      feedback-YYYY-MM-DD.json  ← raw delta log for audit / debugging
```

`instructions.md` is a growing, curated file that `RepoContextService` already reads
(it looks for `AGENT_INSTRUCTIONS` markers in the scanned repo).  The Feedback Service
appends new rules derived from each batch of human corrections, so each generation cycle
starts with richer context than the last.

---

#### New Kafka Topic

| Topic | Producer | Consumer | Payload |
|-------|----------|----------|---------|
| `FeedbackQueue` | feedback-service | feedback-service (internal batch processor) | `FeedbackBatch` JSON |

The topic decouples the fast webhook handler (capture the blobs) from the slower analysis
and context-write operations, and provides a natural audit log of every feedback event.

---

#### New Service: `feedback-service`

| Attribute | Value |
|-----------|-------|
| Port | `8083` |
| Trigger | GitHub `pull_request` webhook — merged QA PRs only |
| Reads from | Test repository (both blobs via GitHub API) |
| Writes to | Test repository (`.qa-agent/` context files via GitHub API) |
| Depends on | `TARGET_REPO_TOKEN`, `GITHUB_WEBHOOK_SECRET` |

It shares the `common` module for models and Kafka config, and will reuse `GitHubService`
(move it to `common`) for all repository I/O.

---

#### Impact on Generation Quality

Each feedback cycle tightens the gap between AI output and human expectation:

```
Cycle 1  AI generates generic RestAssured skeleton
         Human corrects: adds correct base class, fixes endpoint

         → ContextWriter appends to base-classes.md and endpoint-map.md

Cycle 2  AI reads updated context, generates with the correct base class
         Human makes only minor naming adjustments

         → ContextWriter appends naming convention rule

Cycle 3  AI output is accepted with no changes → zero deltas
         No context update needed — convergence achieved for this component
```

Over time, the `.qa-agent/instructions.md` file in the test repository becomes a
living specification of the team's test-writing standards, derived entirely from real
human corrections rather than being hand-authored upfront.

---

#### Implementation Checklist

- [ ] Extend `GitHubWebhookController` to emit a `FeedbackEvent` when a QA PR merges
- [ ] Create `feedback-service` module (Spring Boot, port 8083)
- [ ] Implement `FeedbackAnalyser` — Gherkin + JavaParser structural diff
- [ ] Implement `FeedbackClassifier` — tag each delta with a label
- [ ] Implement `ContextWriter` — commit `.qa-agent/` files to test repo
- [ ] Move `GitHubService` to `common` module (shared by strategy + feedback)
- [ ] Add `FeedbackQueue` Kafka topic to `KafkaConfig`
- [ ] Update `RepoContextService` to read `.qa-agent/instructions.md`
- [ ] Add `feedback-service` to `docker-compose.yml` and `docker-compose.prod.yml`
- [ ] Write unit tests for `FeedbackAnalyser` pattern detection
- [ ] Write integration test: generate → human edit → feedback → regenerate, assert improved output


