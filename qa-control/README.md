# qa-control — QA-ISystem orchestration repo

This directory is the **source of truth for a separate GitHub repository** called
`qa-control`. It holds the orchestration that drives the scale-to-zero QA pipeline
(Design doc Decision C4 + Parts D/E). Target product repos stay clean — they only
install the GitHub App.

> **Why a separate repo?** Decision C4: the orchestration (workflows + receiver)
> is isolated from both the product code (QA-ISystem) and the customer/target
> repos. Move the contents of this directory into a dedicated `qa-control` repo
> before going live.

## Contents

| Path | Purpose |
|------|---------|
| `.github/workflows/qa-impact-strategy.yml` | Run 1 — `repository_dispatch: [pr_opened, pr_sync]` → impact → strategy → BDD PR (E.1) |
| `.github/workflows/qa-codegen.yml` | Run 2 — `repository_dispatch: [bdd_approved]` → prepare → codegen matrix → gather → test PR (E.2) |
| `.github/workflows/qa-feedback.yml` | Run 3 — `repository_dispatch: [pr_changes_requested]` → regenerate (E.3) |
| `receiver/` | Serverless webhook receiver: verify HMAC, map event → dispatch (Part D) |
| `github-app-manifest.json` | GitHub App manifest (webhook events + permissions) |

> The **runner-image build** workflow (E.4) is NOT here — it lives in the source
> repo (`QA-ISystem/.github/workflows/qa-runner-image.yml`) because it compiles
> the 5 service jars. qa-control only orchestrates; it doesn't build code.

## Required GitHub configuration (set in the live qa-control repo)

**Repository variables** (`Settings → Secrets and variables → Actions → Variables`):
- `QA_APP_ID` — the GitHub App's id.

**Repository secrets** (`… → Secrets`):
- `QA_APP_PRIVATE_KEY` — the GitHub App private key (PEM). Used by
  `actions/create-github-app-token` in each job to mint a fresh, target-scoped token.
- `QA_DB_URL` — Neon Postgres connection string (Decision C1).

No long-lived tokens ever travel in dispatch payloads — each job mints its own.

## Event flow (Part D)

```
target PR opened ─► receiver ─► dispatch:pr_opened ─► qa-impact-strategy.yml
                                                         ├─ impact  (no AI)
                                                         └─ strategy ─► BDD PR ─► gate=awaiting_bdd_approval ─► STOP
human approves BDD PR (qa/bdd/*) ─► receiver ─► dispatch:bdd_approved ─► qa-codegen.yml
                                                         ├─ prepare (matrix from DB)
                                                         ├─ codegen (1 pod / scenario)
                                                         └─ gather ─► test PR ─► gate=awaiting_tests_approval ─► STOP
human approves test PR (qa/tests/*) ─► receiver ─► dispatch:tests_approved ─► (finalize)
human requests changes ─► receiver ─► dispatch:pr_changes_requested ─► qa-feedback.yml
```

See `../RUNBOOK-k8s-migration.md` for the full provisioning sequence (Phases 0–8).
