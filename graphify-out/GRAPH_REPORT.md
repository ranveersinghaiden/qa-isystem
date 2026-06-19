# Graph Report - QA-ISystem  (2026-06-19)

## Corpus Check
- 154 files · ~114,266 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 140 nodes · 357 edges · 12 communities (9 shown, 3 thin omitted)
- Extraction: 98% EXTRACTED · 2% INFERRED · 0% AMBIGUOUS · INFERRED: 8 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `f053bac0`
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

## God Nodes (most connected - your core abstractions)
1. `RepoContextService` - 27 edges
2. `String` - 18 edges
3. `StrategyAgentTest` - 14 edges
4. `BddGenerator` - 13 edges
5. `Stream` - 12 edges
6. `Test` - 11 edges
7. `DisplayName` - 11 edges
8. `setup.sh script` - 11 edges
9. `ImpactEnvelope` - 10 edges
10. `start-local.sh script` - 9 edges

## Surprising Connections (you probably didn't know these)
- `CapturingBddGenerator` --inherits--> `BddGenerator`  [EXTRACTED]
  strategy-service/src/test/java/nz/co/eroad/qaisystem/agent/StrategyAgentTest.java → strategy-service/src/main/java/nz/co/eroad/qaisystem/agent/BddGenerator.java

## Import Cycles
- None detected.

## Communities (12 total, 3 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.35
Nodes (11): add_error(), add_warning(), brew_install(), die(), error(), header(), info(), note() (+3 more)

### Community 1 - "Community 1"
Cohesion: 0.20
Nodes (11): FixedCoverageAnalyzer, StrategyAgentTest, BeforeEach, ChangeType, CoverageReport, DisplayName, E2ECoverageAnalyzer, ImpactedComponent (+3 more)

### Community 2 - "Community 2"
Cohesion: 0.28
Nodes (10): BddGenerator, CacheKey, Scenario, ScenarioBuilder, BddScenario, ImpactEnvelope, List, RepoContext (+2 more)

### Community 3 - "Community 3"
Cohesion: 0.16
Nodes (10): CapturingBddGenerator, SilentTestPrService, Override, BddScenario, ImpactEnvelope, String, TestStrategy, TestPrService (+2 more)

### Community 5 - "Community 5"
Cohesion: 0.60
Nodes (3): List, String, Stream

### Community 6 - "Community 6"
Cohesion: 0.44
Nodes (9): die(), error(), header(), info(), kill_port(), port_busy(), success(), warn() (+1 more)

### Community 7 - "Community 7"
Cohesion: 0.32
Nodes (4): CopilotAgentClient, AiProviderProperties, Path, String

### Community 9 - "Community 9"
Cohesion: 0.33
Nodes (3): RepoContext, PostConstruct, ProductExpertContext

### Community 10 - "Community 10"
Cohesion: 0.33
Nodes (5): AiProviderProperties, CopilotAgentConfig, CopilotCliConfig, CopilotConfig, OpenAiConfig

## Knowledge Gaps
- **13 isolated node(s):** `AiProviderProperties`, `CopilotCliConfig`, `CopilotAgentConfig`, `OpenAiConfig`, `CopilotConfig` (+8 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **3 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `BddGenerator` connect `Community 2` to `Community 3`?**
  _High betweenness centrality (0.187) - this node is a cross-community bridge._
- **Why does `CapturingBddGenerator` connect `Community 3` to `Community 1`, `Community 2`?**
  _High betweenness centrality (0.171) - this node is a cross-community bridge._
- **Why does `RepoContextService` connect `Community 4` to `Community 8`, `Community 9`, `Community 11`, `Community 5`?**
  _High betweenness centrality (0.127) - this node is a cross-community bridge._
- **What connects `AiProviderProperties`, `CopilotCliConfig`, `CopilotAgentConfig` to the rest of the system?**
  _13 weakly-connected nodes found - possible documentation gaps or missing edges._