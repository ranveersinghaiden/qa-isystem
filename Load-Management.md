# Load Management & Scaling Architecture

This document details the six architectural changes implemented to evolve QA-ISystem from a serial, single-threaded pipeline into a horizontally scalable, elastic autonomous QA engine.

## Change A: Per-scenario Fan-out (Codegen Parallelism)

**Problem:** Previously, one Kafka message contained an entire BDD feature (multiple scenarios). `codegen-service` would process these scenarios in a serial loop. A single PR with 118 scenarios would block a consumer thread for hours, causing Kafka rebalances and head-of-line blocking.

**Solution:**
- **New Model:** `TestScriptRequest` containing `prId`, `scenarioId`, a single `Scenario` object, and metadata.
- **Fan-out:** `StrategyService` now iterates through generated scenarios and publishes one Kafka message per scenario to `TestScriptsQueue`.
- **Parallel Processing:** Messages are keyed by `scenarioId` to ensure even distribution across all Kafka partitions. Multiple `codegen-service` instances (and multiple threads per instance) now process different scenarios from the same PR simultaneously.
- **Completion Tracking:**
    - **Redis Counter:** `qa:codegen:{prId}:remaining` tracks how many scenarios are left for a PR.
    - **Idempotency:** `qa:codegen:{scenarioId}:done` prevents duplicate test PRs if a message is redelivered.
    - Once the counter hits zero, a per-PR summary is emitted.

## Change B: Elastic, Host-sized Concurrency

**Problem:** Agent runs (Copilot CLI subprocesses) are heavy on CPU/Memory and take 1.5–3.5 minutes. Unbounded concurrency would crash the host or exhaust AI tokens.

**Solution:**
- **Controlled Concurrency:** New property `aiqa.agent.max-concurrent` (default 3) limits the number of active agent subprocesses.
- **Semaphore:** `ConductorAgentRunner` uses a `java.util.concurrent.Semaphore` to gate subprocess launches.
- **Kafka Harmony:** 
    - `max.poll.records=1` ensures a consumer thread only takes one scenario at a time.
    - `max.poll.interval.ms` increased to 900,000 (15 min) to prevent "rebalance storms" when an agent run takes longer than the default poll timeout.

## Change C: WorkspacePool (Isolated Working Directories)

**Problem:** Concurrent agents running in the same git clone cause file-system races and `git pull` corruption during execution.

**Solution:**
- **Workspace Isolation:** `WorkspacePool` maintains a pool of isolated `git worktree` directories created off the base clone.
- **Lease/Release:** Each agent run leases a unique worktree path, executes its task, and returns it to the pool.
- **Efficiency:** Worktrees share the base `.git` object store, making them extremely fast and lightweight to create compared to fresh clones.
- **Independence:** This decouples concurrency from correctness; `ConductorAgentRunner` now receives a dedicated path for every run.

## Change D: Horizontal Autoscaling (KEDA)

**Problem:** Manual scaling of service replicas is inefficient for variable PR volumes.

**Solution:**
- **KEDA (Kubernetes Event-Driven Autoscaling):** Strategy and Codegen services are deployed with KEDA `ScaledObject` configurations.
- **Lag-based Scaling:** Replicas scale out automatically based on consumer-group lag in `ImpactResultsQueue` (Strategy) and `TestScriptsQueue` (Codegen).
- **Partitioning:** 
    - `ImpactResultsQueue`: 12 partitions.
    - `TestScriptsQueue`: 24 partitions.
    - Allows scaling up to 24 concurrent instances for codegen.
- **Scaling Logic:** Scales out when `lag / replica > 5`.

## Change E: Backpressure and Safety

**Problem:** Rapid bursts of PRs could overwhelm the system if Kafka continues to deliver messages faster than they can be processed.

**Solution:**
- **Natural Backpressure:** The combination of `Semaphore` permits and `max.poll.records=1` creates a pull-based backpressure system. Consumer threads only poll new work when a semaphore slot is available.
- **Active Waiting:** Partitions assigned to a busy instance remain idle until that instance has capacity, preventing message loss or excessive memory pressure.

## Change F: Observability

**Problem:** Lack of visibility into capacity and bottleneck identification.

**Solution:**
- **Micrometer Metrics:**
    - `aiqa.agent.slots.inuse` / `.available`: Active vs. free concurrency slots.
    - `aiqa.agent.latency`: Histogram of agent subprocess durations.
    - `aiqa.agent.workspace.available`: Status of the `WorkspacePool`.
- **Prometheus Export:** Metrics exposed via `/actuator/prometheus` for Grafana dashboards and KEDA scaling decisions.
- **Completion Logging:** Per-PR scenario completion status tracked in logs for GREP-ability.

