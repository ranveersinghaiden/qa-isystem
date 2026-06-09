# QA-ISystem — Autonomous AI-Driven QA Pipeline

Watches pull requests → analyses impact → decides test strategy → generates BDD scenarios →
produces executable test code → stabilises failing tests → self-improves from reviewer feedback.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Module Overview](#module-overview)
3. [Pipeline Flow](#pipeline-flow)
4. [Quick Start](#quick-start)
5. [API Reference](#api-reference)
6. [AI Provider Configuration](#ai-provider-configuration)
7. [Cost Optimisation](#cost-optimisation)
8. [CI/CD Pipelines](#cicd-pipelines)
9. [AI Feedback Loop](#ai-feedback-loop)
10. [Running Tests](#running-tests)
11. [Future Roadmap](#future-roadmap)

---

## Architecture

```
Git / Webhook
     │ POST /api/pr/webhook
     ▼
┌──────────────┐
│  pr-service  │ :8080  Validates & enriches PR
└──────┬───────┘
       │ FeatureUpdatesQueue (Kafka)
       ▼
┌───────────────────────────────────────────────┐
│  impact-service  :8081   NO AI                │
│  GitDiffParser → DependencyGraph              │
│  ChangeTypeDetector → RiskScorer              │
│  TestCoverageService → ImpactEnvelope         │
└──────┬────────────────────────────────────────┘
       │ ImpactResultsQueue (Kafka)
       ▼
┌──────────────────────────────────────────────────────────────────┐
│  strategy-service  :8082   AI-native, self-improving             │
│                                                                  │
│  ① AiCallGate   — rule-based gate (40-60% of AI calls skipped)  │
│  ② StrategyAgent — SKIP / UPDATE_TESTS / CREATE_TESTS            │
│  ③ BddGenerator  — PromptResponseCache → AI or template         │
│  ④ GitHub PR     — BDD review PR (qa/bdd/*)                     │
│                                                                  │
│  On BDD PR merge  → TestScriptsQueue → codegen-service          │
│  On BDD PR reject → PrFeedbackService → revised BDD PR         │
│                                                                  │
│  CodegenService  — API / UI / Mobile test runners               │
│  StabilizationLoop — run→fail→fix up to 3×                      │
│  Final Test PR   — (qa/tests/*)                                  │
│                                                                  │
│  AiCostMonitor   — GET /api/qa/cost/report                      │
│                                                                  │
│  AI providers (aiqa.ai.provider):                               │
│    copilot-cli (default) — gh api, flat seat licence             │
│    copilot               — Copilot REST API + token              │
│    openai                — any OpenAI-compatible endpoint        │
│    (none configured)     — enhanced template fallback            │
└──────────────────────────────────────────────────────────────────┘
       │ FeedbackQueue (Kafka)
       ▼
┌──────────────────────────────────────────┐
│  feedback-service  :8084                 │
│  Re-generates rejected PRs, updates      │
│  productExpert/ knowledge files          │
└──────────────────────────────────────────┘
```

---

## Module Overview

| Module | Port | Responsibility |
|--------|------|----------------|
| `common` | — | Shared models, Kafka/Redis config, `AiClient` interface + 3 implementations (`CopilotCliClient` · `CopilotClient` · `OpenAiClient`), `AiClientConfig`, `GitHubService`, `RepoContextService`, `PrTracker` |
| `pr-service` | 8080 | Webhook receiver, PR validation, Kafka publisher |
| `impact-service` | 8081 | Deterministic impact analysis — no AI |
| `strategy-service` | 8082 | `AiCallGate` · `StrategyAgent` · `BddGenerator` · `PromptResponseCache` · `AiCostMonitor` · GitHub webhook · codegen trigger |
| `codegen-service` | 8083 | Test code generation (API/UI/Mobile), stabilisation loop |
| `feedback-service` | 8084 | Kafka consumer for rejected PRs, re-generation, product expert updates |

---

## Kafka Topics

| Topic | Producer | Consumer | Payload |
|-------|----------|----------|---------|
| `FeatureUpdatesQueue` | pr-service | impact-service | `PullRequest` — includes `title`, `products` |
| `ImpactResultsQueue` | impact-service | strategy-service | `ImpactEnvelope` — carries `prTitle` copied from `PullRequest.title` |
| `TestScriptsQueue` | strategy-service | codegen-service | `BddScenario` — carries `prTitle` for GitHub PR naming |
| `TestResultsQueue` | codegen-service | *(future)* | `TestResult` |
| `FeedbackQueue` | strategy-service | feedback-service | `FeedbackEvent` — wraps `BddScenario` or `TestScript`, both carrying `prTitle` |

> **`prTitle` propagation:** `PullRequest.title` → `ImpactEnvelope.prTitle` → `BddScenario.prTitle` → `TestScript.prTitle`.
> All QA-generated GitHub PR titles (initial + revised) derive from this field, falling back to `"… for PR: {prId}"` when absent.

---

## Pipeline Flow

```
PR webhook → pr-service → FeatureUpdatesQueue
                                │  PullRequest {prId, title, products, raw_diff, …}
                         impact-service (no AI)
                         GitDiffParser · DependencyGraph
                         ChangeTypeDetector · RiskScorer
                         → ImpactEnvelope {prId, prTitle, …} → ImpactResultsQueue
                                │  prTitle copied from PullRequest.title
                         strategy-service
                         ① AiCallGate.evaluate(envelope)
                              SKIP          → done
                              RULE_HANDLED  → template BDD, no AI call
                              NEEDS_AI      → ③
                         ② StrategyAgent.decide() (E2E coverage + risk rules)
                         ③ BddGenerator
                              check PromptResponseCache
                              HIT  → reuse cached gherkin, no AI call
                              MISS → AiClient.complete() → cache response
                         ④ TestPrService → BDD Review PR (qa/bdd/*)
                              title: "[AI-QA] {prTitle}"  (e.g. "[AI-QA] VSF-3670: …")
                                │
                    ┌───────────┴────────────┐
                 MERGED                  REJECTED
                    │                       │
             TestScriptsQueue       PrFeedbackService
                    │  BddScenario          (re-gen + product expert update)
                    │  {prId, prTitle, …}    revised title: "[AI-QA] Revised: {prTitle}"
             codegen-service
             API/UI/Mobile runner
             StabilizationLoop (max 3×)
             → Final Test PR (qa/tests/*)
                  title: "✅ [AI-QA] {prTitle}"
```

---

## Quick Start

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Java | 25+ | `java -version` |
| Maven | 3.9+ | or `./mvnw` (included) |
| Docker Desktop | any | must be running |
| gh CLI | latest | `brew install gh` — required for `copilot-cli` provider (the default) |

### 1 — Start infrastructure

```bash
docker compose up -d
docker compose ps   # wait for qa-kafka, qa-redis to show (healthy)
```

> Optional — Kafka UI at `http://localhost:8090`: `docker compose --profile debug up -d`

### 2 — Build

```bash
./mvnw clean package -DskipTests
```

### 3 — Configure (optional)

All services start with sensible defaults — no env vars required for local testing.

**AI Provider** (pick one):

```bash
# Option A — Copilot CLI (recommended, flat seat licence, no token needed)
gh auth login                    # once — stores credentials in system keychain
# AI_PROVIDER=copilot-cli is already the default; nothing else to set

# Option B — Copilot REST API
export AI_PROVIDER=copilot
export GITHUB_COPILOT_TOKEN=ghp_...

# Option C — OpenAI / Azure / Ollama / GitHub Models
export AI_PROVIDER=openai
export OPENAI_API_KEY=sk-...
export OPENAI_BASE_URL=https://api.openai.com
export OPENAI_MODEL=gpt-4o
```

**GitHub PR creation** (strategy-service):

```bash
export TARGET_REPO_URL=https://github.com/your-org/your-test-repo
export TARGET_REPO_TOKEN=ghp_...       # PAT with repo scope
export TARGET_REPO_USERNAME=your_username
export GITHUB_WEBHOOK_SECRET=...       # HMAC secret matching GitHub webhook settings
```

### 4 — Start services (separate terminals)

**Option A — automated script (recommended)**

```bash
# Full start: Docker infra + build + all 5 services + health checks
./scripts/start-local.sh

# With optional GitHub PR creation
TARGET_REPO_URL=https://github.com/your-org/your-test-repo \
TARGET_REPO_TOKEN=ghp_... \
TARGET_REPO_USERNAME=your_username \
./scripts/start-local.sh

# Skip rebuild (use existing JARs)
./scripts/start-local.sh --skip-build

# Clean start (clears stale ZooKeeper state — use after NodeExistsException)
./scripts/start-local.sh --fresh

# Include Kafka UI at http://localhost:8090
./scripts/start-local.sh --with-kafka-ui

# Stop everything
./scripts/start-local.sh --stop
```

The script performs these pre-flight checks before starting anything:
- Java 25+ on PATH
- Maven wrapper (`mvnw`) present
- Docker daemon running + `docker compose` v2 available
- `gh` CLI installed and authenticated (warns and continues if not — AI falls back to templates)
- All 7 ports free (8080-8084, 9092, 6379) — exits with port list if any are taken
- All JARs present when `--skip-build` is passed

Service logs land in `logs/{service-name}.log`. Each service is health-polled up to 60s after launch.

**Option B — manual**

```bash
./mvnw spring-boot:run -pl pr-service        # :8080
./mvnw spring-boot:run -pl impact-service    # :8081
./mvnw spring-boot:run -pl strategy-service  # :8082
./mvnw spring-boot:run -pl codegen-service   # :8083
./mvnw spring-boot:run -pl feedback-service  # :8084
```


### 5 — Trigger the pipeline

```bash
# Demo (built-in sample PR)
curl -X POST http://localhost:8080/api/pr/demo

# Real PR payload
curl -X POST http://localhost:8080/api/pr/submit \
  -H "Content-Type: application/json" \
  -d '{
    "title": "feat: add payment gateway",
    "author": "dev@example.com",
    "repositoryName": "payment-service",
    "sourceBranch": "feature/payments",
    "products": "payments, auth",
    "rawDiffContent": "diff --git a/PaymentService.java ..."
  }'

# From file
curl -X POST http://localhost:8080/api/pr/submit \
  -H "Content-Type: application/json" \
  -d @pr-webhook-sample.json
```

### 6 — Useful runtime commands

```bash
# Manually trigger codegen after BDD PR is generated (local dev gate)
curl -X POST http://localhost:8082/api/strategy/approve-bdd \
  -H "Content-Type: application/json" \
  -d '{...BddScenario JSON...}'

# AI cost savings report
curl http://localhost:8082/api/qa/cost/report

# Re-pull test repo and refresh context cache
curl -X POST http://localhost:8082/api/strategy/refresh-context
```

---

## API Reference

### pr-service `:8080`

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/pr/webhook` | GitHub webhook payload |
| `POST` | `/api/pr/submit` | Manual `PullRequest` JSON |
| `POST` | `/api/pr/demo` | Built-in demo pipeline trigger |
| `GET`  | `/api/pr/health` | Health check |

#### `PullRequest` payload fields

| Field | Type | Notes |
|-------|------|-------|
| `title` | `String` | **Required.** Used as the GitHub PR title — e.g. `"[AI-QA] VSF-3670: …"` |
| `author` | `String` | **Required.** |
| `repositoryName` | `String` | **Required.** |
| `raw_diff` | `String` | Unified diff string; parsed into structured `GitDiff` objects by pr-service |
| `products` | `String` \| `String[]` | Optional. Products affected — accepts JSON array `["payments","auth"]` **or** comma-separated string `"payments, auth"`. Normalised to `List<String>`. |
| `jira_ids` | `String[]` | Optional. Linked Jira ticket IDs |
| `pr_id` | `String` | Optional. Auto-generated as `PR-{UUID8}` when absent |
| `repo_owner` / `owner` / `org` | `String` | Optional. Org/user for GitHub diff fetching |

### impact-service `:8081`

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/api/impact/status` | Status |
| `POST` | `/api/impact/analyze` | Synchronous diff analysis — returns risk level, change types, coverage |

### strategy-service `:8082`

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/api/strategy/status` | Status |
| `POST` | `/api/strategy/approve-bdd` | Manually trigger codegen from BDD JSON (local dev) |
| `POST` | `/api/strategy/github-webhook` | GitHub `pull_request` webhook (BDD/test PR merge/reject) |
| `POST` | `/api/strategy/refresh-context` | Re-pull target test repo, refresh coverage cache |
| `GET`  | `/api/qa/cost/report` | AI call gating / cache / cost metrics |

---

## AI Provider Configuration

| `aiqa.ai.provider` | Client | Auth | Cost model |
|--------------------|--------|------|-----------|
| `copilot-cli` (**default**) | `CopilotCliClient` — `gh api` subprocess | `gh auth login` (no env var) | Flat Copilot seat licence |
| `copilot` | `CopilotClient` — REST | `GITHUB_COPILOT_TOKEN` | Flat Copilot seat licence |
| `openai` | `OpenAiClient` — REST | `OPENAI_API_KEY` | Per-token billing |
| *(none configured)* | — | — | Enhanced template fallback |

### copilot-cli (default) — how it works

`CopilotCliClient` runs `gh api https://api.githubcopilot.com/chat/completions` via `ProcessBuilder`.
The `gh` credential store (populated by `gh auth login`) provides authentication automatically —
no token env var required. Falls back to template mode silently if `gh auth status` fails.

```bash
# One-time setup on dev machines
brew install gh
gh auth login
```

Full config reference (all optional — defaults shown):

```yaml
aiqa:
  ai:
    provider: ${AI_PROVIDER:copilot-cli}   # copilot-cli | copilot | openai
    copilot-cli:
      gh-cli-path: ${GH_CLI_PATH:gh}
      model:       ${COPILOT_CLI_MODEL:gpt-4o}
      timeout-seconds: ${COPILOT_CLI_TIMEOUT:120}
    copilot:
      token:    ${GITHUB_COPILOT_TOKEN:}
      base-url: ${COPILOT_BASE_URL:https://api.githubcopilot.com}
      model:    ${COPILOT_MODEL:gpt-4o}
    openai:
      api-key:  ${OPENAI_API_KEY:}
      base-url: ${OPENAI_BASE_URL:https://api.openai.com}
      model:    ${OPENAI_MODEL:gpt-4o}
```

> **Docker / CI note:** `gh auth login` credentials are per-user. In containers, run
> `gh auth login --with-token <<< "$GITHUB_TOKEN"` in the entrypoint, or switch to
> `AI_PROVIDER=copilot` + `GITHUB_COPILOT_TOKEN` (simpler for automated environments).

---

## Cost Optimisation

Strategy-service gates every AI call through three layers before making a network request:

```
ImpactEnvelope
      │
      ▼
① AiCallGate  (zero-cost rules, runs first)
      ├─ SKIP         → docs-only, test-only PRs, trivial <10-line diffs, version bumps
      ├─ RULE_HANDLED → low-risk + existing tests, config-only changes  → template, no AI
      └─ NEEDS_AI ─────────────────────────────────────────────────────────────┐
                                                                               ▼
② PromptResponseCache  (Redis, 24h TTL, keyed on changeType+componentType+risk+repo)
      ├─ HIT  → return cached gherkin, no AI call
      └─ MISS ───────────────────────────────────────────────────────────────────┐
                                                                                 ▼
③ AiClient.complete()  (copilot-cli / copilot / openai)
   → store response in cache for future identical patterns
```

| Layer | Estimated saving |
|-------|-----------------|
| AI call gating (`AiCallGate`) | 40–60% of calls eliminated |
| Prompt response cache (`PromptResponseCache`) | 20–30% additional |
| Copilot seat licence vs per-token | flat fee at volume |
| **Combined** | **60–75% fewer AI calls** |

Metrics via Micrometer, exposed at `GET /api/qa/cost/report`:

```json
{
  "totalRequests": 42,
  "skippedByGate": 18,
  "cacheHits": 8,
  "copilotCalls": 16,
  "skipRatePct": "42.9%",
  "cacheHitRatePct": "19.0%",
  "aiCallRatePct": "38.1%",
  "estimatedSavedPct": "61.9%"
}
```

---

## CI/CD Pipelines

```
.github/workflows/
├── _service-build.yml      ← Reusable: build, test, push to GHCR
├── _service-deploy.yml     ← Reusable: SSH deploy via docker compose
├── pr-service-ci.yml       ← triggers: pr-service/**, common/**
├── impact-service-ci.yml   ← triggers: impact-service/**, common/**
├── strategy-service-ci.yml ← triggers: strategy-service/**, common/**
├── pr-service-cd.yml       ← auto-deploy on main/develop
├── impact-service-cd.yml
└── strategy-service-cd.yml
```

**CI:** checkout → Java 25 (Temurin) → Maven cache → `mvn install -pl common` →
`mvn verify -pl {service}` → upload Surefire reports → Docker build → push to GHCR (push only).

**CD:** CI success → SSH → install/authenticate `gh` CLI (AI services only) → `docker pull` → `docker compose up -d --no-deps --force-recreate` → health check.

### gh CLI on the deploy server

Services that use the `copilot-cli` AI provider (strategy, codegen, feedback) need `gh` on the server.
The `_service-deploy.yml` reusable workflow handles this automatically when `install-gh-cli: true` is set:

1. Installs `gh` via the official apt repo if not already present.
2. Reads `GITHUB_COPILOT_TOKEN` from the server's `.env` file and runs `gh auth login --with-token`.
3. If the token is absent, logs a warning — the service starts but AI falls back to templates.

### Required secrets

| Secret | Scope | Purpose |
|--------|-------|---------|
| `GHCR_TOKEN` | global | Push/pull GHCR images |
| `DEPLOY_HOST` / `DEPLOY_USER` / `DEPLOY_SSH_KEY` | production env | SSH deploy |
| `STAGING_DEPLOY_HOST` / `STAGING_DEPLOY_USER` / `STAGING_DEPLOY_SSH_KEY` | staging env | SSH deploy |

### Production `.env` variables (on deploy server)

| Variable | Required | Notes |
|----------|----------|-------|
| `TARGET_REPO_URL` | for PR creation | HTTPS URL of target test repo |
| `TARGET_REPO_TOKEN` | for PR creation | PAT with `repo` scope; SSO-authorise for org repos |
| `TARGET_REPO_USERNAME` | for PR creation | GitHub username |
| `GITHUB_WEBHOOK_SECRET` | recommended | Prevents unauthenticated webhook triggers |
| `AI_PROVIDER` | no | `copilot-cli` (default) · `copilot` · `openai` |
| `GITHUB_COPILOT_TOKEN` | if `provider=copilot` | Token with Copilot access |
| `OPENAI_API_KEY` | if `provider=openai` | API key |
| `AIQA_AI_ENABLED` | no | `true` to enable AI-assisted risk scoring in impact-service |

---

## AI Feedback Loop

When a QA-generated PR is rejected, the system automatically:

1. Fetches all review comments from GitHub
2. Classifies via AI: `KNOWLEDGE_GAP` or `STYLE_ONLY`
3. If `KNOWLEDGE_GAP`: appends new knowledge to `productExpert/{product}/PRODUCT.md`, opens a
   separate update PR for human review
4. Re-generates the rejected content with feedback as additional context
5. Creates a revised PR — loop repeats until accepted

| PR type | Branch prefix | Title format | Handler |
|---------|--------------|--------------|---------|
| BDD scenarios (initial) | `qa/bdd/*` | `[AI-QA] {prTitle}` | `BddGenerator → TestPrService` |
| BDD scenarios (revised) | `qa/bdd/*-rev-*` | `[AI-QA] Revised: {prTitle}` | `PrFeedbackService.handleBddRejection()` |
| Test code (initial) | `qa/tests/*` | `✅ [AI-QA] {prTitle}` | `CodegenService → TestPrService` |
| Test code (revised) | `qa/tests/*-rev-*` | `[AI-QA] Revised Tests: {prTitle}` | `PrFeedbackService.handleTestRejection()` |

`{prTitle}` falls back to `"… for PR: {prId}"` when no title was provided in the webhook payload.

Feedback runs on a **virtual thread** so the GitHub webhook HTTP response is returned immediately.

### Target repo convention

```
{test-repo}/
  productExpert/
    payments/
      PRODUCT.md    ← domain flows, business rules, edge cases
      PATTERNS.md   ← assertion patterns, test structure
  .aiqa/
    context.md      ← team-wide QA conventions
  .github/
    agents/
      api-conventions.md   ← agent instruction files (read by RepoContextService)
```

---

## Running Tests

```bash
./mvnw test      # all modules
./mvnw test -pl strategy-service
./mvnw test -pl impact-service -Dtest=RiskScorerTest
```

Expected output:
```
Tests run: 25   ← pr-service
Tests run: 27   ← impact-service
Tests run: 26   ← strategy-service
Tests run:  7   ← codegen-service
BUILD SUCCESS
```

**Total: 85 tests, 0 failures — zero Mockito (real test doubles only)**

| Module | Class | Tests | Covers |
|--------|-------|-------|--------|
| pr-service | `PRServiceTest` | 9 | enrichment, validation, Kafka publish |
| pr-service | `PRControllerTest` | 4 | webhook, submit, demo, health |
| pr-service | `PRControllerAdviceTest` | 5 | global exception handler |
| pr-service | `ProductsFieldDeserializerTest` | 7 | `products` JSON array + comma-string, whitespace trim, null/empty |
| impact-service | `GitDiffParserTest` | 7 | diff parsing, file types |
| impact-service | `RiskScorerTest` | 11 | thresholds, weights, normalisation |
| impact-service | `TestCoverageServiceTest` | 9 | coverage ratio, levels |
| strategy-service | `RepoContextTest` | 9 | helper methods, agent instructions |
| strategy-service | `ApiTestRunnerTest` | 7 | code generation, repo context |
| strategy-service | `StrategyAgentTest` | 10 | SKIP/CREATE logic, fallback rules |
| codegen-service | `ApiTestRunnerTest` | 7 | code generation |

---

## Technology Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 25, Spring Boot 4 |
| Messaging | Apache Kafka (Spring Kafka 3.2) |
| State | Redis (`StringRedisTemplate`, `RedisPrTracker`) |
| AI clients | `CopilotCliClient` (default · `gh api`) · `CopilotClient` (REST) · `OpenAiClient` (REST) |
| Cost gating | `AiCallGate` · `PromptResponseCache` (Redis) · `AiCostMonitor` (Micrometer) |
| Test generation | RestAssured (API), Selenium (UI), Appium (Mobile), Cucumber/Gherkin |
| Build | Maven 3.9 multi-module |
| Observability | Spring Boot Actuator, Micrometer |
| Boilerplate | Lombok |
| Tests | JUnit 5, AssertJ — **zero Mockito** |

---

## Future Roadmap

**AST-Level Diff Feedback** — parse merged `.feature` and `.java` test files at AST level to
detect human edits, emit `FeedbackDelta` records (`WRONG_ASSERTION`, `MISSING_CONTEXT`,
`COVERAGE_GAP`), auto-commit to `.qa-agent/instructions.md` in the test repo. This would
complement the rejection-based feedback loop with learning from accepted-but-edited PRs.

````

