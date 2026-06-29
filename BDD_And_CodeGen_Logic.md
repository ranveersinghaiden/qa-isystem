# BDD and Code Generation Logic

> How `strategy-service` generates BDD scenarios and `codegen-service` turns them into runnable Java tests.

---

## The Full Workflow

```
GitHub Webhook
     │
     ▼
pr-service (8080)
     │  Kafka: PrAnalysisQueue
     ▼
impact-service (8081)   ← deterministic diff analysis
     │  Kafka: TestStrategyQueue
     ▼
strategy-service (8082)  ← BDD generation  ◄─── THIS is where BDD is made
     │  Kafka: TestScriptsQueue
     ▼
codegen-service (8083)   ← Java test code generation + stabilisation
     │  GitHub API
     ▼
Test PR on target repo
```

---

## Part 1 — How `strategy-service` generates BDD

### Step 1 — Getting repo context (`RepoContextService`)

On **startup**, `RepoContextService` runs git as a subprocess:

```java
// RepoContextService.java
ProcessBuilder pb = new ProcessBuilder("git", "clone", repoUrl, localPath);
pb.environment().put("GIT_TERMINAL_PROMPT", "0");
```

It **clones or pulls** the *target test repo* (the repo under test, not QA-ISystem itself) locally onto the machine where the service is running.

Then `refreshCache()` scans that cloned repo for:

| What it finds | How it's used |
|---|---|
| `.github/agents/*.md` files | Loaded as **agent instructions** — injected verbatim into the AI system prompt |
| `productExpert/` directory (`*.md`, `*.txt`, `*.json`) | Loaded as **product expert context** — domain knowledge, data models, business rules |
| Existing test files (packages, base classes, imports) | Used to keep generated code consistent with project conventions |
| Integration/E2E test files | Indexed into a **coverage index** — prevents generating duplicate tests |

> **The "product expert context" is literally Markdown files you commit into the target repo under a `productExpert/` folder.** The agent reads them on every startup and refresh.

---

### Step 2 — Building the AI prompt (`BddGenerator`)

`BddGenerator.buildSystemPrompt()` assembles:

```
[System Prompt]
You are an expert QA engineer...

=== PRODUCT EXPERT CONTEXT ===
<full content of productExpert/*.md files>

=== AGENT INSTRUCTIONS ===
<full content of .github/agents/*.md files matching the test type>

=== SAMPLE TESTS ===
<real test files from the target repo>

=== EXISTING COVERAGE ===
<coverage index — what's already tested>
```

`buildUserPrompt()` adds the PR-specific data:

```
PR #42: "Add new billing endpoint"
Risk level: HIGH
Change types: [API_CHANGE, DATABASE_MIGRATION]
Regression: true
External context: <Jira ticket / Confluence page if present>
```

`StrategyAgent` runs `CoveragePlanner` before `BddGenerator`, producing a `ScenarioMatrix`
(capability × `ScenarioClass`). The user prompt then enumerates the **PLANNED** gap cells — one
scenario per `(capability, class)` — so generation is gap-driven, not open-ended. Classes that keep
getting rejected (`RejectionLedger`) are folded back into the required set. See
`QA-ISystem-Architecture.md` §11 Phase 3.

---

### Step 3 — AI call + caching

Before calling the AI, it checks Redis:

```
key: qa:strategy:prompt-cache:<SHA-256 of changeType|componentType|riskLevel|repoName>
TTL: 24 hours
```

- **Cache hit** → return cached BDD response immediately
- **Cache miss** → call configured `AiClient` → store response in Redis → publish `BddScenario` records to Kafka `TestScriptsQueue`

---

## Part 2 — How `codegen-service` generates test code

### Step 1 — Kafka consumer

```java
@KafkaListener(topics = "${kafka.topics.test-scripts}", concurrency = "3")
```

Three concurrent listeners pick up `BddScenario` messages. Each `BddScenario` carries:
- The Gherkin scenarios
- `prId`, `prTitle`, `prContext` (carries Jira/Confluence labels forward)

---

### Step 2 — Loading repo context (again)

`CodegenService` calls `RepoContextService.getContext(testType)` which returns the same `RepoContext` object:

- Agent instructions (per test type)
- Product expert sections
- Base package, imports, base class
- Sample test files
- Coverage index

This is the **same cloned repo on disk**, cached in memory per test type.

---

### Step 3 — Routing to a runner

Based on `@tags` in the BDD (e.g. `@api`, `@ui`, `@mobile`):

| Tag | Runner | Framework generated |
|---|---|---|
| `@api` | `ApiTestRunner` | RestAssured + JUnit 5 |
| `@ui` | `UITestRunner` | Selenium + JUnit 5 |
| `@mobile` | `MobileTestRunner` | Appium + JUnit 5 |

Each runner builds a prompt like:

```
[System] You are a Java test engineer.
Base package: nz.co.eroad.billing.tests
Base class: extends BaseApiTest
Imports: [from real files in target repo]
Sample existing test: [actual file content]
Agent instructions: [from .github/agents/api-agent.md]
Product expert context: [from productExpert/billing.md]

[User] Generate a RestAssured JUnit 5 test for this BDD scenario:
Feature: Billing endpoint
  Scenario: Create invoice...
```

The AI returns a `.java` file as a string → stored as a `TestScript`.

---

### Step 4 — Stabilisation loop (`StabilizationLoop`)

This is the most critical part. The service actually **compiles and runs the generated test** up to 3 times:

```
attempt 1: compile + run generated test
           (javax.tools.JavaCompiler + JUnit Platform Launcher)
           │
           ├── PASS → proceed to PR creation
           │
           └── FAIL → apply fix: add @Timeout annotations,
                       configure connection timeouts
                       │
attempt 2: compile + run again
           │
           ├── PASS → proceed to PR creation
           │
           └── FAIL → apply fix: wrap calls in retry logic,
                       add null guards
                       │
attempt 3: compile + run again
           │
           ├── PASS → proceed to PR creation
           │
           └── FAIL → generate minimal smoke test
                       (just checks HTTP 2xx-5xx reachability)
                       mark status = SMOKE_ONLY
                       proceed to PR creation
```

> The test runs **in-process** on the machine where `codegen-service` is deployed — no separate JVM, no Docker sandbox, no CI runner.

---

### Step 5 — PR creation (`TestPrService`)

Once stabilised (or smoke-only), `TestPrService` calls `GitHubService` to:

1. Create a branch on the target test repo
2. Commit the `.java` test file
3. Open a PR for human review

---

## Part 3 — Where does `copilot-cli` (`gh api`) fit in?

The `CopilotCliClient` is the **default AI provider** (`aiqa.ai.provider: copilot-cli`). Here's exactly what it does:

```java
// CopilotCliClient.java
ProcessBuilder pb = new ProcessBuilder(
    ghCliPath,           // e.g. "/usr/local/bin/gh"
    "api",
    "/copilot/v1/engines/copilot-codex/completions",
    "--method", "POST",
    "--input", tempFile  // JSON body written to a temp file (chmod 600)
);
pb.environment().put("GH_TOKEN", token);
```

It spawns the **`gh` CLI as a subprocess**, passing the full chat messages JSON through a temp file. `gh api` forwards the request to the GitHub Copilot API using the authenticated session.

- stdout/stderr are drained on **virtual threads** (`Thread.ofVirtual()`) to prevent blocking
- It waits up to `timeoutSeconds` (default 120s)
- At construction time it verifies the CLI is available by running `gh auth status` (must exit 0)

### The three AI providers compared

| Provider | Config value | How it works |
|---|---|---|
| **CopilotCliClient** | `copilot-cli` *(default)* | Spawns `gh api` subprocess — uses your local `gh` session |
| **CopilotClient** | `copilot` | Direct HTTP POST to GitHub Copilot API with a bearer token |
| **OpenAiClient** | `openai` | Direct HTTP POST to any OpenAI-compatible endpoint (Azure OpenAI, Ollama, etc.) |

Set the provider in `application.yaml`:

```yaml
aiqa:
  ai:
    provider: copilot-cli   # or: copilot, openai
```

---

## Part 4 — Where does everything actually run?

Everything runs **in-process on the server/machine where the Spring Boot services are deployed**. There is no separate agent VM, no GitHub Actions runner, no cloud sandbox:

| Operation | Where it runs |
|---|---|
| `git clone` of target repo | Local filesystem of the service host |
| AI calls | HTTPS outbound (or via `gh` subprocess for `copilot-cli`) |
| Java compilation | In-memory via `javax.tools.JavaCompiler` |
| Test execution | In the same JVM via JUnit Platform Launcher |
| PR creation | GitHub REST API outbound call |

### Production (Docker Compose) requirements

The `codegen-service` container needs:

- `gh` CLI installed and authenticated (for `copilot-cli` mode)
- `GIT_TOKEN` env var to clone target repos
- Network access to `api.github.com`

---

## Quick Reference — Key Classes

| Class | Module | Responsibility |
|---|---|---|
| `RepoContextService` | `common` | Clones target repo, loads product expert docs + agent instructions |
| `RepoContext` | `common` | Data object holding all context passed to AI prompts |
| `BddGenerator` | `strategy-service` | Builds prompts, calls AI, parses Gherkin, publishes `BddScenario` |
| `PromptResponseCache` | `strategy-service` | Redis cache for AI responses (24h TTL, SHA-256 key) |
| `TestScriptsConsumer` | `codegen-service` | Kafka consumer — 3 concurrent listeners on `TestScriptsQueue` |
| `CodegenService` | `codegen-service` | Routes `BddScenario` to the correct test runner |
| `ApiTestRunner` | `codegen-service` | Generates RestAssured + JUnit 5 code |
| `UITestRunner` | `codegen-service` | Generates Selenium + JUnit 5 code |
| `MobileTestRunner` | `codegen-service` | Generates Appium + JUnit 5 code |
| `TestExecutionEngine` | `codegen-service` | Compiles + runs generated Java in-process |
| `StabilizationLoop` | `codegen-service` | 3-attempt compile/run/fix loop |
| `TestPrService` | `codegen-service` | Opens PR on target repo with generated test file |
| `CopilotCliClient` | `common` | Spawns `gh api` subprocess to call GitHub Copilot |
| `CopilotClient` | `common` | Direct HTTP to GitHub Copilot API |
| `OpenAiClient` | `common` | Direct HTTP to OpenAI-compatible endpoint |
| `AiClientConfig` | `common` | Spring `@Configuration` — selects which `AiClient` bean to create |

