package nz.co.eroad.qaisystem.agent;

import nz.co.eroad.qaisystem.cache.PromptResponseCache;
import nz.co.eroad.qaisystem.execution.RepoContext;
import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.ChatMessage;
import nz.co.eroad.qaisystem.model.ConversationHistory;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import nz.co.eroad.qaisystem.model.TestStrategy;
import nz.co.eroad.qaisystem.monitor.AiCostMonitor;
import nz.co.eroad.qaisystem.service.ConversationStore;
import nz.co.eroad.qaisystem.service.RepoContextService;
import nz.co.eroad.qaisystem.service.TestPrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates Gherkin BDD scenarios from a {@link TestStrategy}.
 *
 * <h3>Generation modes</h3>
 * <ol>
 *   <li><b>Cache mode</b> (fastest) — returns a previously AI-generated response when a
 *       matching {@link PromptResponseCache.CacheKey} exists in Redis.</li>
 *   <li><b>AI mode</b> (required) — calls the Copilot CLI with a rich system prompt
 *       incorporating the target repo's conductor agent instructions and caches the response.</li>
 * </ol>
 *
 * <p>There is no built-in template fallback. If the Copilot CLI is unavailable or returns
 * an empty response, an {@link IllegalStateException} is thrown so the failure is visible
 * immediately rather than producing silent placeholder output.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BddGenerator {

    private static final String GENERATION_PREAMBLE =
            "You are a QA test generation system. Apply the following project-specific " +
            "conductor instructions strictly within the scope of generating BDD scenarios " +
            "and test code. Do not deviate from test generation tasks.\n\n";

    private final TestPrService       testPrService;
    private final AiClient            aiClient;
    private final RepoContextService  repoContextService;
    private final PromptResponseCache cache;
    private final AiCostMonitor       monitor;
    private final ConversationStore   conversationStore;

    public BddScenario generate(TestStrategy strategy, ImpactEnvelope envelope) {
        log.info("[BddGenerator] Generating for strategy '{}' PR '{}'",
                strategy.getStrategyId(), envelope.getPrId());

        monitor.recordRequest();

        RepoContext context = repoContextService.getContext("API");

        var cacheKey = buildCacheKey(envelope, strategy, context);
        var cached   = cache.get(cacheKey);

        List<BddScenario.Scenario> scenarios;

        if (cached.isPresent()) {
            monitor.recordCacheHit();
            log.info("[BddGenerator] CACHE HIT for PR '{}' — skipping AI call", envelope.getPrId());
            scenarios = parseGherkinToScenarios(cached.get(), envelope);

        } else if (aiClient.isAvailable()) {
            String systemPrompt = buildSystemPrompt(context);
            String userPrompt   = buildUserPrompt(strategy, envelope, context);

            log.debug("[BddGenerator] Calling Copilot CLI for BDD generation (systemPrompt={} chars, userPrompt={} chars)",
                    systemPrompt.length(), userPrompt.length());

            String gherkin = aiClient.complete(systemPrompt, userPrompt);
            if (gherkin != null && !gherkin.isBlank()) {
                log.info("[BddGenerator] Copilot CLI returned {} chars of Gherkin", gherkin.length());
                cache.put(cacheKey, gherkin);
                monitor.recordAiExecuted();

                // Save initial conversation so feedback-service has history context for first rejection
                try {
                    var turns = List.of(ChatMessage.user(userPrompt), ChatMessage.assistant(gherkin));
                    conversationStore.save(envelope.getPrId() + ":bdd",
                            new ConversationHistory(envelope.getPrId(), turns, 1, Instant.now()));
                    log.debug("[BddGenerator] Saved initial BDD conversation for PR '{}'", envelope.getPrId());
                } catch (Exception e) {
                    log.warn("[BddGenerator] Could not save conversation history for PR '{}': {}",
                            envelope.getPrId(), e.getMessage());
                }

                scenarios = parseGherkinToScenarios(gherkin, envelope);
            } else {
                monitor.recordAiFailure();
                throw new IllegalStateException(
                        "[BddGenerator] Copilot CLI returned an empty response for PR '"
                        + envelope.getPrId() + "'. Check 'gh auth status' and retry.");
            }

        } else {
            throw new IllegalStateException(
                    "[BddGenerator] Copilot CLI is not available. " +
                    "Run 'gh auth login' and ensure the 'gh' executable is on PATH. " +
                    "PR: " + envelope.getPrId());
        }

        BddScenario bdd = BddScenario.builder()
                .scenarioId(UUID.randomUUID().toString())
                .featureTitle("Tests for PR: " + envelope.getPrId())
                .featureDescription(envelope.getChangesSummary())
                .scenarios(scenarios)
                .prId(envelope.getPrId())
                .prTitle(envelope.getPrTitle())
                .strategyId(strategy.getStrategyId())
                .bddType(BddScenario.BddType.NEW)
                .prContext(envelope.getPrContext())
                .build();

        testPrService.createBddPr(bdd);

        log.info("[BddGenerator] {} scenarios created for PR '{}' (copilotCli={} conductorAgent={} productExpert={})",
                scenarios.size(), envelope.getPrId(),
                aiClient.isAvailable(), context.hasConductorAgent(), context.hasProductExpert());

        return bdd;
    }

    // ─── Cache key builder ─────────────────────────────────────────────────────

    /** Builds a cache key from the envelope's primary classification attributes. */
    private PromptResponseCache.CacheKey buildCacheKey(ImpactEnvelope envelope,
                                                        TestStrategy strategy,
                                                        RepoContext context) {
        var changeTypes = envelope.getDetectedChangeTypes();
        String changeType = (changeTypes == null || changeTypes.isEmpty())
                ? "UNKNOWN" : changeTypes.get(0).name();

        var components = envelope.getImpactedComponents();
        String componentType = (components == null || components.isEmpty())
                ? "UNKNOWN" : components.get(0).getType().name();

        String repoName = (context != null && context.getBasePackage() != null)
                ? context.getBasePackage() : "default";

        return new PromptResponseCache.CacheKey(
                changeType, componentType, envelope.getRiskLevel().name(), repoName);
    }

    // ─── AI prompt builders ────────────────────────────────────────────────────

    private String buildSystemPrompt(RepoContext context) {
        StringBuilder sb = new StringBuilder(GENERATION_PREAMBLE);

        // Conductor agent from the target repo — primary role directive
        if (context.hasConductorAgent()) {
            sb.append("=== CONDUCTOR AGENT INSTRUCTIONS (from target repo .github/agents/) ===\n\n");
            sb.append(context.getConductorAgentContent()).append("\n\n");
            log.debug("[BddGenerator] Conductor agent instructions injected into system prompt ({} chars)",
                    context.getConductorAgentContent().length());
        } else {
            // No conductor agent — use expert QA engineer baseline
            sb.append("You are an expert QA engineer specialising in writing Gherkin BDD scenarios.\n\n");
            sb.append("Your scenarios must be:\n");
            sb.append("- Written in clear, business-readable language\n");
            sb.append("- Covering happy path, error cases, and boundary conditions\n");
            sb.append("- Tagged appropriately (@api, @ui, @mobile, @smoke, @regression)\n");
            sb.append("- Specific to the product and changes described\n\n");
            log.debug("[BddGenerator] No conductor agent found in target repo — using baseline QA engineer prompt");
        }

        if (context.hasProductExpert()) {
            sb.append(context.productExpertSystemPrompt()).append("\n");
        }
        if (context.getRepoAiqaContext() != null && !context.getRepoAiqaContext().isBlank()) {
            sb.append(context.aiqaContextSection()).append("\n");
        }
        // Other agent instruction files (non-conductor) — appended as supplemental context
        if (context.hasAgentInstructions()) {
            context.getAgentInstructions().entrySet().stream()
                    .filter(e -> !e.getKey().toLowerCase().contains("conductor"))
                    .forEach(e -> sb.append("-- ").append(e.getKey()).append(" --\n")
                            .append(e.getValue()).append("\n\n"));
        }
        if (context.getSampleTests() != null && !context.getSampleTests().isEmpty()) {
            sb.append("=== EXISTING TEST STYLE (follow these patterns) ===\n\n");
            context.getSampleTests().forEach((name, src) ->
                    sb.append("-- ").append(name).append(" --\n").append(src).append("\n\n"));
        }

        return sb.toString();
    }

    private String buildUserPrompt(TestStrategy strategy, ImpactEnvelope envelope,
                                   RepoContext context) {
        String reqs = strategy.getNewTestRequirements().stream()
                .map(r -> "  - " + r.getFeatureName() + " (" + r.getTestType() + ")")
                .collect(Collectors.joining("\n"));

        String changeTypes = envelope.getDetectedChangeTypes() == null ? "unknown"
                : envelope.getDetectedChangeTypes().stream()
                           .map(Enum::name).collect(Collectors.joining(", "));

        String externalCtx = (envelope.getPrContext() != null)
                ? envelope.getPrContext().asPromptSection() : "";

        return """
                Generate a Gherkin feature file for the following code change.
                
                PR ID         : %s
                Risk Level    : %s
                Change Types  : %s
                Summary       : %s
                
                Test requirements (what needs to be tested):
                %s
                
                Full regression needed : %s
                Expanded scope         : %s
                %s
                Return ONLY the Gherkin feature file content starting with "Feature:".
                Include @tags on each scenario. Do not add any explanation outside the Gherkin.
                """.formatted(
                envelope.getPrId(),
                envelope.getRiskLevel(),
                changeTypes,
                envelope.getChangesSummary(),
                reqs,
                strategy.isFullRegressionRequired(),
                strategy.isExpandedScope(),
                externalCtx.isBlank() ? "" : "\n" + externalCtx);
    }

    /**
     * Very lightweight Gherkin parser — converts the AI's response into
     * {@link BddScenario.Scenario} objects. Each block starting with
     * "Scenario:" or "Scenario Outline:" becomes one scenario.
     */
    private List<BddScenario.Scenario> parseGherkinToScenarios(String gherkin,
                                                                 ImpactEnvelope envelope) {
        List<BddScenario.Scenario> result = new ArrayList<>();
        String[] lines = gherkin.split("\n");

        List<String> pendingTags = new ArrayList<>();
        BddScenario.Scenario.ScenarioBuilder current = null;
        List<String> given = null, when = null, then = null, and = null;
        String currentKeyword = null;

        for (String rawLine : lines) {
            String line = rawLine.strip();

            if (line.startsWith("@")) {
                Arrays.stream(line.split("\\s+")).forEach(pendingTags::add);
                continue;
            }

            if (line.startsWith("Scenario Outline:") || line.startsWith("Scenario:")) {
                if (current != null) {
                    result.add(finalise(current, given, when, then, and));
                }
                String type  = line.startsWith("Scenario Outline:") ? "Scenario Outline" : "Scenario";
                String title = line.substring(type.length() + 1).trim();
                current = BddScenario.Scenario.builder()
                        .scenarioId(UUID.randomUUID().toString())
                        .title(title)
                        .type(type)
                        .tags(new ArrayList<>(pendingTags))
                        .testType(inferTestType(pendingTags, envelope));
                given = new ArrayList<>(); when = new ArrayList<>();
                then  = new ArrayList<>(); and  = new ArrayList<>();
                pendingTags.clear();
                currentKeyword = null;
                continue;
            }

            if (current == null) continue;

            if (line.startsWith("Given "))       { currentKeyword = "given"; given.add(line.substring(6).trim()); }
            else if (line.startsWith("When "))   { currentKeyword = "when";  when.add(line.substring(5).trim()); }
            else if (line.startsWith("Then "))   { currentKeyword = "then";  then.add(line.substring(5).trim()); }
            else if (line.startsWith("And ") || line.startsWith("But ")) {
                String step = line.substring(4).trim();
                if      ("given".equals(currentKeyword)) given.add(step);
                else if ("when".equals(currentKeyword))  when.add(step);
                else if ("then".equals(currentKeyword))  then.add(step);
                else                                     and.add(step);
            }
        }
        if (current != null) result.add(finalise(current, given, when, then, and));

        if (result.isEmpty()) {
            log.warn("[BddGenerator] Could not parse any scenarios from AI response — using single placeholder");
            result.add(BddScenario.Scenario.builder()
                    .scenarioId(UUID.randomUUID().toString())
                    .title("AI-generated scenario for PR " + envelope.getPrId())
                    .type("Scenario")
                    .tags(List.of("@auto-generated"))
                    .givenSteps(List.of("the system is ready"))
                    .whenSteps(List.of("the change is deployed"))
                    .thenSteps(List.of("all acceptance criteria are met"))
                    .andSteps(List.of())
                    .testType("API")
                    .build());
        }
        return result;
    }

    private BddScenario.Scenario finalise(BddScenario.Scenario.ScenarioBuilder b,
                                          List<String> given, List<String> when,
                                          List<String> then,  List<String> and) {
        return b.givenSteps(given).whenSteps(when).thenSteps(then).andSteps(and).build();
    }

    private String inferTestType(List<String> tags, ImpactEnvelope envelope) {
        if (tags != null) {
            for (String t : tags) {
                String tl = t.toLowerCase();
                if (tl.contains("mobile") || tl.contains("appium")) return "MOBILE";
                if (tl.contains("ui") || tl.contains("web") || tl.contains("selenium")) return "UI";
            }
        }
        return "API";
    }

}
