# Graph Report - QA-ISystem  (2026-06-18)

## Corpus Check
- 151 files · ~112,706 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 13 nodes · 27 edges · 2 communities
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `e42ab9fd`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_Community 0|Community 0]]
- [[_COMMUNITY_Community 1|Community 1]]

## God Nodes (most connected - your core abstractions)
1. `setup.sh script` - 11 edges
2. `brew_install()` - 6 edges
3. `info()` - 3 edges
4. `warn()` - 3 edges
5. `error()` - 3 edges
6. `die()` - 3 edges
7. `add_warning()` - 3 edges
8. `add_error()` - 3 edges
9. `success()` - 2 edges
10. `header()` - 2 edges

## Surprising Connections (you probably didn't know these)
- `setup.sh script` --calls--> `die()`  [EXTRACTED]
  scripts/setup.sh → scripts/setup.sh  _Bridges community 0 → community 1_

## Import Cycles
- None detected.

## Communities (2 total, 0 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.48
Nodes (7): add_error(), add_warning(), brew_install(), header(), info(), warn(), setup.sh script

### Community 1 - "Community 1"
Cohesion: 0.40
Nodes (4): die(), error(), note(), success()

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `setup.sh script` connect `Community 0` to `Community 1`?**
  _High betweenness centrality (0.288) - this node is a cross-community bridge._
- **Why does `brew_install()` connect `Community 0` to `Community 1`?**
  _High betweenness centrality (0.030) - this node is a cross-community bridge._