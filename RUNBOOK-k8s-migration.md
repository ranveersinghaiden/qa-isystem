# RUNBOOK — QA-ISystem scale-to-zero migration (GitHub Actions + K8s/ARC)

Operational provisioning guide for the migration from always-on hosted services
(Kafka + Redis + 5 idle JVMs + SSH deploy box) to **scale-to-zero GitHub Actions
runners on Kubernetes (ARC)** with **Neon Postgres** state and a **single shared
headroom** compression Service.

> **Scope of this runbook:** the steps a **human operator** runs against external
> systems (Neon, GitHub App, ghcr, a K8s cluster, the serverless host). The repo
> already contains every artifact these steps reference (`db/schema.sql`,
> `runner-image/Dockerfile`, `k8s/**`, `qa-control/**`, the workflows). Nothing
> here changes live prod until **Phase 7**; each phase is independently reversible.

## Architecture in one paragraph

A target PR fires a webhook → the **receiver** (`qa-control/receiver`) verifies the
HMAC and maps the event to a `repository_dispatch` → a **workflow** in the
`qa-control` repo wakes an **ephemeral ARC runner pod** built from
`runner-image/Dockerfile` (JDK 25 + 5 service jars + headroom + warm `.m2`) → the
pod runs one service in `--mode oneshot`, reads/writes **Neon** (`db/schema.sql`),
reaches the shared **headroom** Service at
`http://headroom.qa.svc.cluster.local:8787`, then terminates → nodes scale to 0.
Human gates (BDD-PR approve, test-PR approve) are persisted in
`pr_history.gate_state` and resumed by the next dispatch.

## Locked decisions (context)

- **C1** Neon serverless Postgres replaces Redis (jsonb blobs; `db/schema.sql`).
- **C2** One tiny always-on in-cluster headroom Service shared by all pods; OAuth in ONE Secret.
- **C3** 1 scenario per pod (`SCENARIOS_PER_POD`, default 1).
- **C4** Serverless receiver: verify HMAC → map event → `repository_dispatch` (no DB, no tokens in payload).
- **C5** Single-org now; nullable `tenant_id` seam everywhere for future SaaS.

---

## Phase 0 — Foundations (no behavior change)

Stand up the new substrate beside the live pipeline.

1. **Neon project + schema (C1).**
   - Create a Neon project; create a database `qa`.
   - Capture the pooled connection string as `QA_DB_URL`
     (`postgresql://USER:PASSWORD@HOST.neon.tech/qa?sslmode=require`).
   - Apply the schema:
     ```bash
     export QA_DB_URL='postgresql://…?sslmode=require'
     psql "$QA_DB_URL" -f db/schema.sql
     psql "$QA_DB_URL" -c '\dt'   # expect: pr_history, scenario_state, context_history,
                                  #         chat_history, prompt_cache, coverage_learning, dedup_state
     ```
2. **GitHub App.**
   - Create from `qa-control/github-app-manifest.json` (set `hook_attributes.url`
     to the receiver URL after Phase 4, or a placeholder now).
   - Permissions: `contents:write`, `pull_requests:write`, `metadata:read`.
   - Events: `pull_request`, `pull_request_review`.
   - Generate a **private key (PEM)**; record the **App id** and (after install) the
     **installation id**.
   - Install on a **test/shadow target repo** first (e.g.
     `ranveersinghaiden/playwrightFramework`), NOT prod.
3. **`qa-control` repo.**
   - Create the repo; push the contents of this directory's `qa-control/` into it.
   - Set repo **variables**: `QA_APP_ID`.
   - Set repo **secrets**: `QA_APP_PRIVATE_KEY` (PEM), `QA_DB_URL`.
4. **ghcr.io registry.** Ensure the source repo can push
   `ghcr.io/ranveersinghaiden/qa-isystem/qa-runner` (packages: write).

**Exit:** `psql` reaches Neon; App installed on the shadow repo; a hand-fired
`repository_dispatch` runs an empty workflow.
**Rollback:** delete the App + Neon project. Live pipeline untouched.

---

## Phase 1 — One-shot mode (shadow, off by default)

Each service can "do one thing and exit" while still able to Kafka-listen.
**Delivered in-repo** (additive, `@Profile("oneshot")`): `common/.../state/StateStore`
+ `PostgresStateStore` (+ `InMemoryStateStore`), per-service `*OneShotRunner`.

Verify locally (no cluster needed):
```bash
./mvnw -q -B test          # reactor BUILD SUCCESS; default (no-profile) boot unchanged
# one-shot smoke against a throwaway Neon branch:
java -jar impact-service/target/*.jar --mode oneshot --pr-id PR-XXXXXXXX
psql "$QA_DB_URL" -c "select pr_id, gate_state from pr_history where pr_id='PR-XXXXXXXX';"
```
**Exit:** one-shot writes match what the Kafka path would write.
**Rollback:** don't set the profile; the old path is default.

---

## Phase 2 — Custom runner image (the "backpack")

`runner-image/Dockerfile` (Part F) bakes JDK 25, the 5 jars, the GitHub
Copilot CLI + `gh` (the default engine), headroom, and a warm
`.m2` so pods start in ~20s.

- Push to `main` touching `runner-image/**`, `**/pom.xml`, or `**/src/**` →
  `.github/workflows/qa-runner-image.yml` (E.4) builds and pushes
  `ghcr.io/ranveersinghaiden/qa-isystem/qa-runner:latest`.
- Smoke offline:
  ```bash
  docker run --rm ghcr.io/ranveersinghaiden/qa-isystem/qa-runner:latest \
    java -jar /app/impact.jar --mode oneshot --pr-id PR-TEST
  ```
- Verify the Copilot CLI + gh are present (the default engine):
  ```bash
  docker run --rm ghcr.io/ranveersinghaiden/qa-isystem/qa-runner:latest \
    bash -lc 'copilot --version && gh --version'
  ```
**Exit:** runs offline (warm `.m2`, headroom present); cold start ≤ ~20s.
**Rollback:** image is inert until ARC references it.

---

## Phase 3 — ARC + headroom Service + DB on K8s

Cluster scales to zero between PRs.

1. **Namespace + DB/headroom secrets:**
   ```bash
   kubectl create namespace qa
   kubectl create secret generic qa-db -n qa \
     --from-literal=url="$QA_DB_URL"
   # headroom creds from a host where `headroom device add copilot` was run:
   kubectl create secret generic headroom-creds -n qa \
     --from-file=credentials=$HOME/.headroom/credentials
   # Copilot CLI auth (the default engine) — creds from a host where you ran the
   # Copilot CLI login once, plus a Copilot-entitled token for `gh`
   # (see k8s/copilot/copilot-creds-secret.example.yaml):
   kubectl create secret generic copilot-creds -n qa \
     --from-file=$HOME/.copilot
   kubectl create secret generic copilot-gh-token -n qa \
     --from-literal=token="$COPILOT_GH_TOKEN"
   ```
2. **Headroom Service (C2):**
   ```bash
   kubectl apply -f k8s/headroom/deployment.yaml
   kubectl apply -f k8s/headroom/service.yaml
   kubectl rollout status deployment/headroom -n qa
   ```
3. **ARC controller + runner scale set:**
   ```bash
   helm install arc \
     oci://ghcr.io/actions/actions-runner-controller-charts/gha-runner-scale-set-controller \
     -n arc-systems --create-namespace
   kubectl create secret generic qa-arc-github-app -n qa \
     --from-literal=github_app_id=<APP_ID> \
     --from-literal=github_app_installation_id=<INSTALLATION_ID> \
     --from-file=github_app_private_key=<path-to-app.pem>
   helm upgrade --install qa-runner \
     oci://ghcr.io/actions/actions-runner-controller-charts/gha-runner-scale-set \
     -n qa -f k8s/arc/runner-scale-set-values.yaml
   ```
4. **Spot autoscaling:** install cluster-autoscaler or Karpenter so nodes scale to
   0 when idle (spot pool; tolerations already set in the values file).

**Exit:** a manual `repository_dispatch` spins a pod that reaches `AI_BASE_URL`,
runs `impact --mode oneshot`, writes Neon, terminates, and nodes scale to 0.
**Rollback:** set the scale set `maxRunners: 0`; old infra still serves prod.

---

## Phase 4 — Workflows + receiver (shadow target only)

Wire the event flow end-to-end on a **non-prod** target.

1. **Deploy the receiver** (`qa-control/receiver`, Cloudflare Worker shown):
   ```bash
   cd qa-control/receiver
   npm install && npm test
   npx wrangler secret put QA_WEBHOOK_SECRET   # == the App webhook secret
   npx wrangler secret put QA_DISPATCH_TOKEN   # contents:write on qa-control only
   # set QA_CONTROL_OWNER / QA_CONTROL_REPO in wrangler.toml [vars]
   npm run deploy
   ```
2. Point the GitHub App `hook_attributes.url` at the deployed receiver URL.
3. **Resolve payload seeding** (the one open wiring item — see
   `qa-control/receiver/README.md`): choose **A** (receiver upserts `pr_history`)
   or **B** (a tokened pre-impact `--mode seed` step). Without this, the token-free
   impact job has no diff to analyze.
4. Open a real PR on the shadow repo and watch:
   `impact → strategy → BDD PR` → human approves `qa/bdd/*` → `codegen matrix →
   test PR` → approve `qa/tests/*` / request changes → feedback.

**Exit:** the shadow PR drives the full flow; pod counts match the Part J estimate.
**Rollback:** point the receiver away from prod; uninstall the App from the prod target.

---

## Phase 5 — State + monitoring cutover

Make Postgres the source of truth and move tracing off Redis.

- Context-trace sink `RedisTraceSink` → `PostgresTraceSink` (writes `context_history`),
  gated by `aiqa.trace.enabled` (off by default).
- Coverage-learning writes Postgres (`coverage_learning`).
- Each job appends a summary to `$GITHUB_STEP_SUMMARY`.

**Exit:** a full shadow run is reconstructable entirely from Neon.
**Rollback:** flip the sink back to Redis (kept available through Phase 6).

---

## Phase 6 — Parity / shadow comparison

Prove new ≥ old before flipping prod.

- Run the **same** PRs through old (Kafka) and new (Actions) paths.
- Diff: BDD scenarios, context char-count/recall, scenario pass-rate, cost/run
  (job-minutes × runner price).

**Exit:** new path matches or beats old on context recall + coverage; cost/run ≤ target.
**Rollback:** trivial — prod still on the old path.

---

## Phase 7 — Cut prod over

- Point the prod target's App at the qa-control workflows.
- Decommission `_service-deploy.yml` SSH box + docker-compose Kafka/ZK/Redis.

**Exit:** no always-on infra; idle cost → ~$0 (Neon scale-to-zero + tiny headroom
pod + nodes → 0).
**Rollback:** re-enable the deploy box and re-point the App for one release window
(keep old infra warm-but-stopped through Phase 7).

---

## Phase 8 — Decommission & docs

- Delete Kafka config/listeners (or keep behind a dead `legacy` profile for one
  release) · remove ZK/Kafka/Redis from compose.
- Documentation stage updates `QA-ISystem-Architecture.md`, `README.md`,
  `.env.example` to describe the now-real system (precise, DRY).

**Exit:** no always-on infra remains; docs match reality.

---

## Phase 9 — (optional, future) SaaS multi-tenant

Activate the `tenant_id` seam · ingress/App-per-install · per-tenant quotas + cost
attribution. Additive, not a rewrite.

---

## Pod / cost estimate (Part J — 50 PRs × 20 scenarios)

| Per PR | Pods |
|---|---|
| impact | 1 |
| strategy | 1 |
| codegen (matrix, 1/scenario) | 20 |
| gather | 1 |
| feedback (if a round of changes) | +1 |
| **subtotal** | **23 (24 w/ feedback)** |

Cold-start ~20s/pod (warm `.m2` + baked headroom), offset by zero idle infra.
`maxRunners` 60–100 throttles bursts (matrix queues, never drops). Pin to head SHA
+ clone-once→artifact to avoid mid-run drift. Headroom Service is `replicas≥2` +
liveness. No tokens in payloads — minted per-job. Native `concurrency:` +
`dedup_state` kills the historic 4× duplicate-BDD-PR bug. `pr_history.gate_state`
is the single source of truth for human gates across runs.

---

## Quick reference — credentials & where they live

| Secret | Lives in | Used by |
|---|---|---|
| `QA_DB_URL` (Neon) | qa-control repo secret + K8s `qa-db` Secret | workflows + runner pods |
| `QA_APP_PRIVATE_KEY` (PEM) | qa-control repo secret | `create-github-app-token` in each job |
| `QA_APP_ID` | qa-control repo variable | `create-github-app-token` |
| `QA_WEBHOOK_SECRET` | receiver secret | HMAC verify |
| `QA_DISPATCH_TOKEN` | receiver secret | receiver's own dispatch call |
| headroom OAuth (`~/.headroom/credentials`) | K8s `headroom-creds` Secret | headroom pod only |
| Copilot CLI creds (`~/.copilot`) | K8s `copilot-creds` Secret | runner pods (default engine) |
| Copilot-entitled `gh` token | K8s `copilot-gh-token` Secret | runner pods (`gh` auth) |
| ARC App creds | K8s `qa-arc-github-app` Secret | ARC controller |

**Never** commit any of these. Templates only: `k8s/headroom/secret.example.yaml`,
`k8s/neon/qa-db-secret.example.yaml`, `k8s/copilot/copilot-creds-secret.example.yaml`.
`.env`/PEM/credentials stay out of git.
