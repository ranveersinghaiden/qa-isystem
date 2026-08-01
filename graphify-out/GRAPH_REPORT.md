# Graph Report - QA-ISystem  (2026-08-01)

## Corpus Check
- 24 files · ~24,802 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 322 nodes · 1264 edges · 16 communities (12 shown, 4 thin omitted)
- Extraction: 41% EXTRACTED · 59% INFERRED · 0% AMBIGUOUS · INFERRED: 740 edges (avg confidence: 0.53)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `12b08b07`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_Community 0|Community 0]]
- [[_COMMUNITY_Community 1|Community 1]]
- [[_COMMUNITY_Community 2|Community 2]]
- [[_COMMUNITY_Community 3|Community 3]]
- [[_COMMUNITY_Community 4|Community 4]]
- [[_COMMUNITY_Community 5|Community 5]]
- [[_COMMUNITY_Community 6|Community 6]]
- [[_COMMUNITY_Community 7|Community 7]]
- [[_COMMUNITY_Community 8|Community 8]]
- [[_COMMUNITY_Community 9|Community 9]]
- [[_COMMUNITY_Community 10|Community 10]]
- [[_COMMUNITY_Community 11|Community 11]]
- [[_COMMUNITY_Community 12|Community 12]]
- [[_COMMUNITY_Community 13|Community 13]]
- [[_COMMUNITY_Community 14|Community 14]]
- [[_COMMUNITY_Community 15|Community 15]]

## God Nodes (most connected - your core abstractions)
1. `LocalContextBundle` - 73 edges
2. `GeneratedArtifact` - 68 edges
3. `ValidationResult` - 59 edges
4. `AutomationTask` - 56 edges
5. `LocalContextBuilder` - 51 edges
6. `GenerationRouter` - 50 edges
7. `AutomationTask` - 43 edges
8. `RepairType` - 43 edges
9. `DeterministicBddGenerator` - 36 edges
10. `TestDomain` - 32 edges

## Surprising Connections (you probably didn't know these)
- `Any` --uses--> `LocalContextBuilder`  [INFERRED]
  .github/scripts/qa-isystem/local_generation.py → .github/scripts/qa-isystem/qa_engine_context.py
- `Any` --uses--> `ContextBudget`  [INFERRED]
  .github/scripts/qa-isystem/local_generation.py → .github/scripts/qa-isystem/qa_engine_models.py
- `Any` --uses--> `GeneratedArtifact`  [INFERRED]
  .github/scripts/qa-isystem/local_generation.py → .github/scripts/qa-isystem/qa_engine_models.py
- `Any` --uses--> `LocalContextBundle`  [INFERRED]
  .github/scripts/qa-isystem/local_generation.py → .github/scripts/qa-isystem/qa_engine_models.py
- `Any` --uses--> `RepairType`  [INFERRED]
  .github/scripts/qa-isystem/local_generation.py → .github/scripts/qa-isystem/qa_engine_models.py

## Import Cycles
- None detected.

## Communities (16 total, 4 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.12
Nodes (18): AutomationTask, GeneratedArtifact, LocalContextBundle, ValidationResult, TestDomain, ApiPack, API pack: safe client skeleton and deterministic contract checks., BddPack (+10 more)

### Community 1 - "Community 1"
Cohesion: 0.21
Nodes (37): GenerationMode, GenerationPlan, Any, AutomationTask, GeneratedArtifact, LocalContextBundle, ValidationResult, Any (+29 more)

### Community 2 - "Community 2"
Cohesion: 0.14
Nodes (38): GenerationPack, AutomationTask, AutomationTask, GeneratedArtifact, LocalContextBundle, ValidationResult, AutomationTask, GeneratedArtifact (+30 more)

### Community 3 - "Community 3"
Cohesion: 0.17
Nodes (26): AutomationTask, Path, TaskType, GenerationRouter, Local-first orchestration and explicit LLM escalation policy., Attempts local generation and validation before any model invocation., LocalValidators, _Provider (+18 more)

### Community 4 - "Community 4"
Cohesion: 0.06
Nodes (34): 10. Manually triggering / operating the pipeline, 11. What was cleaned up (superseded by this design), 1. Why Copilot CLI + `GITHUB_TOKEN` (not REST API, not a PAT), 2. High-level architecture, 2a. Architecture diagram — the 3 `qa-isystem-*` workflows, 3.1 `qa-isystem-bdd.yml` — Stage A (owns *all* BDD generation), 3.2 `qa-isystem-code.yml` — Stage B, 3.3 `qa-isystem-trigger.yml` — self-test harness / reference caller (+26 more)

### Community 5 - "Community 5"
Cohesion: 0.16
Nodes (33): AutomationTask, LocalContextBundle, Path, TaskType, main(), _task_from_json(), default_pack_registry(), Build built-in packs lazily so packs only depend on shared contracts. (+25 more)

### Community 6 - "Community 6"
Cohesion: 0.21
Nodes (10): ContextBudget, AutomationTask, LocalContextBundle, Path, LocalContextBuilder, Bounded local repository evidence collection for QA generation., Ranks local evidence with transparent token overlap and path affinity., read_text() (+2 more)

### Community 7 - "Community 7"
Cohesion: 0.25
Nodes (4): AutomationTask, GeneratedArtifact, LocalContextBundle, Path

### Community 8 - "Community 8"
Cohesion: 0.32
Nodes (7): Any, to_jsonable(), _acceptance_criteria(), main(), Keep locally supplied requirement lines bounded., _read(), test_task_builder_bounds_local_requirements()

### Community 9 - "Community 9"
Cohesion: 0.43
Nodes (6): build_context(), infer_product_from_paths(), _load_catalog(), main(), read_maybe_file(), resolve_product_target()

### Community 10 - "Community 10"
Cohesion: 0.40
Nodes (3): Path, test_cli_writes_local_context(), test_resolve_product_target_reads_only_local_catalog()

### Community 11 - "Community 11"
Cohesion: 0.33
Nodes (5): Layout, QA-isystem tooling, Requirements, Run tests, Workflows

## Knowledge Gaps
- **36 isolated node(s):** `Local QA context tooling`, `Product knowledge guidance`, `Any`, `sync_product_experts.sh script`, `1. Why Copilot CLI + `GITHUB_TOKEN` (not REST API, not a PAT)` (+31 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **4 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `GenerationRouter` connect `Community 3` to `Community 0`, `Community 1`, `Community 2`, `Community 5`, `Community 6`, `Community 8`?**
  _High betweenness centrality (0.128) - this node is a cross-community bridge._
- **Why does `LocalContextBuilder` connect `Community 6` to `Community 1`, `Community 2`, `Community 3`?**
  _High betweenness centrality (0.094) - this node is a cross-community bridge._
- **Why does `LocalContextBundle` connect `Community 2` to `Community 0`, `Community 1`, `Community 3`, `Community 6`, `Community 7`?**
  _High betweenness centrality (0.085) - this node is a cross-community bridge._
- **Are the 72 inferred relationships involving `LocalContextBundle` (e.g. with `ContextBudget` and `GenerationMode`) actually correct?**
  _`LocalContextBundle` has 72 INFERRED edges - model-reasoned connections that need verification._
- **Are the 67 inferred relationships involving `GeneratedArtifact` (e.g. with `GenerationMode` and `GenerationPack`) actually correct?**
  _`GeneratedArtifact` has 67 INFERRED edges - model-reasoned connections that need verification._
- **Are the 58 inferred relationships involving `ValidationResult` (e.g. with `GenerationMode` and `GenerationPack`) actually correct?**
  _`ValidationResult` has 58 INFERRED edges - model-reasoned connections that need verification._
- **Are the 55 inferred relationships involving `AutomationTask` (e.g. with `ContextBudget` and `GenerationMode`) actually correct?**
  _`AutomationTask` has 55 INFERRED edges - model-reasoned connections that need verification._