# QA-ISystem

QA-ISystem is an autonomous, AI-driven QA pipeline. When a developer opens a Pull Request on
a product repository, it analyzes the change, decides which tests are needed, generates BDD
scenarios, and — after a human approves them — generates and stabilizes the test code, opening
PRs back on the product repo. All AI work is performed by the **GitHub Copilot CLI**.

For the full design, see [`QA-ISystem-Architecture.md`](QA-ISystem-Architecture.md).

## Pipeline at a glance

```
PR ─▶ pr-service ─▶ impact-service ─▶ strategy-service ─▶ [human merges BDD PR]
        :8080          :8081               :8082
                                                        ─▶ codegen-service ─▶ test PR
                                                             :8083
                                                        ─▶ feedback-service (on rejection)
                                                             :8084
```

## Modules

| Module | Port | Role |
|--------|------|------|
| `common` | — | Shared models, config, AI/GitHub/state services |
| `pr-service` | 8080 | PR webhook ingestion |
| `impact-service` | 8081 | Deterministic impact analysis |
| `strategy-service` | 8082 | Strategy + BDD generation, opens the BDD PR |
| `codegen-service` | 8083 | Test-code generation + stabilization, opens the test PR |
| `feedback-service` | 8084 | AI rejection-feedback loop |

See architecture doc [§2](QA-ISystem-Architecture.md#2-system-overview) for responsibilities
and [§3](QA-ISystem-Architecture.md#3-two-execution-modes) for the two execution modes.

## Prerequisites

- **Java 25** and **Maven 3.9+** (the bundled `./mvnw` wrapper works).
- **Docker** with Compose v2 (for local Kafka, Zookeeper, and Redis).
- **GitHub Copilot CLI** — the only AI provider. Install and authenticate once:
  ```bash
  brew install gh
  gh auth login          # then install @github/copilot and confirm `copilot` runs
  ```
  No AI token environment variables are required; `gh` manages credentials.

## Quick start (local, always-on mode)

Local development runs the five services continuously over Kafka and Redis. The
`start-local.sh` script does everything: it brings up the Docker infrastructure, builds the
JARs with Maven, and launches the services on ports 8080–8084.

1. **Set the target repository and start:**
   ```bash
   export TARGET_REPO_URL=https://github.com/<org>/<product-repo>.git
   export TARGET_REPO_TOKEN=<github-pat-with-repo-scope>
   export TARGET_REPO_USERNAME=<github-username>

   ./scripts/start-local.sh                 # add --with-kafka-ui for Kafka UI on :8090
   ```
   Other flags: `--skip-build` (reuse existing JARs), `--fresh` (wipe stale Docker volumes),
   `--stop` (tear everything down), `--help`.

2. **Submit a PR** to kick off the pipeline:
   ```bash
   # zero-config built-in demo PR:
   curl -X POST http://localhost:8080/api/pr/demo

   # or post a sample webhook payload:
   curl -X POST http://localhost:8080/api/pr/webhook \
        -H 'Content-Type: application/json' \
        -d @pr-webhook-sample.json
   ```
   Health check: `GET http://localhost:8080/api/pr/health`.

3. **Approve the BDD PR.** strategy-service opens a BDD PR on the target repo. Merge it
   normally, or simulate approval locally:
   ```bash
   ./scripts/approve-bdd.sh --pr-id <PR-ID> --yes
   ```
   Approval triggers codegen-service, which generates the test code and opens the test PR.

## Configuration

Settings live in each service's `src/main/resources/application.yaml` and accept environment
overrides. Copy `.env.example` to `.env` for local values.

### Target repository

| Env var | Meaning |
|---------|---------|
| `TARGET_REPO_URL` | HTTPS URL of the product repo under test |
| `TARGET_REPO_BRANCH` | Base branch (default `main`) |
| `TARGET_REPO_TOKEN` | GitHub PAT with `repo` scope (PR creation) |
| `TARGET_REPO_USERNAME` | GitHub username for the PAT |
| `TARGET_REPO_AUTH_TYPE` | Auth mode (`none` or token) |
| `TARGET_REPO_SCAN_MODULES` | Scan target modules for package/import conventions |

### AI provider

The only provider is the GitHub Copilot CLI; OpenAI and Copilot REST providers have been
removed. AI beans activate only when `aiqa.github.enabled=true` (strategy- and
codegen-service). Copilot settings live under `aiqa.ai` (CLI path, model, timeout).
Authenticate with `gh auth login` — no AI tokens in config.

### Common flags

| Key | Default | Purpose |
|-----|---------|---------|
| `aiqa.codegen.enabled` | `false` | strategy-service drives the codegen hand-off |
| `aiqa.strategy.risk-threshold-high` | `0.7` | High-risk gate for AI generation |
| `aiqa.agent.max-concurrent` | `3` | Parallel Copilot runs / Kafka listener concurrency |
| `aiqa.stabilization.max-retries` | `3` | codegen Run → Fail → Fix attempts |
| `aiqa.trace.enabled` | `false` | Enable context tracing (see Monitoring) |

## CI/CD & scale-to-zero deployment

The production direction replaces always-on services with GitHub Actions that scale to zero.
See architecture doc [§8](QA-ISystem-Architecture.md#8-scale-to-zero-deployment) for the
model. In short:

- **Workflows** live in `qa-control/.github/workflows/`: `qa-impact-strategy.yml`,
  `qa-codegen.yml`, `qa-feedback.yml`. A webhook **receiver** (in `qa-control`) converts
  target-repo events into `repository_dispatch` triggers.
- **Runners**: ARC self-hosted runners on Kubernetes (`k8s/arc`), autoscaled by KEDA
  (`k8s/keda`). Build the runner image from `runner-image/Dockerfile` (Node 22 +
  `@github/copilot` + `gh`).
- **State and support**: Neon Postgres (`k8s/neon`), the headroom context proxy
  (`k8s/headroom`), and Copilot auth (`k8s/copilot`).
- **Apply manifests:**
  ```bash
  kubectl apply -f k8s/arc -f k8s/keda -f k8s/neon -f k8s/headroom -f k8s/copilot
  kubectl apply -f k8s/strategy-deployment.yaml -f k8s/codegen-deployment.yaml
  ```
- Services run as one-shot JARs: `java -jar <service>.jar --mode oneshot`.

## Monitoring & operations

- **Logs**: `start-local.sh` writes per-service logs under `logs/` (e.g.
  `tail -f logs/strategy-service.log`). Every log line is prefixed with `[ClassName]`.
- **AI cost report**: `curl http://localhost:8082/api/qa/cost/report`. The Conductor
  subprocess path bypasses in-process metering, so token/cost can read as zero there.
- **Context traces** (off by default): set `aiqa.trace.enabled=true` (and optionally
  `aiqa.trace.sink=file|postgres`). The file sink writes prompt/raw-stream/final/meta under
  `logs/context-traces/`.
- **Kafka UI** (optional): `./scripts/start-local.sh --with-kafka-ui` → http://localhost:8090.

### Troubleshooting

- **Stale ZooKeeper / Kafka after a crash**: restart with `./scripts/start-local.sh --fresh`
  to wipe Docker volumes.
- **No BDD PR appears**: confirm `gh auth login` succeeded and `TARGET_REPO_TOKEN` has `repo`
  scope, then check `logs/strategy-service.log`.
- **Local webhook signature**: set `GITHUB_WEBHOOK_SECRET` to match the HMAC, or use
  `POST /api/pr/demo` / `/api/pr/submit`, which do not require a signature.

## Running tests

```bash
./mvnw test                              # all modules
./mvnw test -pl strategy-service -am     # one module plus its dependencies
```

Testing follows a **zero-Mockito** policy: real test doubles as inner static classes, JUnit 5
only.

## Technology stack

| Layer | Choice |
|-------|--------|
| Language | Java 25 |
| Framework | Spring Boot 4.0.x |
| Build | Maven (multi-module) |
| Messaging | Apache Kafka (always-on mode) |
| State | Redis (always-on) / Neon Postgres (scale-to-zero) |
| AI | GitHub Copilot CLI (Conductor agent) |
| Orchestration | GitHub Actions + ARC + KEDA on Kubernetes |
| Boilerplate | Lombok |

## Roadmap

- Complete the cutover from always-on services to scale-to-zero GitHub Actions.
- Dead-letter handling for failed Kafka messages.
- Richer coverage analysis feeding the strategy decision.
