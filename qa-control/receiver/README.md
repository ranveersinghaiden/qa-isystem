# QA-ISystem webhook receiver

A tiny, stateless serverless function — the **only routing logic** in the
scale-to-zero pipeline (Design doc Decision C4 + Part D). Shown as a Cloudflare
Worker; the same ~100 lines port directly to AWS Lambda / GCP Cloud Function.

## What it does

1. **Verify** the GitHub webhook HMAC (`X-Hub-Signature-256`) in constant time.
2. **Map** the event to a `repository_dispatch` type (Part D):

   | GitHub event | Condition | dispatch type |
   |---|---|---|
   | `pull_request` | `opened` / `reopened` | `pr_opened` |
   | `pull_request` | `synchronize` | `pr_sync` |
   | `pull_request_review` | `approved` + head `qa/bdd/*` | `bdd_approved` |
   | `pull_request_review` | `approved` + head `qa/tests/*` | `tests_approved` |
   | `pull_request_review` | `changes_requested` | `pr_changes_requested` |

3. **Dispatch** `repository_dispatch` at the `qa-control` repo with a minimal,
   **token-free** `client_payload` (`prId, owner, repo, prNumber, branch, headSha`,
   plus `reviewId` for feedback).

The source PR's `prId` is computed deterministically (`PR-XXXXXXXX`, FNV-1a of
`owner/repo#number`) so all three workflow runs and the Neon `pr_history` row agree.
For review events the `prId` is parsed back out of the QA-authored branch name
(`qa/bdd/PR-…`, `qa/tests/PR-…`).

## Configuration

`wrangler.toml [vars]`:
- `QA_CONTROL_OWNER`, `QA_CONTROL_REPO` — where to fire `repository_dispatch`.

Secrets (`wrangler secret put …`, never committed):
- `QA_WEBHOOK_SECRET` — must match the GitHub App webhook secret.
- `QA_DISPATCH_TOKEN` — fine-grained PAT or App installation token with
  `contents:write` on **qa-control only**. This authenticates the receiver's own
  call to the dispatch API; it is never forwarded to workflows.

## Develop / test / deploy

```bash
npm install
npm test          # zero-dependency routing tests (Node 20+/22+), no network/secrets
npm run dev       # wrangler local dev
npm run deploy    # wrangler deploy
```

## Payload seeding — the one open wiring item (Phase 4 tuning)

The `impact` one-shot reads the PR payload (changed files / diff) from Neon by
`prId` (its E.1 job is intentionally token-free). Something must seed
`pr_history.payload` **before** impact runs. This receiver deliberately stays out
of the database to remain small and faithful to Decision C4. Two supported options:

- **A — receiver seeds (heavier receiver):** add a Neon HTTP write here that
  fetches PR files via the GitHub API and upserts a `pr_history` row
  (`gate_state='new'`, `payload` shaped like the `PullRequest` model). Keeps the
  `impact` job token-free.
- **B — tokened pre-impact step seeds (heavier workflow):** add a
  `create-github-app-token` + seed step at the top of `qa-impact-strategy.yml`
  that runs `java -jar /app/pr.jar --mode seed …` (the `pr-service` already knows
  how to fetch + enrich a PR into a `PullRequest`). Keeps the receiver minimal.

Pick one during Phase 4. The Java one-shot runners (`ImpactOneShotRunner` et al.)
read/write `pr_history` via `StateStore`; see their `TODO(migration)` notes.
