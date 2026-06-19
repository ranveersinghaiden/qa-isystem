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

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates Gherkin BDD scenarios from a {@link TestStrategy}.
 *
 * <h3>Generation modes</h3>
 * <ol>
 *   <li><b>Cache mode</b> (fastest) — returns a previously generated response when a
 *       matching {@link PromptResponseCache.CacheKey} exists in Redis.</li>
 *   <li><b>Agent pipeline mode</b> (primary) — delegates to the local {@code copilot} CLI
 *       via {@link CopilotAgentClient}. Phase 1: Conductor produces a test plan. Phase 2:
 *       TestPlanner converts the plan into Gherkin. Each agent sees only its own workspace
 *       instructions — no manual file loading required.</li>
 *   <li><b>AI client fallback</b> — falls back to {@link AiClient} (GitHub Models API) if
 *       the agent pipeline raises an exception.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BddGenerator {

    private static final String GENERATION_PREAMBLE =
            "You are a QA test generation system. Apply the following project-specific " +
            "conductor instructions strictly within the scope of generating BDD scenarios " +
            "and test code. Do not deviate from test generation tasks.\n\n";

    private final TestPrService          testPrService;
    private final AiClient               aiClient;
    private final RepoContextService     repoContextService;
    private final PromptResponseCache    cache;
    private final AiCostMonitor          monitor;
    private final ConversationStore      conversationStore;
    private final CopilotAgentClient     copilotAgentClient;

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

        } else {
            String gherkin = generateGherkin(strategy, envelope, context);
            if (gherkin == null || gherkin.isBlank()) {
                monitor.recordAiFailure();
                throw new IllegalStateException(
                        "[BddGenerator] No Gherkin output produced for PR '"
                        + envelope.getPrId() + "'. Check copilot CLI and gh auth status.");
            }

            log.info("[BddGenerator] Agent pipeline returned {} chars of Gherkin", gherkin.length());
            cache.put(cacheKey, gherkin);
            monitor.recordAiExecuted();

            try {
                var turns = List.of(ChatMessage.user(buildConductorPrompt(strategy, envelope)),
                        ChatMessage.assistant(gherkin));
                conversationStore.save(envelope.getPrId() + ":bdd",
                        new ConversationHistory(envelope.getPrId(), turns, 1, Instant.now()));
                log.debug("[BddGenerator] Saved initial BDD conversation for PR '{}'", envelope.getPrId());
            } catch (Exception e) {
                log.warn("[BddGenerator] Could not save conversation history for PR '{}': {}",
                        envelope.getPrId(), e.getMessage());
            }

            scenarios = parseGherkinToScenarios(gherkin, envelope);
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

        log.info("[BddGenerator] {} scenarios created for PR '{}' (conductorAgent={} productExpert={})",
                scenarios.size(), envelope.getPrId(),
                context.hasConductorAgent(), context.hasProductExpert());

        return bdd;
    }

    // ─── Generation orchestration ──────────────────────────────────────────────

    /**
     * Primary path: two-phase copilot agent pipeline.
     * Fallback: {@link AiClient} single-shot call.
     */
    private String generateGherkin(TestStrategy strategy, ImpactEnvelope envelope,
                                   RepoContext context) {
        try {
            return runTwoPhaseAgentPipeline(strategy, envelope);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "[BddGenerator] Interrupted during agent pipeline for PR '"
                    + envelope.getPrId() + "'", e);
        } catch (Exception e) {
            log.warn("[BddGenerator] Agent pipeline failed for PR '{}' — falling back to AiClient: {}",
                    envelope.getPrId(), e.getMessage());
            if (!aiClient.isAvailable()) {
                throw new IllegalStateException(
                        "[BddGenerator] Agent pipeline failed and AiClient is not available. "
                        + "PR: " + envelope.getPrId(), e);
            }
            var systemPrompt = buildSystemPrompt(context);
            var userPrompt   = buildUserPrompt(strategy, envelope, context);
            log.debug("[BddGenerator] AiClient fallback (systemPrompt={} chars, userPrompt={} chars)",
                    systemPrompt.length(), userPrompt.length());
            return aiClient.complete(systemPrompt, userPrompt);
        }
    }

    /**
     * Phase 1 → Conductor produces a structured test plan.
     * Phase 2 → TestPlanner converts the plan into a Gherkin feature file.
     * Each phase runs in the cloned target repo directory so the agent reads
     * its own {@code .github/agents/} instructions naturally.
     */
    private String runTwoPhaseAgentPipeline(TestStrategy strategy, ImpactEnvelope envelope)
            throws InterruptedException {
        Path workingDir = repoContextService.getLocalRepoPath();
        log.info("[BddGenerator] Starting two-phase agent pipeline for PR '{}' workingDir='{}'",
                envelope.getPrId(), workingDir);

        // Phase 1 — Conductor: decide what to test
        var conductorPrompt = buildConductorPrompt(strategy, envelope);
        log.debug("[BddGenerator] Phase 1 Conductor prompt ({} chars)", conductorPrompt.length());
        var testPlan = copilotAgentClient.runAgent("Conductor", conductorPrompt, workingDir);
        log.info("[BddGenerator] Phase 1 complete — Conductor produced {} chars", testPlan.length());

        // Phase 2 — TestPlanner: write Gherkin from the test plan
        var testPlannerPrompt = buildTestPlannerPrompt(testPlan, strategy, envelope);
        log.debug("[BddGenerator] Phase 2 TestPlanner prompt ({} chars)", testPlannerPrompt.length());
        var gherkin = copilotAgentClient.runAgent("TestPlanner", testPlannerPrompt, workingDir);
        log.info("[BddGenerator] Phase 2 complete — TestPlanner produced {} chars", gherkin.length());

        return gherkin;
    }

    // ─── Prompt builders ───────────────────────────────────────────────────────

    private String buildConductorPrompt(TestStrategy strategy, ImpactEnvelope envelope) {
        var reqs = strategy.getNewTestRequirements().stream()
                .map(r -> "  - " + r.getFeatureName() + " (" + r.getTestType() + ")")
                .collect(Collectors.joining("\n"));
        var changeTypes = envelope.getDetectedChangeTypes() == null ? "unknown"
                : envelope.getDetectedChangeTypes().stream()
                           .map(Enum::name).collect(Collectors.joining(", "));

        return """
                QA task: produce a structured test plan for the following code change.
                Do NOT write Gherkin yet — output a numbered list of test areas, \
                test types, and key scenarios to cover.

                PR ID         : %s
                Risk Level    : %s
                Change Types  : %s
                Summary       : %s
                Full regression: %s

                Test requirements:
                %s
                """.formatted(
                envelope.getPrId(),
                envelope.getRiskLevel(),
                changeTypes,
                envelope.getChangesSummary(),
                strategy.isFullRegressionRequired(),
                reqs);
    }

    private String buildTestPlannerPrompt(String conductorPlan,
                                          TestStrategy strategy,
                                          ImpactEnvelope envelope) {
        var externalCtx = (envelope.getPrContext() != null)
                ? envelope.getPrContext().asPromptSection() : "";

        return """
                Convert the following test plan into a Gherkin feature file.

                Output ONLY the Gherkin starting with "Feature:".
                Include @tags on each scenario (@api, @ui, @mobile, @smoke, @regression).
                Do not add any explanation or prose outside the Gherkin.

                PR ID      : %s
                Risk Level : %s
                %s
                === TEST PLAN (from Conductor) ===
                %s
                """.formatted(
                envelope.getPrId(),
                envelope.getRiskLevel(),
                externalCtx.isBlank() ? "" : externalCtx + "\n",
                conductorPlan);
    }

    // ─── Legacy API-client prompt builders (used by fallback path) ─────────────

    private String buildSystemPrompt(RepoContext context) {
        StringBuilder sb = new StringBuilder(GENERATION_PREAMBLE);

        if (context.hasConductorAgent()) {
            sb.append("=== CONDUCTOR AGENT INSTRUCTIONS (from target repo .github/agents/) ===\n\n");
            sb.append(context.getConductorAgentContent()).append("\n\n");
        } else {
            sb.append("You are an expert QA engineer specialising in writing Gherkin BDD scenarios.\n\n");
            sb.append("Your scenarios must be:\n");
            sb.append("- Written in clear, business-readable language\n");
            sb.append("- Covering happy path, error cases, and boundary conditions\n");
            sb.append("- Tagged appropriately (@api, @ui, @mobile, @smoke, @regression)\n");
            sb.append("- Specific to the product and changes described\n\n");
        }

        if (context.hasProductExpert()) {
            sb.append(context.productExpertSystemPrompt()).append("\n");
        }
        if (context.getRepoAiqaContext() != null && !context.getRepoAiqaContext().isBlank()) {
            sb.append(context.aiqaContextSection()).append("\n");
        }
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
        var reqs = strategy.getNewTestRequirements().stream()
                .map(r -> "  - " + r.getFeatureName() + " (" + r.getTestType() + ")")
                .collect(Collectors.joining("\n"));
        var changeTypes = envelope.getDetectedChangeTypes() == null ? "unknown"
                : envelope.getDetectedChangeTypes().stream()
                           .map(Enum::name).collect(Collectors.joining(", "));
        var externalCtx = (envelope.getPrContext() != null)
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

    // ─── Cache key builder ─────────────────────────────────────────────────────

    private PromptResponseCache.CacheKey buildCacheKey(ImpactEnvelope envelope,
                                                        TestStrategy strategy,
                                                        RepoContext context) {
        var changeTypes = envelope.getDetectedChangeTypes();
        var changeType = (changeTypes == null || changeTypes.isEmpty())
                ? "UNKNOWN" : changeTypes.get(0).name();

        var components = envelope.getImpactedComponents();
        var componentType = (components == null || components.isEmpty())
                ? "UNKNOWN" : components.get(0).getType().name();

        var repoName = (context != null && context.getBasePackage() != null)
                ? context.getBasePackage() : "default";

        return new PromptResponseCache.CacheKey(
                changeType, componentType, envelope.getRiskLevel().name(), repoName);
    }

    // ─── Gherkin parser ────────────────────────────────────────────────────────

    private List<BddScenario.Scenario> parseGherkinToScenarios(String gherkin,
                                                                ImpactEnvelope envelope) {
        List<BddScenario.Scenario> result = new ArrayList<>();
        var lines = gherkin.split("\n");

        List<String> pendingTags = new ArrayList<>();
        BddScenario.Scenario.ScenarioBuilder current = null;
        List<String> given = null, when = null, then = null, and = null;
        String currentKeyword = null;

        for (var rawLine : lines) {
            var line = rawLine.strip();

            if (line.startsWith("@")) {
                Arrays.stream(line.split("\\s+")).forEach(pendingTags::add);
                continue;
            }

            if (line.startsWith("Scenario Outline:") || line.startsWith("Scenario:")) {
                if (current != null) {
                    result.add(finalise(current, given, when, then, and));
                }
                var type  = line.startsWith("Scenario Outline:") ? "Scenario Outline" : "Scenario";
                var title = line.substring(type.length() + 1).trim();
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
                var step = line.substring(4).trim();
                if      ("given".equals(currentKeyword)) given.add(step);
                else if ("when".equals(currentKeyword))  when.add(step);
                else if ("then".equals(currentKeyword))  then.add(step);
                else                                     and.add(step);
            }
        }
        if (current != null) result.add(finalise(current, given, when, then, and));

        if (result.isEmpty()) {
            log.warn("[BddGenerator] Could not parse any scenarios from agent response — using placeholder");
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
            for (var t : tags) {
                var tl = t.toLowerCase();
                if (tl.contains("mobile") || tl.contains("appium")) return "MOBILE";
                if (tl.contains("ui") || tl.contains("web") || tl.contains("selenium")) return "UI";
            }
        }
        return "API";
    }

}
