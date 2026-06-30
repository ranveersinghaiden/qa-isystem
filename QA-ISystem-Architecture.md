# QA-ISystem — Architecture & Design Reference

> An autonomous, AI-driven QA pipeline. It turns a product Pull Request into reviewed BDD
> scenarios and generated, stabilized test code, with a human approval gate in the middle.

This document is the design reference. For setup, configuration, and day-to-day operation,
see [`README.md`](README.md).

---

## 1. The Problem

When a developer opens a PR on a product repository, someone has to decide: does the test
suite need updating, and if so, which tests should be written? Traditionally a QA engineer
reads the diff, finds the affected components, checks existing coverage, writes BDD
scenarios, generates test code, runs and fixes it, and opens a PR — hours of manual work.

QA-ISystem automates that loop while keeping a human in control of what actually merges.

---

## 2. System Overview

The project is a Java 25 / Spring Boot 4 multi-module Maven build. There are six modules:
five deployable Spring Boot services plus `common`, a shared library.

| Module | Port | Responsibility |
|--------|------|----------------|
| `common` | — | Shared domain models, Kafka/Redis/Postgres config, AI clients, GitHub and repo-context services |
| `pr-service` | 8080 | Ingest PR webhooks, validate, extract context, publish to the pipeline |
| `impact-service` | 8081 | Deterministic diff/impact analysis (no AI) |
| `strategy-service` | 8082 | Test-strategy decision, BDD generation, opens the BDD PR |
| `codegen-service` | 8083 | Test-code generation and stabilization, opens the test PR |
| `feedback-service` | 8084 | AI rejection-feedback loop on reviewed PRs |

### End-to-end flow

```
   Product PR opened
        │
        ▼
  ┌────────────┐   FeatureUpdatesQueue   ┌────────────────┐
  │ pr-service │ ───────────────────────▶│ impact-service │
  │   :8080    │                          │     :8081      │
  └────────────┘                          └────────────────┘
                                                  │ ImpactResultsQueue
                                                  ▼
  human reviews & merges          ┌────────────────────┐
  the BDD PR  ◀───── opens BDD PR ─│  strategy-service  │
        │                          │       :8082        │   (Copilot CLI Conductor
        │ TestScriptsQueue         └────────────────────┘    generates Gherkin)
        ▼
  ┌────────────────┐  opens test PR
  │ codegen-service│ ─────────────────▶  product repo
  │     :8083      │
  └────────────────┘
        │ TestResultsQueue / rejected review
        ▼
  ┌─────────────────┐  FeedbackQueue
  │ feedback-service│  (AI regeneration)
  │     :8084       │
  └─────────────────┘
```

1. **pr-service** receives the PR webhook, builds a `PrContext`, and publishes a
   `PullRequest` to `FeatureUpdatesQueue`.
2. **impact-service** analyzes the diff deterministically and emits an `ImpactEnvelope`
   (impacted components plus a risk score) to `ImpactResultsQueue`.
3. **strategy-service** decides a `TestStrategy`, generates `BddScenario`s via the Copilot
   CLI, and opens a **BDD PR** on the target repo.
4. A **human reviews and merges** the BDD PR — this is the approval gate.
5. Merging triggers **codegen-service**, which generates and stabilizes test code per
   scenario and opens a **test PR**.
6. If a reviewer rejects the output, **feedback-service** runs the AI feedback loop to
   regenerate.

---

## 3. Two Execution Modes

The same business logic runs in two deployment shapes.

| | Legacy always-on | Scale-to-zero (strategic direction) |
|--|------------------|-------------------------------------|
| Trigger / transport | Apache Kafka topics | GitHub Actions workflows |
| Process model | Five long-running Spring Boot services | One-shot JARs (`--mode oneshot`) |
| State | Redis (`RedisPrTracker`) | Neon Postgres (`PostgresStateStore`) |
| Orchestration | `docker-compose` + `scripts/start-local.sh` | ARC self-hosted runners + KEDA on Kubernetes |
| Idle cost | Always on | ~$0 (scales to zero) |
| Used for | Local development | Production target (branch `moveToK8s`) |

Local development uses the always-on Kafka path. The scale-to-zero path is defined by the
workflows and Kubernetes manifests in this repo (see §8) and is the production target.

### Kafka topics (always-on transport)

Topic names come from `kafka.topics.*` configuration — they are never hardcoded.

| Config key | Topic | Producer → Consumer |
|------------|-------|---------------------|
| `feature-updates` | `FeatureUpdatesQueue` | pr-service → impact-service |
| `impact-results` | `ImpactResultsQueue` | impact-service → strategy-service |
| `test-scripts` | `TestScriptsQueue` | strategy-service → codegen-service |
| `test-results` | `TestResultsQueue` | codegen-service → feedback path |
| `feedback` | `FeedbackQueue` | feedback-service |

---

## 4. The `common` Module

### Domain models (`nz.co.eroad.qaisystem.model`)

- `PullRequest` — the incoming PR (number, repo, branch, author, diff).
- `GitDiff` / `PrContext` — the parsed diff and the extracted repository context.
- `ImpactEnvelope` — impacted components and files plus a risk score.
- `TestStrategy` / `PrType` — the chosen strategy and the PR classification.
- `BddScenario` — a generated Gherkin scenario.
- `TestScript` / `TestScriptRequest` — a unit of generated test code and its request.
- `TestResult` — execution outcome, including the final post-fix script.
- `FeedbackEvent` — a rejection-feedback signal.
- `PrRecord`, `ConversationHistory`, `ChatMessage`, `CoverageReport` — tracking and
  AI-conversation support.

### Shared services

- **AI** — `AiClient` (implemented by `CopilotCliClient`) for in-process single-shot calls;
  `ConductorAgentRunner` for heavy multi-step generation (see §5).
- **Repository / GitHub** — `RepoContextService` clones and refreshes the target repo and
  loads product-expert grounding; `GitHubService` performs branch/file/PR operations;
  `PrTracker` (`RedisPrTracker` or in-memory) de-duplicates PRs.
- **State** — `StateStore` (`PostgresStateStore` for one-shot/K8s mode, `InMemoryStateStore`
  otherwise) holding `GateState`, `ScenarioState`, and `PrHistory`.
- **Execution** — `WorkspacePool` leases isolated git worktrees so concurrent agent runs
  never collide.

---

## 5. AI Execution & Context

**The GitHub Copilot CLI is the only AI provider.** OpenAI and Copilot REST API providers
have been removed; credentials are managed entirely by `gh auth login`, so no AI token
environment variables are required or accepted. AI beans activate only when
`aiqa.github.enabled=true` (strategy- and codegen-service); pr-service and impact-service
leave it unset and instantiate no AI beans.

There are two execution paths, both backed by Copilot:

- **In-process** — `CopilotCliClient` (the `AiClient` bean), configured under `aiqa.ai`
  (`AiProviderProperties`: CLI path, model, timeout). Used for short, single-shot prompts and
  metered by the AI cost monitor.
- **Subprocess (primary generation)** — `ConductorAgentRunner` launches the Copilot CLI with
  the bundled **Conductor** agent:

  ```
  copilot --allow-all --autopilot --silent \
          --max-autopilot-continues <n> --agent=Conductor -p <prompt>
  ```

  It runs inside an isolated `WorkspacePool` worktree and routes the agent's outbound LLM
  traffic through the **headroom proxy** for context compression.

### Context selection is the Copilot CLI's job

The Conductor agent receives a thin instruction prompt and **gathers whatever context it
needs directly from the cloned target repository**. There is no custom context-selection
class or pipeline in QA-ISystem — retrieval, file selection, and code adjacency are delegated
to the CLI, which preserves exact local detail (names, imports, framework conventions).

The only context helper is **`ProductExpertContext`**, a small data holder for team-authored
`PRODUCT.md` / `PATTERNS.md` files found under the target repo's `productExpert/{product}/`
directory. That content is appended to prompts as supplementary "product-expert" grounding.
`impact-service` performs deterministic diff analysis, which is independent of LLM context
selection.

### AI cost gating

`strategy-service` gates expensive generation on risk
(`aiqa.strategy.risk-threshold-high=0.7`, `aiqa.strategy.risk-threshold-medium=0.4`) and
de-duplicates work via `PrTracker`, so an identical PR payload is not regenerated.

---

## 6. Service Details

### pr-service (8080)
Receives the PR webhook, validates it, uses `RepoContextService` to build a `PrContext`, and
publishes a `PullRequest` to `FeatureUpdatesQueue`. Performs no AI work.

### impact-service (8081)
Consumes `FeatureUpdatesQueue`, parses the `GitDiff`, maps changes to impacted components,
scores risk, and emits an `ImpactEnvelope` to `ImpactResultsQueue`. **Deterministic — no AI
by default.**

### strategy-service (8082)
Consumes `ImpactResultsQueue`. `StrategyAgent` decides a `TestStrategy` from the risk score
and PR type; `BddGenerator` produces `BddScenario`s via the Conductor agent (with
product-expert grounding and an optional cache); `GitHubService` opens the **BDD PR** on the
target repo. Hosts the AI cost monitor (§7). When `aiqa.codegen.enabled=true` it also drives
the codegen hand-off.

### codegen-service (8083)
After the BDD PR is merged, generates test code per scenario and runs the
**`StabilizationLoop`** (Run → Fail → Fix, `aiqa.stabilization.max-retries=3`,
`retry-delay-ms=2000`). `execute(script, openTestPr)`: the Kafka path opens a per-scenario
test PR (`true`); the one-shot path skips per-scenario PRs and opens a single aggregate PR
(`false`). Each `TestResult` carries the final post-fix script so feedback can be
reconstructed faithfully.

### feedback-service (8084)
Watches reviewed PRs. On rejection, `PrFeedbackService` extracts the review comments and runs
the AI feedback loop to regenerate, emitting `FeedbackEvent`s. When there is no actionable
feedback it skips regeneration.

---

## 7. Observability & Monitors

- **Context traces** — `ContextTraceRecorder` / `TraceConfig`, gated by `aiqa.trace.enabled`
  (default `false`). `aiqa.trace.sink` selects `file` (writes prompt, raw stream, final, and
  meta under `./logs/context-traces`) or `postgres`; `aiqa.trace.redact` applies the
  secret-redaction denylist.
- **AI cost** — `AiCostMonitor` in strategy-service exposes `GET :8082/api/qa/cost` (and
  `/api/qa/cost/report`). Token and cost figures read as zero for the Conductor subprocess
  path because it bypasses in-process `AiClient` metering.

---

## 8. Scale-to-Zero Deployment

The production direction runs the pipeline as GitHub Actions instead of always-on services.

- **Workflows** (`qa-control/.github/workflows/`): `qa-impact-strategy.yml`, `qa-codegen.yml`,
  `qa-feedback.yml`. A lightweight webhook **receiver** turns target-repo events into
  `repository_dispatch` triggers.
- **Runners**: ARC (Actions Runner Controller) self-hosted runners on Kubernetes (`k8s/arc`),
  autoscaled by **KEDA** (`k8s/keda`). The runner image (`runner-image/Dockerfile`) ships
  Node 22, `@github/copilot`, and `gh`.
- **State and support**: Neon Postgres (`k8s/neon`, via `PostgresStateStore`), the headroom
  proxy (`k8s/headroom`), and Copilot authentication (`k8s/copilot`).
- **One-shot execution**: each service runs as `java -jar <service>.jar --mode oneshot`
  (`OneShotArgs`, `CodegenOneShotRunner`, `StrategyOneShotRunner`), does its unit of work,
  persists state to Postgres, and exits — so idle cost is ~$0.

---

## 9. Scaling & Load

- **Per-scenario fan-out** — codegen processes scenarios in parallel, sized to the host
  (`aiqa.agent.max-concurrent`, default 3).
- **Isolation** — `WorkspacePool` leases isolated git worktrees
  (`aiqa.agent.workspace-pool-size`, `aiqa.agent.workspace-lease-timeout-seconds=600`) so
  parallel Copilot runs never share a working tree.
- **Elastic autoscaling** — in K8s mode, KEDA scales runners on demand and back to zero when
  idle.
- **Backpressure** — concurrency caps and lease timeouts bound resource use under load.

---

## 10. Key Design Decisions

- **Human-in-the-loop gate** — the BDD PR must be merged by a human before any test code is
  generated.
- **Deterministic impact, AI generation** — impact analysis is reproducible and AI-free; only
  scenario and code generation use the LLM.
- **Copilot CLI owns context** — this maximizes local-code fidelity (exact names, imports,
  conventions) versus a hand-rolled retriever.
- **Conditional AI beans** — `aiqa.github.enabled` keeps AI out of services that do not need
  it.
- **Zero-Mockito testing** — real test doubles (inner static subclasses) and JUnit 5 only; no
  mocking framework.
- **Config-driven topology** — Kafka topic names, risk thresholds, and concurrency are all
  configuration, never hardcoded.

---

## 11. Class Reference

| Class | Module · package | Role |
|-------|------------------|------|
| `ConductorAgentRunner` | common · `agent` | Runs the `copilot --agent=Conductor` subprocess in a pooled worktree |
| `CopilotCliClient` | common · `agent` | In-process `AiClient` over the Copilot CLI |
| `RepoContextService` | common · `service` | Clones/refreshes the target repo, loads product-expert grounding |
| `GitHubService` | common · `github` | Branch/file/PR operations on the target repo |
| `PrTracker` / `RedisPrTracker` | common · `github` | PR de-duplication tracking |
| `ProductExpertContext` | common · `context` | Team-authored product-grounding data holder |
| `WorkspacePool` | common · `execution` | Isolated git-worktree leasing for concurrency |
| `StateStore` / `PostgresStateStore` | common · `state` | Gate/scenario/PR-history state (Postgres or in-memory) |
| `ContextTraceRecorder` / `TraceConfig` | common · `trace` | Optional prompt/response tracing |
| `BddGenerator` | strategy-service · `agent` | Generates Gherkin via the Conductor agent |
| `StrategyAgent` | strategy-service · `agent` | Decides the test strategy from risk and PR type |
| `AiCostMonitor` | strategy-service · `monitor` | AI cost reporting endpoint |
| `StabilizationLoop` | codegen-service · `execution` | Run → Fail → Fix test-code stabilization |
| `TestPrService` | strategy/codegen · `service` | Opens the test PR(s) |
| `PrFeedbackService` | feedback-service · `agent` | AI rejection-feedback loop |
