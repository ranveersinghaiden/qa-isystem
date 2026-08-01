# QA-ISystem — AI-driven BDD & Code Generation Pipeline

QA-ISystem is a GitHub-native pipeline that turns a product-repo pull request
into reviewed Cucumber BDD scenarios, and then — once a human merges those
scenarios — into working test automation code (step definitions + page
objects). It lives entirely inside `.github/workflows/` and `.github/scripts/qa-isystem/` in
this repo (`eroad/test-automation`) and is invoked by other product repos as
a **reusable workflow**, or self-tested via a PR trigger inside this repo.

This document explains the architecture, why it is built the way it is, and
how to use and operate it — including a step-by-step guide for adding a
caller workflow to a product repo.

---

## 1. Why Copilot CLI + `GITHUB_TOKEN` (not REST API, not a PAT)

The pipeline went through three designs before landing on the current one:

1. **`actions/ai-inference@v2`** — a GitHub Action that calls a model
   directly. Rejected: no repo file/write access and no "select a custom
   agent" concept, so every agent's rules had to be manually inlined into a
   prompt YAML file and the model's JSON reply parsed/applied by a separate
   workflow step.
2. **Agents REST API** (`POST /agents/repos/{owner}/{repo}/tasks`, or
   `gh agent-task create --custom-agent <name>`) — a real cloud agent with
   repo access and agent selection. Rejected for this org: both the REST API
   and `gh agent-task` **require a personal access token (PAT) with a
   Copilot seat**, and this is an organisation-owned account that cannot
   issue one.
3. **GitHub Copilot CLI (`@github/copilot`), invoked directly on the
   runner** — the one path that accepts the **default, always-available
   `GITHUB_TOKEN`**. This was empirically validated with a real workflow run
   before committing to the rewrite. This is the current, live design.

The CLI's `--agent <name>` flag loads a custom agent's instructions directly
from `.github/agents/<name>.agent.md` — no manual prompt-merging step needed.
Trade-off: the CLI call is **synchronous** — the workflow job blocks until
`copilot` exits, paying for the agent's full working time as runner minutes.
In exchange, no polling loop is needed anywhere in the pipeline (see §6).

---

## 2. High-level architecture

```
Product repo PR opened
        |
        v
Caller workflow in the product repo (or, for self-testing, this repo's qa-isystem-trigger.yml) fetches PR diff/title/body, distills the functional change, and validates it matches the PR description (scope_check -- see §4a) before calling:
        |
        v  [ skipped entirely if scope_check = NO_MATCH ]
qa-isystem-bdd.yml --- generate-bdd job ----------------------------------
  1. Sync product experts (HARD FAIL if package unavailable -- see §6)
  2. Fetch Jira details (.github/scripts/qa-isystem/fetch_jira_summary.sh, direct REST API call)
  2a. Validate functional change matches Jira ticket (scope_check, skipped
      if no real ticket was fetched -- hard-fails the job on mismatch -- §4a)
  3. Build BDD context (.github/scripts/qa-isystem/gather_context.py, embeds the fetched detail)
  4. Compress context (.github/scripts/qa-isystem/caveman_compress.py)
  5. Copilot CLI, agent = Workflow_TestPlanner
     -> uses the Jira ticket detail already baked into the context
     -> opens BDD PR in test-automation, labelled AI_BDD
        |
        v
   [ Human reviews the .feature file(s) ]
        |
   +----+-----+
   |          |
 Merge     Close without merging
   |          |
   v          v
qa-isystem-code.yml     check-rejection-feedback job (stage-level guard)
  generate-code job       - fetches PR reviews/comments via gh
  1. Sync product experts - if NO comments -> skip entirely (normal close)
  2. Fetch Jira details   - if HAS comments -> rebdd-on-rejection runs
  3. Build CODE context              |
  4. Compress                        v
  5. Copilot CLI, agent =  qa-isystem-bdd.yml --- rebdd-on-rejection job -
     Workflow_WebCoder or    1. Sync product experts (HARD FAIL)
     Workflow_MobileCoder    2. Gather rejected PR facts
  -> opens code PR,         3. Fetch Jira details
     labelled AI_CODE           4. Build RE-BDD context (with previous
        |                          attempt + reviewer feedback)
        v                      5. Compress
   [ Human reviews ]           6. Copilot CLI regenerates on the SAME branch
```

`pr-reviewer-assignment.yml` runs independently (event-driven, no polling) to
re-assign QA reviewers on `AI_BDD`/`AI_CODE`-labelled PRs as a backstop.

### 2a. Architecture diagram — the 3 `qa-isystem-*` workflows

The pipeline is built from three `qa-isystem-*` reusable workflows plus one
self-test trigger harness. Box-and-arrow view, one line per component
(numbered so it's easy to recreate in a diagramming tool):

```
[1] qa-isystem-trigger.yml (self-test harness / reference caller)
      1.1 extract job -- diff -> product_name -> logic_change + scope_check (PR desc vs. functional change, §4a)
      1.2 call-bdd job -- gated on: matches_scope == 'true'
                |
                v  (workflow_dispatch, fire-and-forget, passes logic_change)
[2] qa-isystem-bdd.yml -- Stage A
      2.1 generate-bdd job -- sync product experts -> fetch Jira details -> scope_check vs. Jira (§4a) -> guard -> build BDD context -> compress -> Workflow_TestPlanner agent -> opens BDD PR (AI_BDD)
      2.2 check-rejection-feedback job -- stage-level guard, runs on PR close
      2.3 rebdd-on-rejection job -- gather rejected PR facts -> fetch Jira -> rebuild context w/ feedback -> Workflow_TestPlanner regenerates on same branch
                |
                v  (human merges the BDD PR)
[3] qa-isystem-code.yml -- Stage B
      3.1 generate-code job -- triggered on pull_request:closed when an AI_BDD PR is merged (chained from Stage A) -- sync product experts -> fetch Jira details -> build CODE context -> compress -> Workflow_WebCoder / Workflow_MobileCoder -> opens code PR (AI_CODE)
                |
                v  (independent of [1]-[3] -- runs on its own weekly schedule, not chained off a merge)
[4] qa-isystem-i360-autofix.yml -- related, independent pipeline
      4.1 gather-failures job -- weekly regression run -> classify consistently_failing scenarios
      4.2 fix-tests job -- Workflow_Conductor -> Workflow_WebCoder (delegated, no self-commit) -> Security + CodeReviewer + Documentation -> Tester

[5] pr-reviewer-assignment.yml -- event-driven QA-reviewer backstop, triggered independently off AI_BDD/AI_CODE-labelled PRs from [2] and [3]

[6] Shared architecture used by [1]-[4] (§1, §5, §6):
      - GitHub Copilot CLI + default GITHUB_TOKEN (--agent flag loads .github/agents/*.agent.md)
      - .github/scripts/qa-isystem/fetch_jira_summary.sh, gather_context.py, caveman_compress.py
```

Key takeaways:
- **Only `qa-isystem-bdd.yml` and `qa-isystem-code.yml` are chained** —
  merging a BDD PR is what triggers Stage B (`generate-code`). The autofix
  pipeline `[4]` is deliberately **independent**: it runs on its own weekly
  schedule against already-merged regression scenarios, not off the
  BDD→Code chain.
- **`qa-isystem-trigger.yml` `[1]` is a caller, not a stage** — it exists
  purely to exercise `qa-isystem-bdd.yml` via `workflow_dispatch` from inside
  this repo for self-testing; a real product repo supplies its own
  equivalent caller (§9).
- **Both scope-consistency gates (§4a) sit before their respective agent
  runs** — the trigger-workflow gate can skip the entire BDD stage before
  it starts; the BDD-workflow gate can only abort after Jira details are
  fetched, since it needs the ticket content to compare against.
- **All four workflows share one architecture** `[6]` — none of them use
  MCP servers, PATs, or the Agents REST API; every agent invocation is a
  local `copilot --agent <name>` call authenticated with the default
  `GITHUB_TOKEN` (§1).

---


## 3. Workflow-by-workflow reference

### 3.1 `qa-isystem-bdd.yml` — Stage A (owns *all* BDD generation)

Three jobs, discriminated by `inputs.product_name` and PR event state:

| Job | Trigger | Guard | What it does |
|---|---|---|---|
| `generate-bdd` | `workflow_dispatch` only | `inputs.product_name != ''` | Syncs product experts (hard fail) → fetches Jira details (direct REST API call) → builds BDD context (embeds the fetched detail) → compresses → runs `Workflow_TestPlanner`. The agent uses the Jira ticket detail already baked into the context, opens a BDD PR (title starts with Jira ID), stamps a `HISTORY` block with `Domain:`/`Product:` lines, applies `AI_BDD`. |
| `check-rejection-feedback` | native `pull_request: types: [closed]` | `inputs.product_name == '' && merged == false && label AI_BDD` | **Stage-level guard**: fetches the closed PR's reviews and comments. Outputs `has_feedback=true/false`. If no comments → `rebdd-on-rejection` never starts (treated as a normal close, not a rejection). |
| `rebdd-on-rejection` | native `pull_request: types: [closed]` | same as above **plus** `needs.check-rejection-feedback.outputs.has_feedback == 'true'` | Syncs product experts → gathers rejected PR facts (branch, Jira ID, previous attempt, reviewer feedback) → fetches Jira details → rebuilds context with feedback → runs `Workflow_TestPlanner` on the **rejected PR's own branch** (no new PR — regenerates in place) → re-assigns QA reviewers. |

`generate-bdd` is **only** reachable via `workflow_dispatch` — it runs as its
own run inside this repo, fired remotely by a caller workflow (a product
repo's own trigger workflow via `gh workflow run`/REST dispatch, or this
repo's `qa-isystem-trigger.yml` for self-testing). There is no `workflow_call`
trigger on this file any more — external callers no longer execute this
job inside their own run (that required cross-repo checkout access this
private repo could not grant).
The only native PR trigger in this file is `pull_request: types: [closed]`,
and it exists solely for the rejection loop.

**Key inputs (`workflow_dispatch`):** `domain` (optional), `product_name`
(**required**), `source_repo`, `pr_number`, `pr_title`, `logic_change`
(plain-English functional-change description — see §8), `pr_body` (optional),
`jira_id` (**required** — the caller must resolve this itself, e.g. by
grepping the PR title, before calling; see §9.4).

**Secrets:** passed via `secrets: inherit` from the caller. `GITHUB_TOKEN`
is used for checkout/reviewer assignment. `JIRA_CLOUD_ID` / `JIRA_EMAIL` /
`JIRA_API_TOKEN` are passed directly to the "Fetch Jira details" step
(`.github/scripts/qa-isystem/fetch_jira_summary.sh`), which calls the Atlassian API gateway
(`api.atlassian.com/ex/jira/<cloudId>`) over HTTPS with basic auth — no MCP server, no `copilot mcp add`, and the
`copilot` CLI step itself never sees these secrets.

**Permissions:** `contents: write`, `pull-requests: write`, `models: read`,
`packages: read` (needed for the product-experts sync).

### 3.2 `qa-isystem-code.yml` — Stage B

Single job, `generate-code`, triggered on `pull_request: types: [closed]`,
guarded by `merged == true && label AI_BDD`.

Steps: **sync product experts** (hard fail) → gather merged BDD PR facts
(`.feature` file paths → product inferred via `--infer-product-from`, Jira ID
from the BDD PR title) → **fetch Jira details** (direct REST API call) →
build code task context (`--task-description` contains the approved
`.feature` file list plus full verbatim scenario content, `--logic-change`
carries the BDD PR body, Jira detail embedded) → dynamically resolve coder
agent: `Workflow_WebCoder` or `Workflow_MobileCoder` based on resolved
product surface → compress → run coder agent via Copilot CLI. The coder
implements step definitions + page objects, opens a PR linked to the merged
BDD PR + Jira ticket, labels it `AI_CODE`.

**Permissions:** `contents: write`, `pull-requests: write`, `models: read`,
`packages: read`.


### 3.3 `qa-isystem-trigger.yml` — self-test harness / reference caller

Lets you exercise the pipeline **without a separate product repo**.
Triggered on `pull_request: types: [opened]` inside `test-automation` itself.
Guarded: skips PRs already labelled `AI_BDD`/`AI_CODE`.

**Job `extract`:**
1. Fetches PR diff/title/body/changed-files via `gh`.
2. Infers `product_name` via `gather_context.py --infer-product-from`.
3. Distills the diff into tech-agnostic functional changes (logic ported
   inline — no external script dependency).
4. Installs Copilot CLI and summarizes into **plain-English prose** via
   `gpt-5-mini` (cheapest model — cosmetic, not code-generation work).
5. **Validates functional change matches PR description** (`scope_check`
   step) — a second, separate `gpt-5-mini` call compares that plain-English
   summary against the PR's own title/body and sets a `matches_scope`
   (`true`/`false`) **job output**; the step also emits a one-line `reason`
   **step output** (used only in the Step Summary, not promoted to a job
   output — see §4a for the full rationale and prompt contract). This
   check is Jira-agnostic — it only compares the diff-derived summary
   against what the PR author wrote.

**Job `call-bdd`** (`needs: [extract]`, `if:
needs.extract.outputs.matches_scope == 'true'`): calls `qa-isystem-bdd.yml`
via `uses: ./.github/workflows/qa-isystem-bdd.yml` + `secrets: inherit`,
passing the plain-English summary as `logic_change`. If the scope check
failed, this job — and therefore the entire BDD stage — is skipped, and the
Step Summary explains why.

This workflow is the reference implementation for any product-repo caller
(see §9 for the step-by-step guide).

### 3.4 `pr-reviewer-assignment.yml` — defensive QA-reviewer backstop

Triggered on `pull_request: types: [opened, labeled]`, guarded to only act
when `AI_BDD` or `AI_CODE` is present. Re-derives `Domain:`/`Product:` from
the PR `HISTORY` block, resolves QA git handles via `--emit-qa-handles`, and
calls `gh pr edit --add-reviewer` (idempotent — no-op if already assigned).

### 3.5 `qa-isystem-i360-autofix.yml` — related pipeline (weekly regression autofix)

A separate, independent pipeline that reuses the same Copilot CLI +
`GITHUB_TOKEN` architecture (§1) but has nothing to do with BDD/code
generation from a product-repo PR — it exists to autonomously fix i360
**web** regression test failures that `i360_regression_tests.yml` already
found and classified.

Two jobs:

| Job | What it does |
|---|---|
| `gather-failures` | Runs `.github/scripts/qa-isystem/classify_i360_failures.py` (same classifier the daily `i360_regression_tests.yml` "Classify I360 Failures" job uses) to get each test's history classification, then `.github/scripts/qa-isystem/gather_i360_autofix_batches.py` (§6) to pull real failure/error text for `consistently_failing` tests only and split them into up to 10 batches. Uploads the batches as the `i360-autofix-batches` artifact. |
| `fix-tests` | Matrix job (one per non-empty batch, up to 10 in parallel). Downloads its batch, builds a task blob listing each failing test + its error message, and runs `copilot --agent Workflow_Conductor --allow-all-tools --no-ask-user`. `Workflow_Conductor` (§5) is the CI-only, unattended variant of the local `Conductor` orchestrator — it delegates the actual fix (allow-list only: locator/UI changes and missing test data; timing, assertion drift, and other root causes are flagged for human review instead) to **`Workflow_WebCoder`** (not base `WebCoder` — it needs the same full CI git/GitHub write permissions this job actually runs with, and is explicitly told not to commit/push/open its own PR since `Workflow_Conductor` owns that end-to-end), runs **Security** + **CodeReviewer** + **Documentation** on the result (iterating up to 10 times if either finds blocking issues), then — since there's no human approval gate in this unattended context — commits to its own `autofix/i360-regression/batch-<n>-run<run_id>` branch and opens its own PR labelled `AI_CODE` + `AI_AUTOFIX` itself (picked up by `pr-reviewer-assignment.yml` like any other agent PR). |

**Scope for now:** only the `consistently_failing` classification (fails in
every observed run over the lookback window) is actionable —
`new_failure`/`flaky`/`stable_pass` are intentionally excluded until this
pipeline proves itself; widen scope later via `ACTIONABLE_CLASSIFICATIONS`
in `.github/scripts/qa-isystem/gather_i360_autofix_batches.py`.

**Schedule:** weekly, `cron: '0 18 * * 6'` (~Sunday 06:00 NZST, fixed UTC
offset like the daily workflow's own cron — no NZDT adjustment). Also
supports `workflow_dispatch` with `batches` / `classification_lookback_days`
inputs.

**Permissions:** `gather-failures` needs job-level `actions: write` (on top
of the workflow-level `contents: read`) for
`actions/upload-artifact@v5`. `fix-tests` needs `contents: write`,
`pull-requests: write`, `issues: write` (to open/update the companion
tracking issue for flagged items), `models: read`, `packages: read`,
`copilot-requests: write`, and `actions: read` — this is broader than
`qa-isystem-code.yml`'s coder job, which has no `issues: write`.

---

## 4. Fetching Jira details

Every job that builds a BDD/code context (`generate-bdd`, `rebdd-on-rejection`,
`generate-code`) runs a **"Fetch Jira details"** step immediately before its
"Build ... context" step, calling `.github/scripts/qa-isystem/fetch_jira_summary.sh` directly —
no MCP server, no Copilot CLI involvement at this point. It:

1. Resolves the Jira ticket ID first — `generate-bdd` requires `jira_id` as a
   mandatory `with:` input (the caller resolves it, e.g. via PR-title grep,
   before calling); `rebdd-on-rejection` and `generate-code` read it back
   from `steps.facts.outputs.jira_id`.
2. Calls the Atlassian API gateway (`api.atlassian.com/ex/jira/<cloudId>/rest/api/3/issue/<id>?fields=summary,description,comment`)
   directly over HTTPS with basic auth, using the `JIRA_CLOUD_ID` / `JIRA_EMAIL` /
   `JIRA_API_TOKEN` secrets passed straight as script args — no
   `ATLASSIAN_*` env var aliasing, no `copilot mcp add`.
3. Extracts the issue's `summary`, `description`, and `comment` fields
   (converting Atlassian Document Format to plain text via `jq`) and
   concatenates them into a single `Summary: ...` / `Description: ...` /
   `Comments: ...` plain-text string, writing it to `jira-details.md`.
4. **Soft-fails everywhere** (no ticket ID, missing/invalid secrets, ticket
   not found, network error): writes a `JIRA_ERROR: ...` placeholder to
   `jira-details.md` and exits 0 — never breaks the workflow.

`jira-details.md` is then passed to `gather_context.py` via
`--jira-details @jira-details.md`, which embeds it verbatim in the context
under a `## Jira ticket detail` section (see §6) — the `Workflow_*` agent
never has to fetch it itself, and there is no Copilot-CLI-runtime MCP-server
dependency for Jira access at all anymore (this previously failed in
production with `! 1 MCP server was blocked by policy: 'mcp-atlassian'`,
silently degrading every run to the no-Jira-context fallback).

The base **`Atlassian`** agent (`.github/agents/Atlassian.agent.md`) and
`scripts/setup-mcp-servers.sh`/`.ps1` still exist for **local, interactive**
Task-tool use (e.g. via `Conductor` — see §5) — they are unrelated to this
CI-time fetch and unaffected by this change.

---

## 4a. Scope-consistency gates (functional change vs. PR description / Jira)

Two independent, non-agent workflow steps guard against generating BDD
scenarios or code for a PR whose actual diff doesn't match what it claims to
do. Both use a plain inline `copilot --model gpt-5-mini --no-ask-user -p
"..."` judgement call (not an agent, no repo write access needed) that must
respond with exactly one line — `MATCH` or `NO_MATCH: <reason>` — which the
step parses to set a `matches_scope` output.

**1. `qa-isystem-trigger.yml` — PR description vs. functional change.**
Before calling `qa-isystem-bdd.yml` at all, the `extract` job distills the
PR's diff into a plain-English functional-change summary (this is the same
text later passed through as `inputs.logic_change`), then runs a
`Validate functional change matches PR description` (`scope_check`) step
comparing that summary against the PR's own title/body. `call-bdd` is gated
with `if: needs.extract.outputs.matches_scope == 'true'` — on a mismatch the
BDD workflow is never invoked at all, and the Step Summary explains why
(e.g. the PR body describes something the diff doesn't actually do, or vice
versa). This check does **not** involve Jira — it is a pure
description-vs-diff sanity check that runs regardless of whether a Jira
ticket exists.

**2. `qa-isystem-bdd.yml` (`generate-bdd` job) — functional change vs. Jira
ticket.** Immediately after the "Fetch Jira details" step (§4) and before
"Build BDD context", a `Validate functional change matches Jira ticket`
(`scope_check`) step:
- Skips the check (auto `matches_scope=true`) if `jira-details.md` starts
  with `JIRA_ERROR:` — i.e. no real ticket content was fetched, so there is
  nothing to compare against and BDD generation proceeds as before, grounded
  on `logic_change`/`pr_body` alone.
- Otherwise asks the model whether `inputs.logic_change` (and, as
  supplementary context, `inputs.pr_body`) is actually described — even at a
  higher, less detailed level — by the fetched ticket's summary, description,
  and acceptance criteria. The prompt explicitly tolerates the ticket being
  less detailed than the change; it should only flag `NO_MATCH` when the
  change is materially different from, unrelated to, or contradicts what the
  ticket describes (wrong ticket ID referenced, unrelated feature, clearly
  out-of-scope change).
- A following `Guard - functional change matches Jira ticket` step hard-fails
  the job (`exit 1` with an `::error::` message including the reason) if
  `matches_scope != 'true'`. Unlike the trigger-workflow check, this one
  **does** hard-block — `jira_id` is a mandatory input on `generate-bdd`
  (§9.4), so a real ticket is always expected once fetched successfully, and
  there is no earlier gate in the pipeline that would otherwise catch a
  Jira/change mismatch.

Both checks are deliberately **judgement calls, not exact-string matches** —
they tolerate paraphrasing, differing levels of detail, and additional scope
in the PR/ticket, and only reject genuine mismatches. Neither check runs in
`rebdd-on-rejection` (§3.1) — that job regenerates scenarios from human
rejection feedback on an already-approved-scope PR, not from a fresh
Jira/diff comparison, so a scope gate there would be redundant with the
human rejection itself.

**Shell-safety hardening.** Because the model's judgement text and the PR's
own title/body/diff are all attacker-influenceable (a malicious or
malformed PR could contain shell metacharacters or GitHub Actions workflow
command syntax), both `scope_check` steps and their downstream consumers
follow a few defensive rules:
- The `reason` step output is populated from **only the first line**
  (`$RESULT`) of the model's response, never the full multi-line file —
  even though the prompt asks for one line, the step never trusts the model
  to honor that contract.
- `reason` is never interpolated directly into a double-quoted
  `echo "...${{ ... }}"` string in a later step; it is passed through
  `env:` and referenced as a shell variable (`$SCOPE_REASON`) instead, so a
  crafted response can't trigger shell command substitution (e.g. `$()`).
- Untrusted/model-derived text (`$LOGIC_CHANGE`, `$PR_BODY`,
  `$SCOPE_REASON`) is emitted with `printf '%s\n' "$VAR"` rather than
  `echo "$VAR"`, since `echo` can misinterpret a leading `-n`/`-e` as its
  own option instead of literal text.
- The guard step additionally collapses `$SCOPE_REASON` to a single line
  (`tr '\n' ' '`) before printing it, as defense-in-depth against stray
  newlines that could otherwise emit extra log lines or be parsed as GitHub
  Actions workflow commands.
- The raw model response file (`scope-check-result.txt`) is never `cat`'d
  straight to the Actions log; only `$RESULT` (the first line) is logged,
  via `printf 'Scope check result: %s\n' "$RESULT"`, so a response whose
  first characters happen to be `::` can't be interpreted as a workflow
  command.


---

## 5. Agent files: `Workflow_*` vs. base agents

`.github/agents/` contains agent definitions. Four have a `Workflow_`
prefix: **`Workflow_TestPlanner`**, **`Workflow_WebCoder`**,
**`Workflow_MobileCoder`** (all three used by §3.1/§3.2's BDD/code
pipeline), and **`Workflow_Conductor`** (used by §3.5's independent
regression-autofix pipeline).

- **Base agents** (`TestPlanner`, `WebCoder`, `MobileCoder`, `Atlassian`,
  `Conductor`, etc.) are used locally as Task-tool sub-agents — read-only
  with respect to git; a human always reviews and performs the actual
  commit/push. `Conductor` may still delegate to the local `Atlassian` agent
  (via MCP, in an interactive IDE session) when a human is driving it.
- **`Workflow_*` agents** are CI-only variants invoked exclusively by
  `copilot --agent Workflow_X` inside these GitHub Actions workflows. They
  grant themselves full git/GitHub write access and commit/push/open the PR
  themselves. `Workflow_TestPlanner`/`Workflow_WebCoder`/
  `Workflow_MobileCoder` **no longer delegate to the `Atlassian` agent or
  any `mcp-atlassian` MCP server** — the calling workflow's own "Fetch Jira
  details" step (§4) already embeds the ticket detail in the context before
  the agent runs, so each of their "Step 0/1" now just reads the pre-fetched
  `## Jira ticket detail` section instead.
  `Workflow_Conductor` is different in shape from the other three: it has no
  Jira/BDD context at all (its task blob is a list of failing tests + error
  messages from §3.5's `gather-failures` job) and it does not implement code
  itself — it is the CI-only, unattended variant of the local **`Conductor`**
  orchestrator, reusing the same delegation model but delegating the actual
  fix to **`Workflow_WebCoder`** rather than base `WebCoder` (matching the
  full CI git/GitHub write permissions this job runs with — `Workflow_Conductor`
  explicitly instructs it not to commit/push/open its own PR,
  since `Workflow_Conductor` owns that end-to-end itself), then runs
  **Security** + **CodeReviewer** + **Documentation** (both still base
  agents — no `Workflow_*` variant of either exists), iterating up to 10
  times. Unlike base `Conductor`'s hard "never run a git write action, ever"
  rule, `Workflow_Conductor` is explicitly allowed — and required — to
  commit, push, and open the PR itself, since §3.5 runs fully unattended
  (weekly cron / `workflow_dispatch`, no human in the loop that week).

**Conductor must never delegate to a `Workflow_*` agent** as a Task-tool
sub-agent. `Workflow_*` agents belong to the CI pipeline alone.

---

## 6. Scripts

### `.github/scripts/qa-isystem/local_generation.py` and `run_local_generation.py`

The local-first generation engine is the shared deterministic layer for local
QA-ISystem use and the cloud workflows. It discovers bounded repository
context, renders BDD or test skeletons when confidence permits, validates the
result, and records whether a cheap model, premium model, or human review is
actually required.

Run the engine directly when a complete `AutomationTask` JSON is available:

```bash
python3 .github/scripts/qa-isystem/local_generation.py \
  --repo . \
  --task task.json \
  --output local-generation.json
```

To construct the same task contract used by the workflows, provide Jira and
change-summary files:

```bash
python3 .github/scripts/qa-isystem/run_local_generation.py \
  --repo . \
  --task-type BDD_GENERATION \
  --task-id jira:PROJ-123 \
  --repository eroad/test-automation \
  --branch "$(git branch --show-current)" \
  --commit-sha "$(git rev-parse HEAD)" \
  --product-context Core360 \
  --jira-context-file jira-details.md \
  --diff-summary-file change-summary.md \
  --impacted-component fleet \
  --output local-generation.json
```

Treat `plan.generation_mode`, `validation`, and `artifact` as a contract.
`observability` is a privacy-safe per-run routing summary: selected
pack/domain/source, outcome/escalation stage, validation/repair state,
unresolved-gap count/categories, and provider/model tier only when used.
It is emitted only in CLI JSON (stdout without `--output`, otherwise the
explicit output file); engine sends or persists no telemetry itself.
`human_review_required` means engine needs review, not a reviewer decision;
a downstream review system may record later acceptance or rejection.
Do not invoke an LLM when a valid `DETERMINISTIC` artifact is returned.
Apply a `LOCAL_FIX` before any model escalation, use `CHEAP_LLM` before
`PREMIUM_LLM`, and preserve `HUMAN_REVIEW` items as explicitly unresolved.

The BDD, re-BDD, and code workflows regenerate
`governance/local-generation.json` for the checked-out commit before their
agents start. Agents reject an absent, stale, or invalid contract; a
`HUMAN_REVIEW` plan produces only a clearly marked draft. Local contributors
should run:

```bash
python3 -m pytest .github/scripts/qa-isystem/tests/test_local_generation.py -q
python3 -m py_compile \
  .github/scripts/qa-isystem/local_generation.py \
  .github/scripts/qa-isystem/run_local_generation.py
```

Update engine tests whenever a task field, routing rule, template, validator,
or contract shape changes. Add a regression fixture for a production routing
failure before changing its policy.

#### Composable engine architecture

The local engine is deliberately a set of deterministic, independently
validated capabilities rather than a single broad prompt:

`local_generation.py` is the stable CLI and import facade. The implementation
is separated by responsibility: `qa_engine_models.py` (contracts/providers),
`qa_engine_context.py` (bounded evidence), `qa_engine_generators.py`
(deterministic artifacts/review), `qa_engine_validators.py` (validation),
`qa_engine_repair.py` (safe repairs), and `qa_engine_router.py` (escalation).
Consumers must continue importing from `local_generation.py`.

#### Pack routing architecture

Core owns shared contracts, bounded evidence, validation aggregation, safe
repair, router, provider sequence, escalation: `qa_engine_models.py`,
`qa_engine_context.py`, `qa_engine_generators.py`,
`qa_engine_validators.py`, `qa_engine_repair.py`, `qa_engine_router.py`.
Core must not add test-type policy branches or copy provider escalation.

`qa_engine_packs.py` defines typed `GenerationPack`, `PackRegistry`, and
`PackSelection`. Small built-in policy modules are
`qa_engine_pack_bdd.py`, `qa_engine_pack_web.py`,
`qa_engine_pack_api.py`, `qa_engine_pack_mobile.py`. Packs own only
deterministic artifact policy and domain static validation.

`AutomationTask.test_domain` supports explicit `bdd`, `web`, `api`, `mobile`;
explicit wins. Direct artifact requests: `WEB_TEST_GENERATION`,
`API_TEST_GENERATION`, `MOBILE_TEST_GENERATION`. Existing BDD, step,
page-object, skeleton, gap, fix, validation task types stay compatible.
Without explicit domain registry infers from `target_test_type`, then
impacted module/path. Unknown or conflicting evidence safely selects generic
BDD, records fallback reason, can escalate human review; never crashes.

Router selects one pack, records domain/inference in `LocalContextBundle`,
then runs shared plus selected-pack validation and shared `LOCAL_FIX`.
Only router owns existing `CHEAP_LLM` → `PREMIUM_LLM` → `HUMAN_REVIEW`
provider escalation.

| Pack | Direct request or inference | Deterministic policy |
| --- | --- | --- |
| BDD | `BDD_GENERATION` | Tagged Gherkin; one Given/When/Then; duplicate scenario/step checks |
| Web | `WEB_TEST_GENERATION`, web target/path | `@Slf4j`, thin patterns, no direct `page.`/driver, type-prefixed generated locators |
| API | `API_TEST_GENERATION`, API target/path | Lazy config, input guards, writes-only `EnvGuard`, explicit success, delete logging, lowercase map keys |
| Mobile | `MOBILE_TEST_GENERATION`, mobile target/path | ActionEngine-only, accessibility-id-first, no PageFactory/static driver/step config |

Context remains bounded. Selected evidence files only provide endpoints,
selectors, API operations; packs leave unresolved TODO when locator/endpoint
evidence missing, never invent one. Add a domain by implementing small
`qa_engine_pack_<domain>.py`, registering in `default_pack_registry`, adding
inference/unsafe-artifact regressions, documenting policy here. Do not copy
router, repair, or LLM providers.

| Capability | Task type | Local result |
| --- | --- | --- |
| BDD generation | `BDD_GENERATION` | Template-selected Gherkin with duplicate and convention validation |
| Step-definition generation | `STEP_DEFINITION_GENERATION` | Thin Cucumber glue using a verified zero-argument local action when available |
| Page-object generation | `PAGE_OBJECT_GENERATION` | A `@Slf4j` page-object shell that never introduces direct driver access |
| Test skeleton or gap generation | `TEST_SKELETON_GENERATION`, `TEST_GAP_GENERATION` | Target package, imports, and skeleton derived from repository evidence |
| Deterministic review | `VALIDATION_ONLY` | Static findings for prohibited waits, missing page/screen logging, and duplicate Cucumber patterns |

`LocalContextBuilder` is the bounded evidence layer. It ranks changed files,
features, step definitions, action/page-object classes, fixtures, endpoints,
and Graphify terms; it also records discovered method signatures, qualified
imports, existing step patterns, conventions, and confidence. Generators may
only use that evidence. This prevents unbounded repository discovery and lets
the engine explain every deterministic decision.

All outputs pass symbol, duplicate, step-definition, page-object, convention,
syntax, optional build, and optional lint validators. The router applies a
local repair first. Only an unresolved, validated gap may call a cheap model;
if that response fails local validation twice, the router may call the premium
model. A premium-model response that fails validation becomes `HUMAN_REVIEW`.
The contract records every route event, provider call, validation result, and
unresolved gap so cost, quality, and escalation rates can be measured.

#### Monthly evidence-backed pattern updates

`.github/workflows/qa-isystem-pattern-updates.yml` runs monthly on the first
UTC day and may
open an `AI_QA_ENGINE` pull request when recent repository changes establish a
reusable deterministic pattern. It creates the local evidence contract before
invoking `Workflow_QaEngineMaintainer`; the agent is limited to engine source,
tests, and this document. It must add focused regression coverage and pass
the engine test suite, and it creates no pull request when no evidence-backed
update is found.

### `.github/scripts/qa-isystem/sync_product_experts.sh`

Pulls the `@eroad/product-experts` npm package from GitHub Packages into
`productExperts/remote/<product>/` so agents have up-to-date domain knowledge.

**This step runs first in every job (immediately after Checkout) and HARD
FAILS on any error** — package access denied, npm error, empty experts
directory. Product expert knowledge is critical for high-quality AI output.

**One-time CI setup:** a package admin must grant
`eroad/test-automation` read access to the package at
`https://github.com/orgs/eroad/packages/npm/%40eroad%2Fproduct-experts`
(Package Settings → "Manage Actions access"). The QA-ISystem workflows already
request `packages: read`; this repository-specific package grant is the
remaining required authorization.

The only exception to hard-failing is a missing `NODE_AUTH_TOKEN` (local/non-CI
runs) — exits 0 with a warning; in CI the token is always set.

**Local setup:** the script automatically uses `gh auth token` when
`NODE_AUTH_TOKEN` is unset. Grant package access to the GitHub CLI session with
`gh auth refresh -h github.com -s read:packages`, then complete EROAD SSO
authorization if prompted. Alternatively, set `NODE_AUTH_TOKEN` to an
SSO-authorized token with `read:packages`. Use
`-Dskip.product-experts-sync=true` only for intentional offline local builds;
it does not repair package synchronization.

### `.github/scripts/qa-isystem/fetch_jira_summary.sh`

Stdlib-only script that fetches a single Jira ticket's detail directly from
the Atlassian REST API (`/rest/api/3/issue/<id>`, basic auth via
`--email`/`--token`) and formats it as markdown (summary, status, labels,
description, linked issues, last few comments). Run by every job's "Fetch
Jira details" step (§4), **before** `gather_context.py`, so the ticket detail
can be embedded in the context up front rather than fetched by the agent at
runtime.

Key flags: `--jira-id`, `--base-url`, `--email`, `--token`, `--output`.
**Soft-fails everywhere** — no ticket ID, missing/invalid credentials, ticket
not found, network error: writes a `JIRA_ERROR: ...` placeholder to
`--output` and exits 0, so the calling workflow always proceeds.

### `.github/scripts/qa-isystem/gather_context.py`

Deterministic context-distillation engine shared by all callers.

Output shape (in order):
1. Jira reference line — `- Jira: **JIRA:<id>** - full ticket detail fetched
   below...` when `--jira-details` is present and not a `JIRA_ERROR`
   placeholder, a "live ticket fetch was unavailable" note when it is, or a
   "not provided" note when there's no Jira ID at all
2. `## Jira ticket detail` section (the embedded, pre-fetched markdown from
   `.github/scripts/qa-isystem/fetch_jira_summary.sh` — only present when the fetch succeeded)
3. Target block: Domain / Target product / Test module + directory keyword /
   QA reviewers (resolved via product-map — see §7)
4. Source PR line
5. `- Product knowledge: productExperts/remote/<dir>/` (path to the synced
   expert directory, or "none synced" if unmapped)
6. `## Logic change` (from `--logic-change` or `--pr-body` fallback)
7. `## Previous attempt` / `## Reviewer feedback` (rejection-loop path only)
8. `## Task` (from required `--task-description`)

Key flags: `--product-name`, `--domain`, `--jira-id`, `--jira-details`
(pre-fetched ticket markdown from `.github/scripts/qa-isystem/fetch_jira_summary.sh`, inline
text or `@file`/`@-`), `--logic-change` (plain-English text or `@file`/`@-`),
`--task-description` (**required**, also supports `@file`/`@-`),
`--pr-title`/`--pr-body`, `--previous-attempt` / `--review-comments`,
`--enforce-jira-gate` (hard-fails exit 4 unless at least one grounding source
is present), `--infer-product-from <file>` (coarse product-key inference
from a newline-separated changed-file list), `--emit-jira-id` (resolves and
prints the Jira ticket ID from `--jira-id`/`--pr-title`/`--pr-body` — a
helper for callers to resolve `jira_id` themselves before invoking
`qa-isystem-bdd.yml`, see §9.4; not used internally by `generate-bdd` since
`jira_id` is now a mandatory caller-supplied input), `--emit-agent`
(prints resolved coder agent name), `--emit-qa-handles` (prints resolved QA
reviewer git handles, comma-joined).

### `.github/scripts/qa-isystem/caveman_compress.py`

Compresses the generated task blob before passing it to the CLI — protects
Jira IDs, file paths, `CONST_CASE` identifiers, and dotted method calls from
being mangled, while stripping filler words to reduce token usage.

### `.github/scripts/qa-isystem/classify_i360_failures.py`

Used by §3.5 (and by `i360_regression_tests.yml`'s own daily "Classify I360
Failures" job). Walks completed `i360_regression_tests.yml` runs over a
lookback window, reads each run's consolidated cucumber JSON artifacts, and
classifies every test by its pass/fail history into `consistently_failing`,
`new_failure`, `flaky`, `stable_pass`, or `insufficient_history`. Writes
`--out-json`/`--out-csv`. Does not carry error messages/stack traces — only
pass/fail/skip per run.

Key flags: `--owner`, `--repo`, `--workflow`, `--lookback-days`,
`--history-event` (`schedule`/`workflow_dispatch`/`all`), `--suite`
(repeatable), `--filter-selected-only`, `--include-run-id`,
`--min-observations`.

### `.github/scripts/qa-isystem/gather_i360_autofix_batches.py`

Feeds §3.5's `fix-tests` matrix. Combines the classification report above
(filtered to `ACTIONABLE_CLASSIFICATIONS` — currently `{consistently_failing}`
only) with the latest completed scheduled run's own cucumber JSON artifacts,
re-read here purely to pull each failed scenario's `error_message`/failed
step text (the classifier above doesn't carry this). Splits the actionable
tests round-robin into `--batches` (default 10) files
(`batch-<n>.json`, each `{batch, run_id, tests: [...]}`) plus a
`matrix.json` GitHub Actions matrix (`{"include": [{"batch": 0}, ...]}`) —
only non-empty batches get an entry, or a single `{"batch": -1}` sentinel if
nothing is actionable (so the calling workflow's matrix step, which no-ops
on `batch == -1`, never sees an invalid empty `include` list).

Key flags: `--owner`, `--repo`, `--workflow`, `--classification-report`,
`--run-lookback-days` (window to find the latest scheduled run for error
messages — independent of the classifier's own, usually much longer,
`--lookback-days`), `--batches`, `--out-dir`. Reuses artifact/pagination
helpers directly from `classify_i360_failures.py` (same `.github/scripts/qa-isystem/`
directory) rather than re-implementing GitHub API plumbing.

---

## 7. Product/target resolution

`.agents/knowledge/product-map.json` + `.agents/knowledge/squads.json` are the router:

- `squads.json`: `squads[<squad name>]` → `{domain, product_owner, qa_responsible,
  qa_git_handle, products[]}`. Each product entry has `name`, `platform`, `description`,
  and a `product_mapping` (`{portal, screen}`, or `null` if not yet mapped).
  `resolve_product_target()` scans this file for a case-insensitive `name` match to get
  the owning squad (→ domain, QA git handles) and its `product_mapping`.
- `product-map.json`'s `portals` section maps a portal key (`coretex-360`, `myeroad`,
  `coretex-360-integrations`, `myeroad-integrations`, `apps`) → `{file, test_module}`,
  pointing at one of the 5 portal files under `.agents/knowledge/products/`. Note the
  module key is **not** uniformly named: most portals use a singular `test_module`
  string, but `apps` (which spans more than one module) uses a `test_modules` list
  instead — and individual nodes *inside* a portal file can override the portal-level
  value with their own `test_module`/`test_modules`. `_resolve_portal_screen()` in
  `.github/scripts/qa-isystem/gather_context.py` checks node-level singular → node-level list →
  portal-level singular → portal-level list, in that order, and always returns a list.
- Each portal file holds `domains`/`apps`/`groups` (portal-specific top level), each with
  a `directory_keyword` and optional `feature_files`, optionally nested one level deeper
  (`sub_domains`/`screens`). `resolve_product_target()` walks a product's `screen` path
  (e.g. `"Driver Performance / Safety > Dual Fatigue"`) through this structure to resolve
  the final `directory_keyword`.
- This is a portal-first structure (built from each portal's live nav-bar layout, mapped
  against QA coverage data) that replaced an earlier domain-first structure
  (`domain_index`/`search_aliases`/`qa_squad_map`), which is retired — those files'
  `module`/`directory-name-keyword` fields were never populated and made automated
  routing impossible. Note only a subset of products currently have a `product_mapping`
  (see each product's `product_mapping: null` in `squads.json`); everything else degrades
  to empty module/directory-keyword and needs either a human to add the mapping or the
  calling agent to infer the target from ticket text/repo conventions.

`--infer-product-from` is a coarser, purely path-based classifier — independent of
`squads.json`/`product-map.json` lookups, used only by the code stage to infer a coarse
product key from a BDD PR's changed `.feature` paths. Only `drive` and
`eruc`/`eruc-mobile` → `eruc b2c` are real mappings today; anything else degrades
gracefully (empty module/keyword/QA handles).

---

## 8. Contracts worth knowing before you touch this

- **`logic_change` is plain-English text, not a raw diff.** Pass a
  plain-English summary (e.g. the Copilot CLI summarization in
  `qa-isystem-trigger.yml`), not a unified diff.
- **`--task-description` is required** for every real `gather_context.py`
  invocation (exit 6 if missing).
- **Jira ticket content is fetched by the workflow, before the agent runs —
  not by the agent, and not via MCP.** The workflow's own "Fetch Jira
  details" step calls `.github/scripts/qa-isystem/fetch_jira_summary.sh` (direct Atlassian REST
  API call) and hands the result to `gather_context.py --jira-details`,
  which embeds it in the context. `Workflow_*` agents no longer delegate to
  the **Atlassian** agent or any `mcp-atlassian` MCP server for this.
- **`HISTORY` block contract**: every BDD/code PR the agents open must
  reproduce `Domain: <domain>` and `Product: <product_name>` verbatim near
  the top of the PR body — downstream stages read these back without
  re-inferring them.
- **Rejection-loop comment guard is at job level** (`check-rejection-feedback`
  job) — `rebdd-on-rejection` never starts for silent closes. There are no
  step-level guards inside it.
- **Reusable-workflow permissions can only be reduced, not elevated, through
  a call chain.** The caller workflow only needs `contents: read` /
  `pull-requests: read` for its own steps.
- **Labels drive everything**: `AI_BDD` marks a BDD PR (code stage on merge,
  rejection loop on unmerged close with comments). `AI_CODE` marks a generated
  code PR. Both gate `pr-reviewer-assignment.yml` and the self-test trigger's
  loop guard.

---

## 9. Adding a caller workflow to a product repo

### 9.1 Prerequisites

1. **Product-experts package access** (one-time, done once for the package):
   grant `eroad/test-automation` read access to `@eroad/product-experts` at
   `https://github.com/orgs/eroad/packages/npm/%40eroad%2Fproduct-experts`
   (Package Settings → "Manage Actions access"). The receiving workflow must
   also request `packages: read`.
2. **Jira secrets** — add `JIRA_CLOUD_ID`, `JIRA_EMAIL`, `JIRA_API_TOKEN` to
   the product repo's secrets (Settings → Secrets and variables → Actions).
   Optional but strongly recommended — without them the "Fetch Jira details"
   step soft-fails and the pipeline works from the PR description only.
3. **GitHub App for cross-repo dispatch** — since `generate-bdd` is
   `workflow_dispatch`-only, your product repo's caller workflow must
   authenticate as a GitHub App to call `gh workflow run` /
   `POST .../actions/workflows/qa-isystem-bdd.yml/dispatches` against
   `eroad/test-automation` (the default `GITHUB_TOKEN` cannot dispatch a
   workflow in another repo). One-time setup:
   - Create (or reuse) a GitHub App, install it on `eroad/test-automation`
     only, and grant it repository permission **Actions: Read and write**
     (least privilege — it never needs access to your product repo).
   - In your product repo, add the App's client ID as the `APP_CLIENT_ID`
     repo/organization **variable** and its private key as the
     `APP_PRIVATE_KEY` **secret** (Settings → Secrets and variables →
     Actions).
   - Your caller workflow generates a short-lived installation token with
     [`actions/create-github-app-token@v3`](https://github.com/actions/create-github-app-token)
     and uses it as `GH_TOKEN` for the dispatch call — see
     `qa-isystem-trigger.yml`'s `call-bdd` job for a worked example.
4. **Identify your `product_name`** — find your product's `name` in
   `.agents/knowledge/squads.json`'s `squads[*].products[]` in this repo
   (e.g. `"eRUC B2C"`, `"Drive"`, `"Reefer Management"`).

### 9.2 Caller workflow template

Create `.github/workflows/qa-bdd-trigger.yml` in your product repo:

```yaml
name: QA - BDD generation trigger

on:
  pull_request:
    types: [opened, synchronize]
    # Optional: restrict to specific paths to reduce noise
    # paths:
    #   - 'src/**'
    #   - 'app/**'

concurrency:
  group: qa-bdd-${{ github.event.pull_request.number }}
  cancel-in-progress: true

permissions:
  contents: read
  pull-requests: read

jobs:
  qa-isystem-trigger:
    runs-on: ubuntu-latest
    timeout-minutes: 15
    outputs:
      pr_title: ${{ steps.facts.outputs.pr_title }}
      pr_body: ${{ steps.facts.outputs.pr_body }}
      jira_id: ${{ steps.facts.outputs.jira_id }}
      logic_change: ${{ steps.summarize.outputs.logic_change }}
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Gather PR facts
        id: facts
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          PR_NUMBER: ${{ github.event.pull_request.number }}
        run: |
          set -euo pipefail
          gh pr view "$PR_NUMBER" --json title --jq '.title' > pr-title.txt
          gh pr view "$PR_NUMBER" --json body  --jq '.body'  > pr-body.txt
          gh pr diff "$PR_NUMBER" > pr.diff || true

          {
            echo "pr_title<<EOF_TITLE"
            cat pr-title.txt
            echo "EOF_TITLE"
          } >> "$GITHUB_OUTPUT"

          {
            echo "pr_body<<EOF_BODY"
            cat pr-body.txt
            echo "EOF_BODY"
          } >> "$GITHUB_OUTPUT"

          # jira_id is a mandatory input on qa-isystem-bdd.yml - resolve it
          # here from the PR title before calling. Assumes PR titles follow
          # `PROJ-123 <description>`; if yours don't, source the ID some
          # other way (e.g. a linked-issue lookup) before this point.
          JIRA_ID="$(grep -oE '[A-Z][A-Z0-9]+-[0-9]+' pr-title.txt | head -1 || true)"
          if [ -z "$JIRA_ID" ]; then
            echo "::error::Could not infer a Jira ID from the PR title. jira_id is mandatory - pass it explicitly."
            exit 1
          fi
          echo "jira_id=$JIRA_ID" >> "$GITHUB_OUTPUT"

      - name: Summarize changes via Copilot CLI (plain-English logic_change)
        id: summarize
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          npm install -g @github/copilot

          # gpt-5-mini is the cheapest model -- sufficient for a plain-text summary.
          copilot --model gpt-5-mini --allow-all-tools --no-ask-user \
            -p "Summarize the following PR diff in plain English prose
          (no markdown, no file list, no code blocks) in 5 sentences or fewer --
          what changed functionally and why it likely matters:

          $(head -c 40000 pr.diff)" > logic-change.txt

          {
            echo "logic_change<<EOF_LOGIC"
            cat logic-change.txt
            echo "EOF_LOGIC"
          } >> "$GITHUB_OUTPUT"

  call-bdd:
    needs: [trigger-bdd]
    uses: eroad/test-automation/.github/workflows/qa-isystem-bdd.yml@main
    secrets: inherit
    with:
      # Replace with your product's name from squads.json's squads[*].products[]
      product_name: "eRUC B2C"
      source_repo: ${{ github.repository }}
      pr_number: ${{ github.event.pull_request.number }}
      pr_title: ${{ needs.trigger-bdd.outputs.pr_title }}
      pr_body: ${{ needs.trigger-bdd.outputs.pr_body }}
      jira_id: ${{ needs.trigger-bdd.outputs.jira_id }}
      logic_change: ${{ needs.trigger-bdd.outputs.logic_change }}
```

### 9.3 What to customise

| Field | What to change |
|---|---|
| `product_name` | Your product's `name` from `squads.json`'s `squads[*].products[]` (e.g. `"Drive"`, `"eRUC B2C"`, `"Reefer Management"`) |
| `on.pull_request.types` | Add `reopened` if you want re-opened PRs to retrigger BDD |
| `on.pull_request.paths` | Restrict to specific paths to avoid triggering on docs/config-only PRs |
| `head -c 40000 pr.diff` | Increase/decrease the diff budget sent to the summarizer |
| `@main` in `uses:` | Pin to a specific ref/tag for stability (`@v1`, `@<sha>`) |

### 9.4 How the Jira ID flows

`jira_id` is a **mandatory** input on `qa-isystem-bdd.yml` — it is no longer
inferred inside the reusable workflow. Your caller must resolve it itself
before calling, typically by grepping the PR title for a `PROJ-123` pattern
(see the "Gather PR facts" step in §9.2 above) and passing it as `jira_id`.
Once supplied, `qa-isystem-bdd.yml`'s "Fetch Jira details" step calls
`.github/scripts/qa-isystem/fetch_jira_summary.sh` with that ID, which fetches the Jira
summary, description, and comments (not status/labels) via the Atlassian
API gateway, and embeds it in the context before `Workflow_TestPlanner` runs.

If your PR titles don't follow `PROJ-123 <description>`, resolve the ID from
wherever it actually lives (a linked issue, a commit trailer, a manual input)
before calling — `.github/scripts/qa-isystem/gather_context.py --emit-jira-id --jira-id ...
--pr-title ... --pr-body ...` is available as a helper if you want the same
inference heuristic this repo's own callers use.

### 9.5 Verifying the setup

1. Open a test PR in your product repo.
2. Check the Actions tab — `qa-bdd-trigger.yml` should appear and run.
3. Once complete, check `eroad/test-automation` → Actions → "QA-ISystem ·
   BDD generation" — a `generate-bdd` run should appear within seconds.
4. **Fails on "Sync product experts" with a 401/403**: in CI, confirm the
   package access grant (§9.1 and §6 above); locally, refresh GitHub CLI
   authentication with `read:packages` and complete EROAD SSO authorization,
   or set `NODE_AUTH_TOKEN` to an authorized token. For other npm failures,
   diagnose the captured npm output.
5. **Fails / no Jira content**: confirm `JIRA_*` secrets are set on the
   product repo (§9.1).

---

## 10. Manually triggering / operating the pipeline

- **Manual BDD generation**: now supported as a one-off via `workflow_dispatch`.
  `generate-bdd` in `qa-isystem-bdd.yml` can be triggered directly from the
  Actions tab ("Run workflow") or `gh workflow run qa-isystem-bdd.yml --ref main
  -f ...`, supplying `product_name`, `source_repo`, `pr_number`, `pr_title`,
  `logic_change`, `jira_id` (mandatory), and optionally `domain`/`pr_body` —
  the same fields a real caller workflow passes. See the self-test path below
  for a scripted example (`qa-isystem-trigger.yml`).
- **Self-test inside this repo**: `qa-isystem-trigger.yml` is a sample/reference
  workflow only — it is NOT wired to real PR events and does not run
  automatically. Manually dispatch it from the Actions tab ("Run workflow")
  or `gh workflow run qa-isystem-trigger.yml --ref main -f pr_number=<PR>`,
  supplying an existing `test-automation` PR number to extract facts from.
- **Re-trigger rejection loop**: close a BDD PR without merging, leave at
  least one review comment explaining the rejection, then the
  `check-rejection-feedback` job detects it and allows `rebdd-on-rejection`
  to run automatically.

---

## 11. What was cleaned up (superseded by this design)

- `.github/prompts/bdd-generate.prompt.yml` and `code-generate.prompt.yml` —
  hand-inlined system prompts for `actions/ai-inference@v2`. Superseded by
  the CLI's native `--agent <name>` file loading.
- `.github/scripts/qa-isystem/merge_agent_prompt.py` — merged agent `.agent.md` instructions
  into a REST-API prompt payload. Superseded by `--agent <name>`.
- `--ai-inference-mode` flag and its dead branches in `.github/scripts/qa-isystem/extract_context.py`.
- `--task {bdd,code}` split, `build_bdd_context()`/`build_blob()`, diff
  parsing (`FileChange`, `parse_unified_diff`, `render_functional_changes`),
  and the old in-script `fetch_jira()` REST API call in the previous
  `extract_context.py` — superseded by the single unified `build_context()`
  pipeline in `gather_context.py`.
- **Atlassian-MCP-delegated Jira fetching** (`copilot mcp add mcp-atlassian`
  + `Workflow_*` agents delegating to the `Atlassian` agent via the Task
  tool) — removed after it was found to be silently blocked at runtime by
  org Copilot MCP policy (`! 1 MCP server was blocked by policy:
  'mcp-atlassian'`), causing every run to degrade to no-Jira-context.
  Superseded by the direct `.github/scripts/qa-isystem/fetch_jira_summary.sh` REST API call
  described in §4, run by the workflow itself before the agent starts.