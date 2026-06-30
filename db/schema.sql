-- QA-ISystem — Neon Postgres schema (scale-to-zero migration, Decision C1).
--
-- Replaces Redis as the cross-run source of truth. Every table carries a nullable
-- `tenant_id` (Decision C5 — the SaaS seam, single-org today). `jsonb` columns hold
-- variable-shape blobs (webhook payloads, chat turns, context-trace events, results).
--
-- Apply once per environment:
--   psql "$QA_DB_URL" -f db/schema.sql
-- Safe to re-run: every statement is IF NOT EXISTS.

-- ---------------------------------------------------------------------------
-- pr_history — one row per source PR; the gate state-machine lives here.
-- ---------------------------------------------------------------------------
create table if not exists pr_history (
  pr_id            text primary key,
  tenant_id        text,                            -- nullable SaaS seam (C5)
  repo             text not null,
  owner            text not null,
  pr_number        int,
  head_sha         text not null,
  branch           text,
  payload          jsonb,                           -- original webhook / PullRequest payload
  impact_envelope  jsonb,                           -- ImpactEnvelope produced by the impact pod (handed to strategy pod)
  strategy         jsonb,                            -- TestStrategy decision produced by the strategy pod
  risk             text,
  gate_state       text not null default 'new',
    -- new | awaiting_bdd_approval | bdd_approved | awaiting_tests_approval | tests_approved | rejected | done
  bdd_pr_number    int,
  tests_pr_number  int,
  created_at       timestamptz default now(),
  updated_at       timestamptz default now()
);

-- ---------------------------------------------------------------------------
-- chat_history — LLM conversation turns per PR (replaces Redis ConversationStore).
-- ---------------------------------------------------------------------------
create table if not exists chat_history (
  id         bigserial primary key,
  pr_id      text references pr_history(pr_id),
  tenant_id  text,
  role       text,                                  -- user | assistant | system
  content    jsonb,
  created_at timestamptz default now()
);

-- ---------------------------------------------------------------------------
-- context_history — context-trace events (replaces the Redis/file trace sink).
-- ---------------------------------------------------------------------------
create table if not exists context_history (
  id          bigserial primary key,
  pr_id       text,
  tenant_id   text,
  boundary    text,                                 -- CONTEXT_SELECTION | COMPRESSION | LLM_REQUEST | LLM_RESPONSE | ...
  payload     jsonb,
  char_count  int,
  token_count int,
  created_at  timestamptz default now()
);

-- ---------------------------------------------------------------------------
-- scenario_state — per-scenario codegen tracking (replaces the Kafka fan-out msg).
-- Completion is detected by the Actions matrix (all rows done), not a Redis countdown.
-- ---------------------------------------------------------------------------
create table if not exists scenario_state (
  pr_id        text,
  scenario_id  text,
  tenant_id    text,
  status       text,                                -- planned | generating | stabilizing | passed | failed
  test_path    text,
  attempts     int default 0,
  result       jsonb,
  scenario     jsonb,                               -- the BDD scenario payload codegen consumes
  updated_at   timestamptz default now(),
  primary key (pr_id, scenario_id)
);

-- ---------------------------------------------------------------------------
-- prompt_cache — cross-PR LLM response reuse (replaces Redis PromptResponseCache).
-- ---------------------------------------------------------------------------
create table if not exists prompt_cache (
  prompt_hash text primary key,
  tenant_id   text,
  response    jsonb,
  created_at  timestamptz default now()
);

-- ---------------------------------------------------------------------------
-- coverage_learning — the >90% authorship lever (coverage matrix learning).
-- ---------------------------------------------------------------------------
create table if not exists coverage_learning (
  id              bigserial primary key,
  tenant_id       text,
  product         text,
  capability      text,
  scenario_class  text,                             -- HAPPY | BOUNDARY | NEGATIVE | AUTH | ...
  covered         boolean,
  pr_id           text,
  created_at      timestamptz default now()
);

-- ---------------------------------------------------------------------------
-- dedup_state — cross-workflow content-hash dedup (replaces Redis claim/done).
-- ---------------------------------------------------------------------------
create table if not exists dedup_state (
  content_hash text primary key,
  pr_id        text,
  status       text,                                -- in_flight | done
  created_at   timestamptz default now()
);

-- ---------------------------------------------------------------------------
-- Indexes for the hot lookup paths.
-- ---------------------------------------------------------------------------
create index if not exists idx_pr_history_gate       on pr_history (gate_state);
create index if not exists idx_pr_history_branch      on pr_history (branch);
create index if not exists idx_scenario_state_pr      on scenario_state (pr_id);
create index if not exists idx_scenario_state_status  on scenario_state (pr_id, status);
create index if not exists idx_context_history_pr     on context_history (pr_id, created_at);
create index if not exists idx_chat_history_pr        on chat_history (pr_id, created_at);
create index if not exists idx_coverage_learning_prod on coverage_learning (product, capability);
