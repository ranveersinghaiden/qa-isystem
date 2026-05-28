package nz.co.eroad.qaisystem.agent;

import nz.co.eroad.qaisystem.model.*;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ChangeType;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.RiskLevel;
import nz.co.eroad.qaisystem.model.TestStrategy.StrategyDecision;
import nz.co.eroad.qaisystem.model.TestStrategy.TestRequirement;
import nz.co.eroad.qaisystem.service.E2ECoverageAnalyzer;
import nz.co.eroad.qaisystem.service.TestPrService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 2 — Minimal AI decision layer.
 *
 * Responsibilities (merged from old TestUpdater):
 *   SKIP        → low risk infra change or all-test PR
 *   UPDATE_TESTS → inline BDD delta for existing tests
 *   CREATE_TESTS → full BDD scenario generation
 *
 * Coverage is assessed by {@link E2ECoverageAnalyzer} against the cloned test repo
 * (or UNKNOWN if no repo is configured). The coverage level from the impact-service
 * envelope is intentionally ignored here — it always arrives as UNKNOWN because
 * impact-service has no test repo access.
 *
 * Fallback rules:
 *   LOW confidence (< 0.4)    → flag fullRegressionRequired = true
 *   HIGH / CRITICAL risk      → flag expandedScope = true + widen areas
 *   NONE coverage             → force CREATE_TESTS regardless of change type
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyAgent {

    private final BddGenerator       bddGenerator;
    private final TestPrService      testPrService;
    private final E2ECoverageAnalyzer e2eCoverageAnalyzer;

    private static final double LOW_CONFIDENCE_THRESHOLD = 0.4;

    public TestStrategy decide(ImpactEnvelope envelope) {
        log.info("[StrategyAgent] Evaluating strategy for PR '{}'", envelope.getPrId());

        // Perform real E2E/integration coverage analysis against the test repo.
        // This replaces the UNKNOWN-level coverage from impact-service with actual data.
        CoverageReport coverage = e2eCoverageAnalyzer.analyze(envelope);

        StrategyDecision decision = computeDecision(envelope, coverage);
        TestStrategy strategy     = buildStrategy(envelope, coverage, decision);
        strategy                  = applyFallbackRules(strategy, envelope);

        log.info("[StrategyAgent] PR '{}' → {} | conf={} | fullRegression={} | expandedScope={}",
                envelope.getPrId(), strategy.getDecision(),
                String.format("%.2f", strategy.getConfidenceScore()),
                strategy.isFullRegressionRequired(),
                strategy.isExpandedScope());

        switch (strategy.getDecision()) {
            case SKIP         -> handleSkip(strategy);
            case UPDATE_TESTS -> handleUpdateTests(strategy, envelope);
            case CREATE_TESTS -> bddGenerator.generate(strategy, envelope);
        }

        return strategy;
    }

    // ─── Decision logic ────────────────────────────────────────────────────────

    /**
     * Core decision. Uses the E2E/integration coverage report from the test repo
     * (not the UNKNOWN-level one that arrived from impact-service).
     */
    private StrategyDecision computeDecision(ImpactEnvelope env, CoverageReport coverage) {
        // Coverage NONE → always create tests (repo scan confirmed no integration tests exist)
        if (coverage.getLevel() == CoverageReport.CoverageLevel.NONE
                && !coverage.getUntestedComponents().isEmpty()) {
            log.info("[StrategyAgent] Coverage=NONE (source={}) → forcing CREATE_TESTS for {}",
                    coverage.getSource(), coverage.getUntestedComponents());
            return StrategyDecision.CREATE_TESTS;
        }

        // No diff was parsed — webhook had no content and GitHub fetch didn't run / failed.
        // Default conservatively to CREATE_TESTS so the PR is never silently skipped.
        // Note: "0 == 0" would otherwise match the test-files-only SKIP rule below.
        if (env.getTotalFilesChanged() == 0) {
            log.warn("[StrategyAgent] PR '{}' has zero parsed diff files — " +
                    "webhook had no diff content. Defaulting to CREATE_TESTS. " +
                    "Tip: configure GITHUB_TOKEN + github.default-org in pr-service to " +
                    "auto-fetch diffs from GitHub.", env.getPrId());
            return StrategyDecision.CREATE_TESTS;
        }

        // Only infra/config changes with LOW risk → SKIP
        boolean onlyInfra = env.getDetectedChangeTypes().stream()
                .allMatch(ct -> ct == ChangeType.CONFIGURATION_CHANGE
                        || ct == ChangeType.DEPENDENCY_UPDATE);
        if (onlyInfra && env.getRiskLevel() == RiskLevel.LOW) return StrategyDecision.SKIP;

        // All changes are test files only → developer already handled it
        if (env.getTotalFilesChanged() == env.getAffectedTestFiles()) return StrategyDecision.SKIP;

        // HIGH/CRITICAL → always CREATE
        if (env.getRiskLevel() == RiskLevel.HIGH || env.getRiskLevel() == RiskLevel.CRITICAL)
            return StrategyDecision.CREATE_TESTS;

        // New feature → CREATE
        if (env.getDetectedChangeTypes().contains(ChangeType.NEW_FEATURE))
            return StrategyDecision.CREATE_TESTS;

        // Partial coverage → UPDATE existing tests to cover the new delta
        if (coverage.getLevel() == CoverageReport.CoverageLevel.PARTIAL)
            return StrategyDecision.UPDATE_TESTS;

        // Existing tests in diff → UPDATE
        if (!env.getExistingTestFiles().isEmpty()) return StrategyDecision.UPDATE_TESTS;

        return StrategyDecision.CREATE_TESTS;
    }

    // ─── Fallback rules ────────────────────────────────────────────────────────

    /**
     * After the base decision, apply safety-net rules:
     *   1. LOW confidence  → mark full regression required
     *   2. HIGH/CRITICAL   → expand test scope to transitive dependencies
     */
    private TestStrategy applyFallbackRules(TestStrategy strategy, ImpactEnvelope env) {
        boolean fullRegression = false;
        boolean expandScope    = false;
        List<String> expandedAreas = new ArrayList<>(
                strategy.getTestAreasTocover() != null ? strategy.getTestAreasTocover() : List.of());

        // Rule 1: LOW confidence → full regression
        if (strategy.getConfidenceScore() < LOW_CONFIDENCE_THRESHOLD) {
            log.warn("[StrategyAgent] LOW confidence ({}) → full regression flagged for PR '{}'",
                    String.format("%.2f", strategy.getConfidenceScore()), strategy.getPrId());
            fullRegression = true;
        }

        // Rule 2: HIGH / CRITICAL risk → expand scope
        if (env.getRiskLevel() == RiskLevel.HIGH || env.getRiskLevel() == RiskLevel.CRITICAL) {
            log.warn("[StrategyAgent] {} risk → expanding scope for PR '{}'",
                    env.getRiskLevel(), strategy.getPrId());
            expandScope = true;
            // Add transitive dependencies as additional test areas
            if (env.getTransitiveDependencies() != null) {
                env.getTransitiveDependencies().stream()
                        .filter(dep -> !expandedAreas.contains(dep))
                        .forEach(expandedAreas::add);
            }
            // Add all impacted component names
            if (env.getImpactedComponents() != null) {
                env.getImpactedComponents().stream()
                        .map(ImpactEnvelope.ImpactedComponent::getComponentName)
                        .filter(name -> !expandedAreas.contains(name))
                        .forEach(expandedAreas::add);
            }
        }

        return TestStrategy.builder()
                .strategyId(strategy.getStrategyId())
                .envelopeId(strategy.getEnvelopeId())
                .prId(strategy.getPrId())
                .decision(strategy.getDecision())
                .reasoning(strategy.getReasoning())
                .confidenceScore(strategy.getConfidenceScore())
                .testsToUpdate(strategy.getTestsToUpdate())
                .newTestRequirements(strategy.getNewTestRequirements())
                .testTypes(strategy.getTestTypes())
                .testAreasTocover(expandedAreas)
                .priority(strategy.getPriority())
                .fullRegressionRequired(fullRegression)
                .expandedScope(expandScope)
                .expandedAreas(expandScope ? expandedAreas : List.of())
                .build();
    }

    // ─── Strategy builder ──────────────────────────────────────────────────────

    private TestStrategy buildStrategy(ImpactEnvelope env, CoverageReport coverage,
                                       StrategyDecision decision) {
        List<TestRequirement> requirements = new ArrayList<>();
        List<String> testTypes             = new ArrayList<>();

        if (decision != StrategyDecision.SKIP) {
            for (ImpactEnvelope.ImpactedComponent comp : env.getImpactedComponents()) {
                String testType = resolveTestType(comp);
                if (!testTypes.contains(testType)) testTypes.add(testType);
                requirements.add(TestRequirement.builder()
                        .featureName(comp.getComponentName())
                        .description("Test coverage for " + comp.getComponentName())
                        .scenarios(generateScenarioHints(comp, env))
                        .testType(TestRequirement.TestType.valueOf(testType))
                        .targetComponent(comp.getFilePath())
                        .build());
            }
        }

        // Prefer uncovered components from E2E analysis as the test areas to focus on.
        // Fall back to suggestedTestAreas from the envelope (populated by impact-service).
        List<String> testAreas = !coverage.getUntestedComponents().isEmpty()
                ? coverage.getUntestedComponents()
                : env.getSuggestedTestAreas();

        return TestStrategy.builder()
                .strategyId(UUID.randomUUID().toString())
                .envelopeId(env.getEnvelopeId())
                .prId(env.getPrId())
                .decision(decision)
                .reasoning(buildReasoning(env, coverage, decision))
                .confidenceScore(computeConfidence(env))
                .testsToUpdate(env.getExistingTestFiles())
                .newTestRequirements(requirements)
                .testTypes(testTypes)
                .testAreasTocover(testAreas)
                .priority(resolvePriority(env))
                .build();
    }

    // ─── UPDATE_TESTS handler (merged from deleted TestUpdater) ───────────────

    private void handleUpdateTests(TestStrategy strategy, ImpactEnvelope envelope) {
        log.info("[StrategyAgent] Generating update scenarios for {} test files in PR '{}'",
                strategy.getTestsToUpdate().size(), envelope.getPrId());

        List<BddScenario.Scenario> scenarios = strategy.getTestsToUpdate().stream()
                .map(testFile -> buildUpdateScenario(testFile, envelope))
                .collect(Collectors.toList());

        BddScenario bddScenario = BddScenario.builder()
                .scenarioId(UUID.randomUUID().toString())
                .featureTitle("Test updates for PR: " + envelope.getPrId())
                .featureDescription("Updating existing tests: " + envelope.getChangesSummary())
                .scenarios(scenarios)
                .prId(envelope.getPrId())
                .strategyId(strategy.getStrategyId())
                .bddType(BddScenario.BddType.UPDATED)
                .build();

        testPrService.createBddPr(bddScenario);
        log.info("[StrategyAgent] BDD update PR created ({} scenarios)", scenarios.size());
    }

    private BddScenario.Scenario buildUpdateScenario(String testFile, ImpactEnvelope env) {
        String name = testFile.contains("/") ? testFile.substring(testFile.lastIndexOf('/') + 1) : testFile;
        return BddScenario.Scenario.builder()
                .scenarioId(UUID.randomUUID().toString())
                .title("Update: " + name)
                .type("Scenario")
                .tags(List.of("@update", "@auto-generated", "@pr-" + env.getPrId()))
                .givenSteps(List.of("the existing test " + name + " is loaded", "the PR changes are reflected"))
                .whenSteps(List.of("the updated component behaviour is exercised"))
                .thenSteps(List.of("existing assertions still hold", "new behaviour is validated"))
                .andSteps(List.of())
                .testType("API")
                .build();
    }

    private void handleSkip(TestStrategy strategy) {
        log.info("[StrategyAgent] SKIP for PR '{}' — {}", strategy.getPrId(), strategy.getReasoning());
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private String resolveTestType(ImpactEnvelope.ImpactedComponent comp) {
        return switch (comp.getType()) {
            case CONTROLLER, SERVICE, REPOSITORY -> "API";
            default -> "UI";
        };
    }

    private List<String> generateScenarioHints(ImpactEnvelope.ImpactedComponent comp,
                                               ImpactEnvelope env) {
        List<String> hints = new ArrayList<>();
        hints.add("Verify happy path for " + comp.getComponentName());
        hints.add("Verify error handling for " + comp.getComponentName());
        if (env.getDetectedChangeTypes().contains(ChangeType.API_CHANGE)) {
            hints.add("Validate request/response contract");
            hints.add("Test boundary conditions");
        }
        if (env.getDetectedChangeTypes().contains(ChangeType.SECURITY_FIX)) {
            hints.add("Test authentication enforcement");
            hints.add("Test authorization rules");
        }
        if (env.getDetectedChangeTypes().contains(ChangeType.DATABASE_CHANGE)) {
            hints.add("Verify data persistence");
            hints.add("Test rollback behaviour");
        }
        return hints;
    }

    private String buildReasoning(ImpactEnvelope env, CoverageReport cov,
                                  StrategyDecision decision) {
        return String.format(
                "Decision=%s risk=%s (%.2f) changeTypes=%s filesChanged=%d " +
                "coverage=%s source=%s covered=%d uncovered=%s",
                decision, env.getRiskLevel(), env.getOverallRiskScore(),
                env.getDetectedChangeTypes(), env.getTotalFilesChanged(),
                cov.getLevel(), cov.getSource(),
                cov.getTestedComponents() != null ? cov.getTestedComponents().size() : 0,
                cov.getUntestedComponents());
    }

    private double computeConfidence(ImpactEnvelope env) {
        double base = Math.min(1.0, env.getTotalFilesChanged() / 10.0);
        return Math.min(1.0, (base + env.getOverallRiskScore()) / 2.0);
    }

    private String resolvePriority(ImpactEnvelope env) {
        return switch (env.getRiskLevel()) {
            case CRITICAL -> "P0";
            case HIGH     -> "P1";
            case MEDIUM   -> "P2";
            case LOW      -> "P3";
        };
    }
}



