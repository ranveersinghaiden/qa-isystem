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
 *   <li><b>Conductor agent mode</b> (only generation path) — delegates to the local
 *       {@code copilot} CLI via {@link ConductorAgentRunner}, which launches a monitored
 *       subprocess that runs the repository's <b>Conductor</b> agent in the cloned target
 *       repo directory. The Conductor reads its own {@code .github/agents/} instructions
 *       and orchestrates any internal sub-delegation itself. No AI API is called directly
 *       and no other agent is ever invoked.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BddGenerator {

    private final TestPrService          testPrService;
    private final RepoContextService     repoContextService;
    private final PromptResponseCache    cache;
    private final AiCostMonitor          monitor;
    private final ConversationStore      conversationStore;
    private final ConductorAgentRunner   conductorAgentRunner;

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
            String gherkin = generateGherkin(strategy, envelope);
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
     * Single generation path: delegate to the repository's Conductor agent via a monitored
     * {@code copilot} subprocess running in the cloned target repo directory.
     */
    private String generateGherkin(TestStrategy strategy, ImpactEnvelope envelope) {
        Path workingDir = repoContextService.getLocalRepoPath();
        var conductorPrompt = buildConductorPrompt(strategy, envelope);
        log.info("[BddGenerator] Delegating BDD generation to Conductor for PR '{}' workingDir='{}' (prompt {} chars)",
                envelope.getPrId(), workingDir, conductorPrompt.length());
        try {
            var gherkin = conductorAgentRunner.delegateToConductor(conductorPrompt, workingDir);
            log.info("[BddGenerator] Conductor produced {} chars of Gherkin for PR '{}'",
                    gherkin == null ? 0 : gherkin.length(), envelope.getPrId());
            return gherkin;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "[BddGenerator] Interrupted during Conductor delegation for PR '"
                    + envelope.getPrId() + "'", e);
        }
    }

    // ─── Prompt builder ────────────────────────────────────────────────────────

    private String buildConductorPrompt(TestStrategy strategy, ImpactEnvelope envelope) {
        var reqs = strategy.getNewTestRequirements().stream()
                .map(r -> "  - " + r.getFeatureName() + " (" + r.getTestType() + ")")
                .collect(Collectors.joining("\n"));
        var changeTypes = envelope.getDetectedChangeTypes() == null ? "unknown"
                : envelope.getDetectedChangeTypes().stream()
                           .map(Enum::name).collect(Collectors.joining(", "));
        var externalCtx = (envelope.getPrContext() != null)
                ? envelope.getPrContext().asPromptSection() : "";

        return """
                QA task: produce a Gherkin feature file of BDD scenarios for the following code
                change. Gather any context you need from this repository yourself.

                Output ONLY the Gherkin starting with "Feature:". Tag each scenario
                (@api, @ui, @mobile, @smoke, @regression). No prose outside the Gherkin.

                PR ID          : %s
                Risk Level     : %s
                Change Types   : %s
                Summary        : %s
                Full regression: %s

                Test requirements:
                %s
                %s
                """.formatted(
                envelope.getPrId(),
                envelope.getRiskLevel(),
                changeTypes,
                envelope.getChangesSummary(),
                strategy.isFullRegressionRequired(),
                reqs,
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
