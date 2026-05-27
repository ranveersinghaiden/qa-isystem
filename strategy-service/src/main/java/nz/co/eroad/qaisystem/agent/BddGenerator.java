package nz.co.eroad.qaisystem.agent;

import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import nz.co.eroad.qaisystem.model.TestStrategy;
import nz.co.eroad.qaisystem.service.TestPrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Dumb template-based BDD generator.
 * Generates Gherkin scenarios from TestStrategy requirements.
 * No orchestration — just template logic.
 * Creates a human-review PR; codegen starts only after approval via /api/strategy/approve-bdd.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BddGenerator {

    private final TestPrService testPrService;

    public BddScenario generate(TestStrategy strategy, ImpactEnvelope envelope) {
        log.info("[BddGenerator] Generating for strategy '{}' PR '{}'",
                strategy.getStrategyId(), envelope.getPrId());

        List<BddScenario.Scenario> scenarios = new ArrayList<>();
        for (TestStrategy.TestRequirement req : strategy.getNewTestRequirements()) {
            scenarios.addAll(buildScenarios(req, envelope));
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

        // Human review PR — codegen triggered separately after approval
        testPrService.createBddPr(bdd);

        log.info("[BddGenerator] {} scenarios created for PR '{}' (fullRegression={} expandedScope={})",
                scenarios.size(), envelope.getPrId(),
                strategy.isFullRegressionRequired(), strategy.isExpandedScope());

        return bdd;
    }

    // ─── Template builders ─────────────────────────────────────────────────────

    private List<BddScenario.Scenario> buildScenarios(TestStrategy.TestRequirement req,
                                                       ImpactEnvelope envelope) {
        List<BddScenario.Scenario> list = new ArrayList<>();

        list.add(BddScenario.Scenario.builder()
                .scenarioId(UUID.randomUUID().toString())
                .title("Successful operation of " + req.getFeatureName())
                .type("Scenario")
                .tags(buildTags(req, envelope))
                .givenSteps(List.of("the system is running", "a valid user session exists"))
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
                .tags(buildTags(req, envelope))
                .givenSteps(List.of("the system is running", "an invalid request is prepared"))
                .whenSteps(List.of("the client calls " + req.getFeatureName()))
                .thenSteps(List.of("response status is 4xx or 5xx", "error message is descriptive"))
                .andSteps(List.of("And the error is logged"))
                .testType(req.getTestType().name())
                .build());

        if (envelope.getDetectedChangeTypes().contains(ImpactEnvelope.ChangeType.API_CHANGE)) {
            list.add(buildBoundaryScenario(req));
        }

        return list;
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

