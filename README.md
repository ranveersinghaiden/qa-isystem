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
11. [AI-Native Feedback Loop](#ai-native-feedback-loop)
12. [Future Roadmap](#future-roadmap)

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
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │  strategy-service  :8082   Phases 2–7  (AI-native, self-improving)     │  │
│  │                                                                        │  │
│  │  StrategyAgent ──► SKIP / UPDATE_TESTS / CREATE_TESTS                  │  │
│  │       │              │             │                                   │  │
│  │  Fallback Rules  BddGenerator  handleUpdateTests                       │  │
│  │  (fullRegression  (AI + product    (inline delta)                      │  │
│  │   expandedScope)   expert context)     │                               │  │
│  │                       │               │                                │  │
│  │               ┌───────▼───────────────▼───────┐                        │  │
│  │               │     BDD Review PR on GitHub    │                       │  │
│  │               │  PrTracker.trackBdd(branch)    │                       │  │
│  │               └──────────────┬────────────────┘                        │  │
│  │                              │                                         │  │
│  │              ┌───────────────┴──────────────────────┐                  │  │
│  │              │  GitHub pull_request webhook         │                  │  │
│  │              │  POST /api/strategy/github-webhook   │                  │  │
│  │              └──────────┬──────────────┬────────────┘                  │  │
│  │                    MERGED            REJECTED                          │  │
│  │                         │                │                             │  │
│  │                         ▼                ▼                             │  │
│  │              TestScriptsQueue   PrFeedbackService                      │  │
│  │              (Kafka, codegen)   .handleBddRejection()                  │  │
│  │                    │              │ fetch review comments              │  │
│  │                    ▼              │ classify knowledge gap             │  │
│  │            CodegenService         │ update productExpert/ (if gap)     │  │
│  │          ┌──────┬──┴─────┐        │ re-generate BDD w/ AI              │  │
│  │       API Runner UI Mobile        │ create revised BDD PR              │  │
│  │          └──────┴──┬─────┘        └─────────────────────────────────   │  │
│  │           StabilizationLoop                                            │  │
│  │         (run → fail → fix, max 3×)                                     │  │
│  │                    │                                                   │  │
│  │               ┌────▼─────────────────────────┐                         │  │
│  │               │  Final Test PR on GitHub     │                         │  │
│  │               │  PrTracker.trackTest(branch) │                         │  │
│  │               └────────────┬─────────────────┘                         │  │
│  │                            │                                           │  │
│  │              ┌─────────────┴──────────────────────────┐                │  │
│  │              │  GitHub pull_request webhook           │                │  │
│  │              └───────────┬──────────────┬─────────────┘                │  │
│  │                     MERGED           REJECTED                          │  │
│  │                          │               │                             │  │
│  │               Pipeline complete   PrFeedbackService                    │  │
│  │               (tests are in repo) .handleTestRejection()               │  │
│  │                                     │ fetch review comments            │  │
│  │                                     │ classify knowledge gap           │  │
│  │                                     │ update productExpert/ (if gap)   │  │
│  │                                     │ re-generate test code w/ AI      │  │
│  │                                     │ create revised test PR           │  │
│  │                                     └────────────────────────────────  │  │
│  │                                                                        │  │
│  │  ── Product Knowledge ──────────────────────────────────────────────   │  │
│  │  productExpert/{product}/*.md  read at startup via RepoContextService  │  │
│  │  .aiqa/context.md              team-wide QA conventions                │  │
│  │  .github/agents/*.md           agent instruction files                 │  │
│  │  ───────────────────────────────────────────────────────────────────   │  │
│  │  OPENAI_API_KEY  → AI generation mode via OpenAI (default provider)    │  │
│  │  GITHUB_COPILOT_TOKEN → AI generation mode via GitHub Copilot API      │  │
│  │  AI_PROVIDER=copilot  → switch between providers at runtime            │  │
│  │  No credential set  → enhanced template fallback mode                  │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Module Overview

| Module | Port | Responsibility |
|--------|------|----------------|
| [`common`](common/README.md) | — | Shared models, Kafka config, GitHub client (`GitHubService`), AI client interface (`AiClient`) with two implementations (`OpenAiClient` for OpenAI/Azure/Ollama, `CopilotClient` for GitHub Copilot API), `AiClientConfig` (provider selection via `aiqa.ai.provider`), `PrTracker` (in-memory or Redis), repo context (`RepoContext`/`RepoContextService`), `FeedbackEvent` model |
| [`pr-service`](pr-service/README.md) | 8080 | PR ingestion — webhook receiver, validation, Kafka publisher |
| [`impact-service`](impact-service/README.md) | 8081 | Deterministic impact analysis — no AI or LLM |
| [`strategy-service`](strategy-service/README.md) | 8082 | Strategy decision, BDD generation (AI or template), GitHub webhook entry point — publishes `FeedbackEvent` to Kafka for rejected PRs |
| [`codegen-service`](codegen-service/README.md) | 8083 | Test code generation (API/UI/Mobile), stabilisation loop, final test PR creation |
| [`feedback-service`](feedback-service/README.md) | 8084 | AI-native feedback loop — consumes `FeedbackQueue`, re-generates rejected BDD/test PRs, updates product expert files |

---

## Kafka Topics

| Topic | Producer | Consumer | Payload |
|-------|----------|----------|---------|
| `FeatureUpdatesQueue` | pr-service | impact-service | `PullRequest` JSON |
| `ImpactResultsQueue` | impact-service | strategy-service | `ImpactEnvelope` JSON |
| `TestScriptsQueue` | strategy-service (approve-bdd / webhook) | codegen-service | `BddScenario` JSON |
| `TestResultsQueue` | codegen-service (stabilisation) | *(future consumers)* | `TestResult` JSON |
| `FeedbackQueue` | strategy-service (rejected PR webhook) | feedback-service | `FeedbackEvent` JSON |

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

Phase 4 — BDD Generation (strategy-service)  ← AI or template
 15. RepoContextService loads product knowledge from test repo at startup:
       productExpert/{product}/*.md  → per-product domain knowledge (PRODUCT.md, PATTERNS.md)
       .aiqa/context.md              → team-wide QA conventions
       .github/agents/*.md           → agent instruction files
 16. BddGenerator generates Gherkin scenarios:
       AI mode (OPENAI_API_KEY set): builds rich system prompt with product expert context,
         calls OpenAI-compatible API, parses Gherkin from response
       Template mode (no key): template-based happy path + error path + boundary outline
 17. GitHubService creates BDD branch + commits .feature file
     TestPrService opens BDD Review PR on GitHub (qa/bdd/{prId}-{short})
     PrTracker.trackBdd(branch, prNumber, scenario) registers PR for webhook lookup
 18. Human reviews the BDD PR on GitHub

Phase 5a — BDD PR MERGED → Code Generation (strategy-service)
     Two equivalent triggers for codegen:

     Path A — GitHub webhook (production):
 19a. Human merges the BDD Review PR on GitHub
      GitHub fires pull_request webhook to POST /api/strategy/github-webhook
      GitHubWebhookController verifies HMAC-SHA256 signature
      PrTracker.findByBranch() → PrRecord(type=BDD)
      Publishes BddScenario → TestScriptsQueue

     Path B — Manual endpoint (local dev / testing):
 19b. POST /api/strategy/approve-bdd  with the BddScenario JSON
      StrategyController publishes BddScenario → TestScriptsQueue

 20. TestScriptsConsumer → CodegenService routes by testType:
       API    → ApiTestRunner    (RestAssured + JUnit 5)
       UI     → UITestRunner     (Selenium + ChromeDriver)
       Mobile → MobileTestRunner (Appium + AndroidDriver)

Phase 5b — BDD PR REJECTED → Feedback & Re-generation (strategy-service)
 21. Human closes the BDD Review PR without merging
     GitHub fires pull_request webhook (action=closed, merged=false)
     PrTracker.findByBranch() → PrRecord(type=BDD)
     PrFeedbackService.handleBddRejection() runs on virtual thread:
       a. Fetch all review comments from GitHub (inline + PR-level)
       b. AI classifies feedback: KNOWLEDGE_GAP or STYLE_ONLY
       c. If KNOWLEDGE_GAP:
             Read productExpert/{product}/PRODUCT.md from repo
             AI appends new knowledge section to file
             Create PR: "[AI-QA] Product Expert Update: {product}"
       d. AI re-generates BDD scenarios incorporating the review feedback
       e. Create revised BDD PR → PrTracker.trackBdd() (loop repeats from step 18)

Phase 6 — Test Stabilisation (strategy-service)
 22. StabilizationLoop.execute() — up to 3 attempts:
       Attempt 1: add timeouts, retry-after config
       Attempt 2: add null guards, assertion retry wrapper
       Attempt 3: simplify to minimal smoke test
     TestExecutionEngine compiles generated Java with javax.tools.JavaCompiler
     and runs it via JUnit Platform Launcher + JupiterTestEngine
 23. On pass (any attempt): GitHubService creates final-test branch + file,
     TestPrService opens Final Test PR on GitHub (qa/tests/{prId}-{short})
     PrTracker.trackTest(branch, prNumber, script) registers PR for webhook lookup
 24. On 3× fail: ABANDONED — PR still raised for human review

Phase 7a — Final Test PR MERGED → Pipeline Complete
 25. Human merges the Final Test PR on GitHub
     GitHub fires pull_request webhook (action=closed, merged=true)
     PrTracker.findByBranch() → PrRecord(type=TEST) → response: TEST_PR_MERGED
     Tests are now committed in the repo — pipeline complete for this PR.

Phase 7b — Final Test PR REJECTED → Feedback & Re-generation (strategy-service)
 26. Human closes the Final Test PR without merging
     GitHub fires pull_request webhook (action=closed, merged=false)
     PrTracker.findByBranch() → PrRecord(type=TEST)
     PrFeedbackService.handleTestRejection() runs on virtual thread:
       a. Fetch all review comments from GitHub
       b. AI classifies feedback: KNOWLEDGE_GAP or STYLE_ONLY
       c. If KNOWLEDGE_GAP → same product expert update as step 21c
       d. AI re-generates test code incorporating review feedback
          (system prompt includes product expert context + existing test patterns)
       e. Create revised test code PR → PrTracker.trackTest() (loop repeats from step 23)

Self-Improving Product Expert Context
     Every feedback cycle can update productExpert/ files in the test repo:
     Cycle 1: AI generates generic tests  →  Human rejects: "missing auth flow knowledge"
              AI opens product expert update PR, human merges it
     Cycle 2: AI generates with auth flow context  →  Human requests style tweaks only
              No product expert update needed
     Cycle 3: AI output accepted with no changes  →  convergence achieved
```

---

## Quick Start

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java | 25+ | `java -version` |
| Maven | 3.9+ | or use the `./mvnw` wrapper (included) |
| Docker Desktop | any | must be running |

### 1 — Start Kafka and Redis

```bash
# From the project root (docker-compose.yml is here)
docker compose up -d

# Poll until all containers show "(healthy)" — takes ~30-45 s
docker compose ps
```

Expected:
```
NAME            STATUS
qa-zookeeper    Up (healthy)
qa-kafka        Up (healthy)
qa-redis        Up (healthy)
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
| `TARGET_REPO_URL` | strategy-service | URL of the test repository to clone for coverage context and to create BDD / test-code PRs in | *(none — coverage UNKNOWN, GitHub PR creation disabled)* |
| `TARGET_REPO_TOKEN` | strategy-service | GitHub PAT with `repo` scope. **Optional for local dev** — if blank, GitHubService automatically calls `git credential fill` which reads the token IntelliJ stored in osxkeychain. Required for CI/production (no credential helper available). If `TARGET_REPO_URL` is set but no token can be resolved, **the service refuses to start**. For GitHub org repos, the PAT must have SSO authorized for the org. | *(none — falls back to osxkeychain / IntelliJ auth)* |
| `TARGET_REPO_USERNAME` | strategy-service | GitHub username paired with the PAT | *(none)* |
| `GITHUB_WEBHOOK_SECRET` | strategy-service | HMAC-SHA256 secret matching the value set in GitHub Repository → Webhooks. Required for the webhook (BDD PR merge/reject, test PR merge/reject) to work correctly. Leave blank in local dev to skip signature verification. | *(none — verification skipped with a warning)* |
| `AI_PROVIDER` | strategy / codegen / feedback | Select AI backend: `openai` (default) or `copilot`. All three services share this setting. | `openai` |
| `OPENAI_API_KEY` | strategy / codegen / feedback | Required when `AI_PROVIDER=openai`. API key for OpenAI or any OpenAI-compatible provider (Azure, Ollama, GitHub Models). When absent and using the default OpenAI endpoint, the service falls back to enhanced template mode. | *(none — template mode)* |
| `OPENAI_BASE_URL` | strategy / codegen / feedback | Used when `AI_PROVIDER=openai`. Override for Azure (`https://…openai.azure.com`), Ollama (`http://localhost:11434`), or GitHub Models (`https://models.inference.ai.azure.com`). | `https://api.openai.com` |
| `OPENAI_MODEL` | strategy / codegen / feedback | Used when `AI_PROVIDER=openai`. Model name to use for completions. | `gpt-4o` |
| `GITHUB_COPILOT_TOKEN` | strategy / codegen / feedback | Required when `AI_PROVIDER=copilot`. GitHub personal access token, GitHub App token, or `GITHUB_TOKEN` (in Actions if Copilot is enabled for the repo). | *(none)* |
| `COPILOT_BASE_URL` | strategy / codegen / feedback | Used when `AI_PROVIDER=copilot`. Override for GitHub Models gateway. | `https://api.githubcopilot.com` |
| `COPILOT_MODEL` | strategy / codegen / feedback | Used when `AI_PROVIDER=copilot`. Model to request from Copilot API. | `gpt-4o` |
| `AIQA_AI_ENABLED` | impact-service | Set `true` to enable AI-assisted risk scoring in the gray zone | `false` |
| `AI_PROVIDER` | impact-service | Select AI backend for gray-zone evaluation: `openai` (default) or `copilot` | `openai` |
| `AIQA_AI_API_KEY` | impact-service | Used when `AI_PROVIDER=openai` — required when AI is enabled | *(none)* |
| `GITHUB_COPILOT_TOKEN` | impact-service | Used when `AI_PROVIDER=copilot` — required when AI is enabled | *(none)* |
| `AIQA_AI_MODEL` | impact-service | Model used for AI scoring | `gpt-4o-mini` |
| `KAFKA_HOST` | all (production only) | Kafka broker hostname advertised to external clients | `localhost` |

Export the variables you need in each terminal **before** starting a service:

```bash
# ── Optional: target test repository (strategy-service) ──────────────────────
export TARGET_REPO_URL=https://github.com/your-org/your-test-repo
export TARGET_REPO_TOKEN=ghp_your_personal_access_token
export TARGET_REPO_USERNAME=your_github_username

# ── Optional: AI generation mode ─────────────────────────────────────────────
# Choose ONE of the two provider options below:

# Option A — OpenAI (or any OpenAI-compatible endpoint)
export AI_PROVIDER=openai
export OPENAI_API_KEY=sk-...
export OPENAI_BASE_URL=https://api.openai.com  # or Azure/Ollama/GitHub Models endpoint
export OPENAI_MODEL=gpt-4o                     # or gpt-4o-mini for lower cost

# Option B — GitHub Copilot API
# export AI_PROVIDER=copilot
# export GITHUB_COPILOT_TOKEN=ghp_...          # GitHub token with Copilot access
# export COPILOT_MODEL=gpt-4o                  # optional

# ── Optional: AI-assisted risk scoring (impact-service) ──────────────────────
export AIQA_AI_ENABLED=true
# Uses the same AI_PROVIDER / AIQA_AI_API_KEY or GITHUB_COPILOT_TOKEN set above
```

> **Local only — no `.env` file needed.** These variables map to the `${VAR:}` placeholders in each service's `application.yaml`. You can also hard-code non-secret values directly in `application.yaml` for local development, but **never commit secrets to source control**.
>
> **Production / Docker deployments** — copy `.env.example` to `.env` at your `DEPLOY_PATH` and fill in all values. The CD workflow picks this file up automatically via `docker-compose.prod.yml`.

### 4 — Start the services (separate terminals)

```bash
# Terminal 1 — pr-service on :8080
./mvnw spring-boot:run -pl pr-service

# Terminal 2 — impact-service on :8081
./mvnw spring-boot:run -pl impact-service

# Terminal 3 — strategy-service on :8082  (strategy + BDD generation + webhook entry)
./mvnw spring-boot:run -pl strategy-service

# Terminal 4 — codegen-service on :8083  (code generation + test stabilisation)
./mvnw spring-boot:run -pl codegen-service

# Terminal 5 — feedback-service on :8084  (PR rejection feedback loop)
./mvnw spring-boot:run -pl feedback-service
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
curl http://localhost:8083/api/codegen/health   # {"status":"UP"}
curl http://localhost:8084/api/feedback/health  # {"status":"UP"}
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
| `POST` | `/api/strategy/approve-bdd` | Manually trigger codegen from a BDD scenario (local dev / testing gate) |
| `POST` | `/api/strategy/github-webhook` | GitHub `pull_request` webhook — auto-triggers codegen on BDD PR merge |
| `POST` | `/api/strategy/refresh-context` | Re-pull target test repo and refresh coverage/context cache |

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

### Run all tests (all 6 services at once)

```bash
./mvnw test
```

Expected output:

```
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0   ← pr-service
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0   ← impact-service
[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0   ← strategy-service
[INFO] Tests run:  7, Failures: 0, Errors: 0, Skipped: 0   ← codegen-service
[INFO] BUILD SUCCESS
```

**Total: 78 tests, 0 failures** (feedback-service has no unit tests yet — they are integration tests requiring Kafka)

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
| **pr-service** | `PRServiceTest` | 9 | enrichment, validation, Kafka publish (via capturing test double) |
| **pr-service** | `PRControllerTest` | 4 | `/webhook`, `/submit`, `/demo`, `/health` endpoints (via fixed PRService test double) |
| **pr-service** | `PRControllerAdviceTest` | 5 | global exception handler, error response shapes |
| **impact-service** | `GitDiffParserTest` | 7 | diff parsing, file types, extensions |
| **impact-service** | `RiskScorerTest` | 11 | level thresholds, factor weights, normalisation |
| **impact-service** | `TestCoverageServiceTest` | 9 | coverage ratio, NONE/GOOD/PARTIAL levels |
| **strategy-service** | `RepoContextTest` | 9 | helper methods, agent instructions header |
| **strategy-service** | `ApiTestRunnerTest` | 7 | code generation, repo context, agent header |
| **strategy-service** | `StrategyAgentTest` | 10 | SKIP/CREATE logic, fallback rules, coverage override (no Mockito — real test doubles) |
| **codegen-service** | `ApiTestRunnerTest` | 7 | code generation from codegen-service module |

**Total: 78 tests, 0 failures — no Mockito in any test**

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
├── pr-service-ci.yml       ← Triggers on pr-service/**, common/**, .mvn/**, pom.xml
├── impact-service-ci.yml   ← Triggers on impact-service/**, common/**, .mvn/**, pom.xml
├── strategy-service-ci.yml ← Triggers on strategy-service/**, common/**, .mvn/**, pom.xml
│
├── pr-service-cd.yml       ← Deploys after CI succeeds (auto) or manually
├── impact-service-cd.yml   ← Same for impact-service
└── strategy-service-cd.yml ← Same for strategy-service
```

> **`.mvn/` and `pom.xml` in all path triggers.** The project ships a `.mvn/settings.xml`
> (empty mirrors) and `.mvn/maven.config` (`-s .mvn/settings.xml`) that route builds to
> Maven Central. Any change to these files re-triggers CI for all three services.

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

### Required `.env` variables on the deployment server

These are read by `docker-compose.prod.yml` on startup. Copy `.env.example` to `.env` and fill in your values.

| Variable | Service | Required | Purpose |
|----------|---------|----------|---------|
| `TARGET_REPO_URL` | strategy-service | Yes (for PRs) | HTTPS URL of the target test repository |
| `TARGET_REPO_TOKEN` | strategy-service | Yes (for PRs) | GitHub PAT with `repo` scope; `git credential fill` is not available in Docker. For GitHub org repos, the PAT must have SSO authorized for the org. |
| `TARGET_REPO_USERNAME` | strategy-service | Yes (for PRs) | GitHub username paired with the PAT |
| `GITHUB_WEBHOOK_SECRET` | strategy-service | Yes (recommended) | HMAC-SHA256 secret matching the GitHub webhook setting — prevents unauthenticated codegen/feedback triggers |
| `AI_PROVIDER` | strategy / codegen / feedback / impact | No | AI backend: `openai` (default) or `copilot`. Controls which credential below is used. |
| `OPENAI_API_KEY` | strategy / codegen / feedback | When `AI_PROVIDER=openai` | OpenAI-compatible API key. Enables AI-driven BDD generation, test code generation, and feedback classification. Without it, the service uses enhanced template mode. |
| `OPENAI_BASE_URL` | strategy / codegen / feedback | No | Override for Azure (`https://…openai.azure.com`), Ollama (`http://localhost:11434`), or GitHub Models (`https://models.inference.ai.azure.com`). Default: `https://api.openai.com` |
| `OPENAI_MODEL` | strategy / codegen / feedback | No | Model name. Default: `gpt-4o` |
| `GITHUB_COPILOT_TOKEN` | strategy / codegen / feedback | When `AI_PROVIDER=copilot` | GitHub token with Copilot access (PAT, App token, or `GITHUB_TOKEN` if Copilot is enabled for the repo) |
| `COPILOT_BASE_URL` | strategy / codegen / feedback | No | Override Copilot endpoint. Default: `https://api.githubcopilot.com` |
| `COPILOT_MODEL` | strategy / codegen / feedback | No | Copilot model. Default: `gpt-4o` |
| `AIQA_AI_ENABLED` | impact-service | No | Set `true` to enable AI-assisted risk scoring |
| `AIQA_AI_API_KEY` | impact-service | If AI enabled and `AI_PROVIDER=openai` | OpenAI-compatible API key for gray-zone risk evaluation |
| `GITHUB_COPILOT_TOKEN` | impact-service | If AI enabled and `AI_PROVIDER=copilot` | GitHub token with Copilot access for gray-zone risk evaluation |

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

## AI-Native Feedback Loop

> **Status:** Implemented in `strategy-service`. No separate feedback service required.

### How It Works

When a human rejects a QA-generated PR (BDD scenarios or test code), the system automatically:

1. **Fetches** all review comments from the GitHub PR
2. **Classifies** the feedback via AI:
   - `KNOWLEDGE_GAP: <description>` — reviewer revealed domain knowledge the AI lacked
   - `STYLE_ONLY: <reason>` — structural or stylistic request, no knowledge gap
3. **Updates product expert files** (if `KNOWLEDGE_GAP`):
   - Reads `productExpert/{product}/PRODUCT.md` from the test repo
   - AI appends a new knowledge section documenting what was missing
   - Opens a PR titled `[AI-QA] Product Expert Update: {product}` for human review
4. **Re-generates** the rejected content with the feedback as additional context
5. **Creates a revised PR** — registered in `PrTracker` so the loop can repeat

This loop applies to **both PR types**:

| PR Type | Trigger | Feedback handler | Revised PR title |
|---------|---------|------------------|-----------------|
| BDD scenarios | `qa/bdd/*` PR rejected | `PrFeedbackService.handleBddRejection()` | `[AI-QA] Revised BDD Scenarios for PR: {id}` |
| Test code | `qa/tests/*` PR rejected | `PrFeedbackService.handleTestRejection()` | `[AI-QA] Revised Tests for PR: {id}` |

### Self-Improving Product Expert Context

The `productExpert/` directory in the test repo acts as a growing, human-curated knowledge base:

```
{test-repo}/
  productExpert/
    payments/
      PRODUCT.md      ← domain flows, business rules, known edge cases
      PATTERNS.md     ← preferred assertion patterns, test structure
    auth/
      PRODUCT.md
  .aiqa/
    context.md        ← team-wide QA conventions
  .github/
    agents/
      api-conventions.md   ← agent instruction files (read by RepoContextService)
```

Each rejection cycle can enrich these files, so subsequent generation runs start with richer context:

```
Cycle 1  AI generates generic tests  →  Human rejects: "missing OAuth token refresh flow"
         AI opens Product Expert Update PR documenting the OAuth flow
         Human reviews and merges the update PR

Cycle 2  AI generates with OAuth context  →  Human requests minor style changes only
         No product expert update — STYLE_ONLY feedback handled inline

Cycle 3  AI output accepted with no changes → convergence achieved for this component
```

### Async Execution

Feedback handling runs on a **virtual thread** (`Thread.ofVirtual()`) so the GitHub webhook HTTP response is returned immediately (within GitHub's 10-second timeout). The re-generation work happens in the background.

---

## Future Roadmap

### AST-Level Diff Feedback (not yet implemented)

An optional future enhancement would add structural diff analysis for merged PRs:

- **For BDD scenarios:** parse both AI-generated and human-merged `.feature` files with a Gherkin parser; compare at scenario/step/tag level
- **For Java test code:** parse AST (JavaParser); compare at method/assertion/annotation level
- **Output:** `FeedbackDelta` records tagged with labels (`WRONG_ASSERTION`, `MISSING_CONTEXT`, `WRONG_ENDPOINT`, `STYLE_PREFERENCE`, `COVERAGE_GAP`)
- **Result:** automatic commits to `.qa-agent/instructions.md` in the test repo — a growing rule set derived from real human corrections

This would complement the existing rejection-based feedback loop with learning from accepted-but-edited PRs.

---

## Cost Saving Decisions - CLI vs API

┌─────────────────────────────────────────────────────────────────────┐
│              COPILOT CLI vs DIRECT API COMPARISON                   │
├─────────────────────────┬───────────────────────────────────────────┤
│ Factor                  │ Assessment                                │
├─────────────────────────┼───────────────────────────────────────────┤
│ Cost Model (2024/2025)  │ Copilot: flat seat license (~$19-39/mo)   │
│                         │ API: pay-per-usage (can spike)            │
├─────────────────────────┼───────────────────────────────────────────┤
│ Context Window          │ Copilot CLI: has repo context built-in    │
│                         │ API: you manage context yourself          │
├─────────────────────────┼───────────────────────────────────────────┤
│ Quality (your finding)  │ Copilot CLI: ✅ Works great with repo     │
│                         │ context + product context                 │
├─────────────────────────┼───────────────────────────────────────────┤
│ Rate Limits             │ Copilot: softer limits on seat license    │
│                         │ API: hard token/RPM limits                │
├─────────────────────────┼───────────────────────────────────────────┤
│ Control                 │ API: full control over prompts/models     │
│                         │ Copilot CLI: opaque, GitHub-controlled    │
├─────────────────────────┼───────────────────────────────────────────┤
│ Auditability            │ API: full request/response logging        │
│                         │ Copilot CLI: limited visibility           │
├─────────────────────────┼───────────────────────────────────────────┤
│ CI/CD Integration       │ Both work, CLI needs GH Actions setup     │
├─────────────────────────┼───────────────────────────────────────────┤
│ Repo-awareness          │ Copilot CLI: native                       │
│                         │ API: you build RAG/context pipeline       │
└─────────────────────────┴───────────────────────────────────────────┘