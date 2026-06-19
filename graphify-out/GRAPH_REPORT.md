# Graph Report - QA-ISystem  (2026-06-19)

## Corpus Check
- 158 files · ~127,622 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 295 nodes · 513 edges · 14 communities
- Extraction: 98% EXTRACTED · 2% INFERRED · 0% AMBIGUOUS · INFERRED: 8 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `f99e7639`
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

## God Nodes (most connected - your core abstractions)
1. `RepoContextService` - 27 edges
2. `QA-ISystem: Complete Architecture & Design Reference` - 18 edges
3. `String` - 18 edges
4. `QA-ISystem — Autonomous AI-Driven QA Pipeline` - 16 edges
5. `StrategyAgentTest` - 14 edges
6. `BddGenerator` - 13 edges
7. `Stream` - 12 edges
8. `Manual Testing & Process Monitoring` - 11 edges
9. `Test` - 11 edges
10. `DisplayName` - 11 edges

## Surprising Connections (you probably didn't know these)
- `CapturingBddGenerator` --inherits--> `BddGenerator`  [EXTRACTED]
  strategy-service/src/test/java/nz/co/eroad/qaisystem/agent/StrategyAgentTest.java → strategy-service/src/main/java/nz/co/eroad/qaisystem/agent/BddGenerator.java

## Import Cycles
- None detected.

## Communities (14 total, 0 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.35
Nodes (11): add_error(), add_warning(), brew_install(), die(), error(), header(), info(), note() (+3 more)

### Community 1 - "Community 1"
Cohesion: 0.11
Nodes (21): CapturingBddGenerator, FixedCoverageAnalyzer, SilentTestPrService, StrategyAgentTest, BeforeEach, ChangeType, CoverageReport, DisplayName (+13 more)

### Community 2 - "Community 2"
Cohesion: 0.28
Nodes (10): BddGenerator, CacheKey, Scenario, ScenarioBuilder, BddScenario, ImpactEnvelope, List, RepoContext (+2 more)

### Community 3 - "Community 3"
Cohesion: 0.05
Nodes (42): 1 — Trigger Test PRs Manually, 2 — Monitor Service Health, 3 — Monitor Service Logs in Real-Time, 4 — Inspect Kafka Topics, 5 — Inspect Redis State, 6 — Check GitHub API for Created PRs, 7 — Monitor Docker Container Performance, 8 — Real-World Example: 3-PR Concurrent Test (+34 more)

### Community 4 - "Community 4"
Cohesion: 0.18
Nodes (11): List, Path, RepoContext, String, Map, Object, PostConstruct, ProductExpertContext (+3 more)

### Community 5 - "Community 5"
Cohesion: 0.04
Nodes (46): 10. AI Provider Configuration, 11. The Two-Phase Coverage Assessment — In Depth, 12. Kafka Manual Acknowledgement — Why It Matters, 13. Multi-Module Maven Build, 14. Docker Compose Setup, 15. Testing the System Locally, 16. Summary of Heuristics and Why They Were Chosen, 17. Class Reference Table (+38 more)

### Community 6 - "Community 6"
Cohesion: 0.44
Nodes (9): die(), error(), header(), info(), kill_port(), port_busy(), success(), warn() (+1 more)

### Community 7 - "Community 7"
Cohesion: 0.29
Nodes (6): CopilotAgentClient, AiProviderProperties, ObjectMapper, Path, String, StringBuilder

### Community 8 - "Community 8"
Cohesion: 0.17
Nodes (12): 7.1 AI Cost Gating — Three Layers Before Any API Call, 7.2 RepoContextService — Cloning and Indexing the Test Repository, 7.3 E2ECoverageAnalyzer — Phase 2 Coverage Assessment, 7.4 StrategyAgent — The Decision Maker, 7.5 BddGenerator — Creating Human-Readable Test Scenarios, 7.6 GitHub PR Workflow, 7.7 Endpoints, 7. strategy-service — Phases 2–4: Strategy, BDD Generation, GitHub PR (+4 more)

### Community 9 - "Community 9"
Cohesion: 0.20
Nodes (10): 4. The `common` Module, `BddScenario`, `CoverageReport`, `FeedbackEvent`, `GitDiff`, `ImpactEnvelope`, Key models, `PrContext` (+2 more)

### Community 10 - "Community 10"
Cohesion: 0.33
Nodes (5): AiProviderProperties, CopilotAgentConfig, CopilotCliConfig, CopilotConfig, OpenAiConfig

### Community 11 - "Community 11"
Cohesion: 0.25
Nodes (8): 6. impact-service — Phase 1: Deterministic Analysis, Step 1: GitDiffParser — Reading the raw diff, Step 2: DependencyGraph — Who depends on what?, Step 3: ChangeTypeDetector — What kind of change is this?, Step 4: RiskScorer — How risky is this change?, Step 4b: AIImpactEvaluator — AI Last Resort (optional), Step 5: IntegrationTestScopeClassifier, The 5-step pipeline

### Community 12 - "Community 12"
Cohesion: 0.06
Nodes (31): 1 — Start infrastructure, 2 — Build, 3 — Configure (optional), 4 — Start services (separate terminals), 5 — Trigger the pipeline, 6 — Useful runtime commands, AI Feedback Loop, AI Provider Configuration (+23 more)

## Knowledge Gaps
- **130 isolated node(s):** `Table of Contents`, `Architecture`, `Module Overview`, `Kafka Topics`, `Pipeline Flow` (+125 more)
  These have ≤1 connection - possible missing edges or undocumented components.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `QA-ISystem: Complete Architecture & Design Reference` connect `Community 5` to `Community 8`, `Community 9`, `Community 11`?**
  _High betweenness centrality (0.061) - this node is a cross-community bridge._
- **Why does `Manual Testing & Process Monitoring` connect `Community 3` to `Community 12`?**
  _High betweenness centrality (0.047) - this node is a cross-community bridge._
- **Why does `BddGenerator` connect `Community 2` to `Community 1`?**
  _High betweenness centrality (0.044) - this node is a cross-community bridge._
- **What connects `Table of Contents`, `Architecture`, `Module Overview` to the rest of the system?**
  _130 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.10631229235880399 - nodes in this community are weakly interconnected._
- **Should `Community 3` be split into smaller, more focused modules?**
  _Cohesion score 0.047619047619047616 - nodes in this community are weakly interconnected._
- **Should `Community 5` be split into smaller, more focused modules?**
  _Cohesion score 0.0425531914893617 - nodes in this community are weakly interconnected._