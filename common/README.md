# common

Shared library module — domain models, Spring configuration, Kafka infrastructure, GitHub
tracking, and AI clients. Every service depends on this; it is never deployed independently.

---

## Models (`qaisystem.model`)

| Class | Description |
|-------|-------------|
| `PullRequest` | Incoming PR event. Key fields: `prId`, `title`, `author`, `sourceBranch`, `repositoryName`, `rawDiffContent`, `diffs`, `jiraIds`, `products`, `status`. |
| `GitDiff` | Single file diff: `filePath`, `diffType` (ADDED/MODIFIED/DELETED/RENAMED), `hunks`, `linesAdded`, `linesDeleted`, `fileExtension`, `isTestFile`. |
| `ImpactEnvelope` | Full impact result from impact-service: `riskLevel`, `overallRiskScore`, `prTitle`, `detectedChangeTypes`, `impactedComponents`, `serviceConfidence`, `coverageReport`, dependency graph, strategy hints, optional `aiInsight`. |
| `CoverageReport` | Test coverage snapshot: `level` (GOOD/PARTIAL/NONE/UNKNOWN), `coverageRatio`, `testedComponents`, `untestedComponents`, `requiredTestTypes`, `existingTestFiles`, `requiresNewTests`. |
| `TestStrategy` | StrategyAgent decision: `decision` (CREATE/UPDATE/SKIP), `confidenceScore`, `fullRegressionRequired`, `expandedScope`, `expandedAreas`, `newTestRequirements`. |
| `BddScenario` | Gherkin feature file: `featureTitle`, `prId`, `prTitle`, `scenarios` (each with given/when/then steps, tags, examples). `prTitle` flows from `PullRequest.title` through the pipeline for GitHub PR naming. |
| `TestScript` | Generated test code: `scriptContent`, `testType` (API/UI/MOBILE), `status`, `prId`, `prTitle`, `retryCount`. |
| `TestResult` | Execution outcome: `passed`, `attemptNumber`, `executionTimeMs`, `errorMessage`, `stabilized`, `finalScriptContent`. |
| `PrRecord` | Immutable record of a tracked QA PR. Fields: `branchName`, `prNumber`, `type` (`PrType`), `bddScenario` (non-null for BDD), `testScript` (non-null for TEST). Uses `@JsonDeserialize(builder=PrRecord.PrRecordBuilder.class)` + `@JsonPOJOBuilder(withPrefix="")` so Jackson can deserialize via the Lombok builder without a no-args constructor. |
| `PrType` | Enum: `BDD` \| `TEST`. Used by `PrTracker` and `FeedbackEvent`. |
| `FeedbackEvent` | Kafka payload for `FeedbackQueue`: wraps either a `BddScenario` (BDD rejection) or `TestScript` (TEST rejection) with `prType`, `prNumber`, and reviewer comment text. |

---

## Configuration (`qaisystem.config`)

| Class | Description |
|-------|-------------|
| `AppConfig` | `ObjectMapper` bean (ISO-8601 dates, `JavaTimeModule`), async task executor (`qa-async-*` virtual threads). |
| `KafkaConfig` | Producer factory, consumer factory, `KafkaTemplate`, `KafkaAdmin`, all topic declarations. Configured from `application.yaml`. Topics are declared idempotently — any service can start first. |
| `AiClientConfig` | Creates the active `AiClient` bean based on `aiqa.ai.provider`. Options: `copilot-cli` (default), `copilot`, `openai`. Falls back to template mode when no credential is configured. |

---

## GitHub / PR Tracking (`qaisystem.github`)

| Class | Description |
|-------|-------------|
| `PrTracker` | Interface for tracking open QA PRs. Methods: `trackBdd()`, `trackTest()`, `findByBranch()`, `findAll()`, `remove()`, `size()`. `findAll()` returns all tracked records (BDD + TEST) — used by `GET /api/strategy/pending-bdd`. |
| `InMemoryPrTracker` | Default implementation backed by `ConcurrentHashMap`. Active when Redis is **not** configured. State is lost on JVM restart. |
| `RedisPrTracker` | Redis-backed implementation. Active when `spring.data.redis.host` is set (`@ConditionalOnProperty`). State survives pod restarts. Key prefix: `qa:pr:`. Deserializes `PrRecord` via its Jackson builder. |
| `GitHubService` | GitHub REST API v3 client: create branch, create/update file, create PR, fetch PR comments. Resolves token from `TARGET_REPO_TOKEN` env var → `git credential fill` → startup failure if URL is set but no token found. |

---

## AI Clients (`qaisystem.agent`)

| Class | Description |
|-------|-------------|
| `AiClient` | Interface: `complete(systemPrompt, userPrompt)`, `isAvailable()`, and default `completeWithHistory(systemPrompt, List<ChatMessage> history, newUserMessage)` for multi-turn conversations. |
| `CopilotCliClient` | Calls `gh api https://api.githubcopilot.com/chat/completions` via `ProcessBuilder`. Uses `gh auth` credentials — no token env var required. Overrides `completeWithHistory()` to send a full multi-turn messages array. Default provider. |
| `CopilotClient` | Calls GitHub Copilot REST API. Requires `GITHUB_COPILOT_TOKEN`. |
| `OpenAiClient` | Calls any OpenAI-compatible endpoint. Supports OpenAI, Azure OpenAI, Ollama, GitHub Models. Requires `OPENAI_API_KEY` (or a custom `OPENAI_BASE_URL` for local models). |

---

## Conversation History (`qaisystem.conversation`)

| Class | Description |
|-------|-------------|
| `ChatMessage` | Java 25 record `(String role, String content)`; static factory methods `system()`, `user()`, `assistant()`. |
| `ConversationHistory` | Java 25 record `(String prId, List<ChatMessage> turns, int totalTurns, Instant lastUpdated)`. |
| `ConversationStore` | Interface: `save(conversationId, history)`, `load(conversationId)`, `remove(conversationId)`. |
| `RedisConversationStore` | `@ConditionalOnProperty(spring.data.redis.host)`. GZIP+Base64 compressed. Key: `qa:chat:{conversationId}`. Configurable TTL. 1 MB decompression OOM guard. Size management: compress first, then drop oldest turns. |
| `InMemoryConversationStore` | `@ConditionalOnMissingBean` fallback. Non-persistent; state is lost on JVM restart. |

---

## Shared Services (`qaisystem.service`)

| Class | Description |
|-------|-------------|
| `RepoContextService` | Clones the target test repo (`git clone --depth 1` or `git pull`), scans test files for package/import/class conventions, builds coverage index (`componentName → test files`), loads `productExpert/*.md` and `.aiqa/context.md`. Refreshable via `POST /api/strategy/refresh-context`. |

---

## Key Design Decisions

- **No `@SpringBootApplication`** — plain library jar, not deployable.
- Topics are declared **idempotently** — all services declare all topics so any can start first.
- Snake_case `@JsonProperty` on `PullRequest` fields (`pr_id`, `jira_ids`, `changed_files`) maps correctly to standard Git webhook payloads.
- `PrRecord` uses `@JsonDeserialize(builder=...)` + `@JsonPOJOBuilder(withPrefix="")` because its all-final `@Builder` fields have no no-args constructor — required for `RedisPrTracker` deserialization.
- `RedisPrTracker` activates automatically when the Redis container is reachable — no manual switch needed beyond having `spring.data.redis.host` in config.
