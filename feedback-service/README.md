# feedback-service

**Port:** `8084`  
**Phase:** 7 — AI-Native Feedback Loop  
**Role:** Consumes `FeedbackEvent` from `FeedbackQueue` (published by strategy-service when a QA PR is rejected), re-generates improved BDD scenarios or test code incorporating the reviewer feedback, and opens product expert update PRs when a knowledge gap is detected.

## Kafka

| Direction | Topic | Payload |
|-----------|-------|---------|
| **Consumes** | `FeedbackQueue` | `FeedbackEvent` JSON |

Consumer group: `feedback-service-group`

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `FeedbackServiceApplication` | Spring Boot entry point — scans `nz.co.eroad.qaisystem` |
| `FeedbackEventConsumer` | Kafka consumer — deserialises `FeedbackEvent`, delegates to `PrFeedbackService` |
| `PrFeedbackService` | Core feedback logic: fetch comments, classify (KNOWLEDGE_GAP vs STYLE_ONLY), update product expert, re-generate, create revised PR |

## Shared from `common`

`GitHubService`, `AiClient`, `OpenAiClient`, `PrTracker`, `RepoContextService`, `RepoContext`, `TargetRepoProperties`, `ProductExpertContext`, all models including `FeedbackEvent`.

## Feedback Flow

```
FeedbackQueue message received
    │
    ▼
FeedbackEventConsumer deserialises FeedbackEvent
    │
    ├─ PrType.BDD  → PrFeedbackService.handleBddRejection()
    │     1. Fetch GitHub review comments
    │     2. AI classify: KNOWLEDGE_GAP or STYLE_ONLY
    │     3. If KNOWLEDGE_GAP → create product expert update PR
    │     4. Re-generate BDD scenarios with feedback context
    │     5. Create revised BDD PR → PrTracker.trackBdd()
    │        Title: `[AI-QA] Revised: {prTitle}` (falls back to `… for PR: {prId}`)
    │
    └─ PrType.TEST → PrFeedbackService.handleTestRejection()
          1. Fetch GitHub review comments
          2. AI classify: KNOWLEDGE_GAP or STYLE_ONLY
          3. If KNOWLEDGE_GAP → create product expert update PR
          4. Re-generate test code with feedback context
          5. Create revised test PR → PrTracker.trackTest()
             Title: `[AI-QA] Revised Tests: {prTitle}` (falls back to `… for PR: {prId}`)
```

## Revised PR Title Format

| PR type | Title format |
|---------|-------------|
| Revised BDD | `[AI-QA] Revised: {prTitle}` |
| Revised test code | `[AI-QA] Revised Tests: {prTitle}` |

`{prTitle}` comes from `FeedbackEvent → BddScenario.prTitle` / `TestScript.prTitle`,
which originates from `PullRequest.title` at the pipeline entry point.

## Configuration

```yaml
server.port: 8084
spring.kafka.consumer.group-id: feedback-service-group

# AI provider: openai or copilot (default)
aiqa.ai.provider: ${AI_PROVIDER:copilot}

# When AI_PROVIDER=openai:
aiqa.ai.openai.api-key:  ${OPENAI_API_KEY:}   # Required for AI re-generation
aiqa.ai.openai.base-url: ${OPENAI_BASE_URL:https://api.openai.com}
aiqa.ai.openai.model:    ${OPENAI_MODEL:gpt-4o}

# When AI_PROVIDER=copilot:
aiqa.ai.copilot.token:    ${GITHUB_COPILOT_TOKEN:}   # GitHub token with Copilot access
aiqa.ai.copilot.base-url: ${COPILOT_BASE_URL:https://api.githubcopilot.com}
aiqa.ai.copilot.model:    ${COPILOT_MODEL:gpt-4o}
```

## No Mocking Policy

This service has no unit tests with mocks. Integration tests would use embedded Kafka (`@EmbeddedKafka`) and a real GitHub sandbox. AI calls use `MockWebServer` (real HTTP server, not a mock).

