package nz.co.eroad.qaisystem.agent;

import nz.co.eroad.qaisystem.execution.RepoContext;
import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import nz.co.eroad.qaisystem.model.TestStrategy;
import nz.co.eroad.qaisystem.service.RepoContextService;
import nz.co.eroad.qaisystem.service.TestPrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates Gherkin BDD scenarios from a {@link TestStrategy}.
 *
 * <h3>Generation modes</h3>
 * <ol>
 *   <li><b>AI mode</b> (preferred) — when {@link AiClient#isAvailable()} is {@code true},
 *       calls the AI with a rich system prompt containing:
 *       <ul>
 *         <li>Product expert context from {@code productExpert/} in the test repo</li>
 *         <li>Repo QA conventions from {@code .aiqa/context.md}</li>
 *         <li>Agent instructions from {@code .github/agents/}</li>
 *         <li>Sample existing BDD scenarios for style reference</li>
 *       </ul>
 *       The AI returns a complete Gherkin feature file which is parsed into a
 *       {@link BddScenario}.</li>
 *   <li><b>Enhanced template mode</b> (fallback) — when no AI is configured, uses
 *       structured templates enriched with product expert content as comments.
 *       This still reflects the product's domain language even without AI.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BddGenerator {

    private final TestPrService      testPrService;
    private final AiClient           aiClient;
    private final RepoContextService repoContextService;

    public BddScenario generate(TestStrategy strategy, ImpactEnvelope envelope) {
        log.info("[BddGenerator] Generating for strategy '{}' PR '{}'",
                strategy.getStrategyId(), envelope.getPrId());

        // Load repo context (includes product expert + agent instructions)
        RepoContext context = repoContextService.getContext("API");

        List<BddScenario.Scenario> scenarios;

        if (aiClient.isAvailable()) {
            scenarios = generateWithAi(strategy, envelope, context);
        } else {
            scenarios = generateWithTemplates(strategy, envelope, context);
        }

        BddScenario bdd = BddScenario.builder()
                .scenarioId(UUID.randomUUID().toString())
                .featureTitle("Tests for PR: " + envelope.getPrId())
                .featureDescription(envelope.getChangesSummary())
                .scenarios(scenarios)
                .prId(envelope.getPrId())
                .strategyId(strategy.getStrategyId())
                .bddType(BddScenario.BddType.NEW)
                .build();

        // Human review PR — codegen triggered after merge
        testPrService.createBddPr(bdd);

        log.info("[BddGenerator] {} scenarios created for PR '{}' (aiMode={} productExpert={} fullRegression={})",
                scenarios.size(), envelope.getPrId(),
                aiClient.isAvailable(), context.hasProductExpert(),
                strategy.isFullRegressionRequired());

        return bdd;
    }

    // ─── AI generation ─────────────────────────────────────────────────────────

    private List<BddScenario.Scenario> generateWithAi(TestStrategy strategy,
                                                       ImpactEnvelope envelope,
                                                       RepoContext context) {
        String systemPrompt = buildSystemPrompt(context);
        String userPrompt   = buildUserPrompt(strategy, envelope, context);

        log.debug("[BddGenerator] Calling AI for BDD generation (systemPrompt={} chars, userPrompt={} chars)",
                systemPrompt.length(), userPrompt.length());

        String gherkin = aiClient.complete(systemPrompt, userPrompt);
        if (gherkin != null && !gherkin.isBlank()) {
            log.info("[BddGenerator] AI returned {} chars of Gherkin", gherkin.length());
            return parseGherkinToScenarios(gherkin, envelope);
        }

        log.warn("[BddGenerator] AI returned empty response — falling back to templates");
        return generateWithTemplates(strategy, envelope, context);
    }

    private String buildSystemPrompt(RepoContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert QA engineer specialising in writing Gherkin BDD scenarios.\n\n");
        sb.append("Your scenarios must be:\n");
        sb.append("- Written in clear, business-readable language\n");
        sb.append("- Covering happy path, error cases, and boundary conditions\n");
        sb.append("- Tagged appropriately (@api, @ui, @mobile, @smoke, @regression)\n");
        sb.append("- Specific to the product and changes described\n\n");

        // Product expert context — highest priority
        if (context.hasProductExpert()) {
            sb.append(context.productExpertSystemPrompt()).append("\n");
        }

        // Repo-level QA context
        if (context.getRepoAiqaContext() != null && !context.getRepoAiqaContext().isBlank()) {
            sb.append(context.aiqaContextSection()).append("\n");
        }

        // Agent instructions from .github/agents/
        if (context.hasAgentInstructions()) {
            sb.append("=== REPOSITORY AGENT INSTRUCTIONS ===\n\n");
            context.getAgentInstructions().forEach((file, content) ->
                    sb.append("-- ").append(file).append(" --\n").append(content).append("\n\n"));
        }

        // Style samples from existing tests
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
                
                Return ONLY the Gherkin feature file content starting with "Feature:".
                Include @tags on each scenario. Do not add any explanation outside the Gherkin.
                """.formatted(
                envelope.getPrId(),
                envelope.getRiskLevel(),
                changeTypes,
                envelope.getChangesSummary(),
                reqs,
                strategy.isFullRegressionRequired(),
                strategy.isExpandedScope());
    }

    /**
     * Very lightweight Gherkin parser — converts the AI's response into
     * {@link BddScenario.Scenario} objects.  Each block starting with
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

            if (line.startsWith("Given ")) { currentKeyword = "given"; given.add(line.substring(6).trim()); }
            else if (line.startsWith("When "))  { currentKeyword = "when";  when.add(line.substring(5).trim()); }
            else if (line.startsWith("Then "))  { currentKeyword = "then";  then.add(line.substring(5).trim()); }
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

    // ─── Enhanced template generation (no AI) ──────────────────────────────────

    private List<BddScenario.Scenario> generateWithTemplates(TestStrategy strategy,
                                                              ImpactEnvelope envelope,
                                                              RepoContext context) {
        List<BddScenario.Scenario> list = new ArrayList<>();
        for (TestStrategy.TestRequirement req : strategy.getNewTestRequirements()) {
            list.addAll(buildScenarios(req, envelope, context));
        }
        return list;
    }

    private List<BddScenario.Scenario> buildScenarios(TestStrategy.TestRequirement req,
                                                       ImpactEnvelope envelope,
                                                       RepoContext context) {
        List<BddScenario.Scenario> list = new ArrayList<>();
        List<String> tags = buildTags(req, envelope);

        // Enrich given steps with product context when available
        List<String> givenSteps = buildGivenSteps(req, context);

        list.add(BddScenario.Scenario.builder()
                .scenarioId(UUID.randomUUID().toString())
                .title("Successful operation of " + req.getFeatureName())
                .type("Scenario")
                .tags(tags)
                .givenSteps(givenSteps)
                .whenSteps(List.of("the client calls " + req.getFeatureName()))
                .thenSteps(List.of("response status is 200", "response body contains expected data",
                        "operation completes within 2000ms"))
                .andSteps(List.of())
                .testType(req.getTestType().name())
                .build());

        list.add(BddScenario.Scenario.builder()
                .scenarioId(UUID.randomUUID().toString())
                .title("Error handling for " + req.getFeatureName())
                .type("Scenario")
                .tags(tags)
                .givenSteps(List.of("the system is running", "an invalid request is prepared"))
                .whenSteps(List.of("the client calls " + req.getFeatureName()))
                .thenSteps(List.of("response status is 4xx or 5xx", "error message is descriptive"))
                .andSteps(List.of("And the error is logged"))
                .testType(req.getTestType().name())
                .build());

        if (envelope.getDetectedChangeTypes() != null
                && envelope.getDetectedChangeTypes().contains(ImpactEnvelope.ChangeType.API_CHANGE)) {
            list.add(buildBoundaryScenario(req));
        }

        return list;
    }

    private List<String> buildGivenSteps(TestStrategy.TestRequirement req, RepoContext context) {
        List<String> steps = new ArrayList<>();
        steps.add("the system is running");
        steps.add("a valid user session exists");
        // If product expert has context, add a domain-specific step
        if (context.hasProductExpert()) {
            String productName = context.getProductExpertSections().keySet().iterator().next();
            steps.add("the " + productName + " service is available");
        }
        return steps;
    }

    private List<String> buildTags(TestStrategy.TestRequirement req, ImpactEnvelope envelope) {
        List<String> tags = new ArrayList<>();
        tags.add("@" + req.getTestType().name().toLowerCase());
        tags.add("@pr-" + envelope.getPrId());
        tags.add("@auto-generated");
        if (envelope.getRiskLevel() == ImpactEnvelope.RiskLevel.HIGH
                || envelope.getRiskLevel() == ImpactEnvelope.RiskLevel.CRITICAL) {
            tags.add("@smoke");
        }
        return tags;
    }

    private BddScenario.Scenario buildBoundaryScenario(TestStrategy.TestRequirement req) {
        return BddScenario.Scenario.builder()
                .scenarioId(UUID.randomUUID().toString())
                .title("Boundary testing for " + req.getFeatureName())
                .type("Scenario Outline")
                .tags(List.of("@boundary", "@api", "@auto-generated"))
                .givenSteps(List.of("the system is running", "input is <input>"))
                .whenSteps(List.of("the client sends the request to " + req.getFeatureName()))
                .thenSteps(List.of("response status is <expectedStatus>"))
                .andSteps(List.of())
                .testType(req.getTestType().name())
                .examples(List.of(BddScenario.Scenario.ExampleRow.builder()
                        .headers(List.of("input", "expectedStatus"))
                        .rows(List.of(
                                List.of("validPayload",  "200"),
                                List.of("emptyPayload",  "400"),
                                List.of("oversizeInput", "413")))
                        .build()))
                .build();
    }
}

