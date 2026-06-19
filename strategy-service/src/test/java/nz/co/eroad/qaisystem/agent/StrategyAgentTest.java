package nz.co.eroad.qaisystem.agent;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import nz.co.eroad.qaisystem.gate.AiCallGate;
import nz.co.eroad.qaisystem.model.*;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ChangeType;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.RiskLevel;
import nz.co.eroad.qaisystem.model.TestStrategy.StrategyDecision;
import nz.co.eroad.qaisystem.monitor.AiCostMonitor;
import nz.co.eroad.qaisystem.service.E2ECoverageAnalyzer;
import nz.co.eroad.qaisystem.service.TestPrService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests StrategyAgent decision logic using real test-double classes (no Mockito).
 */
@DisplayName("StrategyAgent decision logic tests")
class StrategyAgentTest {

    // ── Test doubles (real Java classes, no Mockito) ──────────────────────────

    /** Records whether generate() was called. */
    static class CapturingBddGenerator extends BddGenerator {
        boolean generateCalled = false;
        CapturingBddGenerator() { super(null, null, null, null, null, null, null); }
        @Override public BddScenario generate(TestStrategy s, ImpactEnvelope e) {
            generateCalled = true;
            return BddScenario.builder().scenarioId("SC-test").prId(s.getPrId())
                    .featureTitle("Test Feature").featureDescription("Test")
                    .scenarios(List.of()).build();
        }
    }

    /** No-op TestPrService — prevents real GitHub API calls in tests. */
    static class SilentTestPrService extends TestPrService {
        SilentTestPrService() { super(null, null, null); }
        @Override public String createBddPr(BddScenario s) { return "https://github.com/test/pr/1"; }
        @Override public String createFinalTestPr(TestScript s, TestResult r) { return "https://github.com/test/pr/2"; }
    }

    /** Returns a fixed CoverageReport regardless of input. */
    static class FixedCoverageAnalyzer extends E2ECoverageAnalyzer {
        private CoverageReport report;
        FixedCoverageAnalyzer(CoverageReport r) { super(null); this.report = r; }
        void setReport(CoverageReport r) { this.report = r; }
        @Override public CoverageReport analyze(ImpactEnvelope e) { return report; }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private CapturingBddGenerator bddGen;
    private SilentTestPrService   testPr;
    private FixedCoverageAnalyzer coverageAnalyzer;
    private AiCallGate            gate;
    private AiCostMonitor         monitor;
    private StrategyAgent         agent;

    private static final CoverageReport UNKNOWN = CoverageReport.builder()
            .level(CoverageReport.CoverageLevel.UNKNOWN)
            .source(CoverageReport.CoverageSource.UNKNOWN)
            .untestedComponents(List.of("AuthService"))
            .testedComponents(List.of())
            .requiredTestTypes(List.of("API"))
            .requiresNewTests(true)
            .build();

    @BeforeEach
    void setUp() {
        bddGen           = new CapturingBddGenerator();
        testPr           = new SilentTestPrService();
        coverageAnalyzer = new FixedCoverageAnalyzer(UNKNOWN);
        gate             = new AiCallGate();
        monitor          = new AiCostMonitor(new SimpleMeterRegistry());
        monitor.initMetrics();
        agent            = new StrategyAgent(bddGen, testPr, coverageAnalyzer, gate, monitor);
    }

    // ── Core decision tests ───────────────────────────────────────────────────

    @Test
    @DisplayName("NEW_FEATURE + MEDIUM risk -> CREATE_TESTS and invokes bddGenerator")
    void newFeature_createsTests() {
        TestStrategy strategy = agent.decide(envelope(RiskLevel.MEDIUM, 0.55,
                List.of(ChangeType.NEW_FEATURE), null, 3, 0));
        assertThat(strategy.getDecision()).isEqualTo(StrategyDecision.CREATE_TESTS);
        assertThat(bddGen.generateCalled).isTrue();
    }

    @Test
    @DisplayName("only CONFIGURATION_CHANGE + LOW risk -> SKIP")
    void infraLow_skips() {
        coverageAnalyzer.setReport(CoverageReport.builder()
                .level(CoverageReport.CoverageLevel.GOOD)
                .source(CoverageReport.CoverageSource.REPO_SCAN)
                .untestedComponents(List.of()).testedComponents(List.of())
                .requiredTestTypes(List.of()).requiresNewTests(false).build());
        TestStrategy strategy = agent.decide(envelope(RiskLevel.LOW, 0.2,
                List.of(ChangeType.CONFIGURATION_CHANGE), null, 2, 0));
        assertThat(strategy.getDecision()).isEqualTo(StrategyDecision.SKIP);
        assertThat(bddGen.generateCalled).isFalse();
    }

    @Test
    @DisplayName("all changed files are test files -> SKIP")
    void allTestFiles_skips() {
        TestStrategy strategy = agent.decide(envelope(RiskLevel.MEDIUM, 0.5,
                List.of(ChangeType.NEW_FEATURE), null, 2, 2));
        assertThat(strategy.getDecision()).isEqualTo(StrategyDecision.SKIP);
    }

    @Test
    @DisplayName("E2E coverage NONE forces CREATE_TESTS regardless of change type")
    void coverageNone_forcesCreate() {
        coverageAnalyzer.setReport(CoverageReport.builder()
                .level(CoverageReport.CoverageLevel.NONE)
                .source(CoverageReport.CoverageSource.REPO_SCAN)
                .untestedComponents(List.of("AuthService")).testedComponents(List.of())
                .requiredTestTypes(List.of("API")).requiresNewTests(true).build());
        TestStrategy strategy = agent.decide(envelope(RiskLevel.LOW, 0.15,
                List.of(ChangeType.REFACTORING), null, 2, 0));
        assertThat(strategy.getDecision()).isEqualTo(StrategyDecision.CREATE_TESTS);
    }

    @Test
    @DisplayName("PARTIAL E2E coverage -> UPDATE_TESTS")
    void coveragePartial_updatesTests() {
        coverageAnalyzer.setReport(CoverageReport.builder()
                .level(CoverageReport.CoverageLevel.PARTIAL)
                .source(CoverageReport.CoverageSource.REPO_SCAN)
                .testedComponents(List.of("AuthService"))
                .untestedComponents(List.of("PaymentService"))
                .requiredTestTypes(List.of("API", "INTEGRATION")).requiresNewTests(true).build());
        TestStrategy strategy = agent.decide(envelope(RiskLevel.MEDIUM, 0.5,
                List.of(ChangeType.BUG_FIX), null, 3, 0));
        assertThat(strategy.getDecision()).isEqualTo(StrategyDecision.UPDATE_TESTS);
    }

    @Test
    @DisplayName("GOOD coverage + NEW_FEATURE still creates tests")
    void goodCoverage_newFeature_stillCreates() {
        coverageAnalyzer.setReport(CoverageReport.builder()
                .level(CoverageReport.CoverageLevel.GOOD)
                .source(CoverageReport.CoverageSource.REPO_SCAN)
                .testedComponents(List.of("AuthService")).untestedComponents(List.of())
                .requiredTestTypes(List.of("API")).requiresNewTests(false).build());
        TestStrategy strategy = agent.decide(envelope(RiskLevel.MEDIUM, 0.55,
                List.of(ChangeType.NEW_FEATURE), null, 3, 0));
        assertThat(strategy.getDecision()).isEqualTo(StrategyDecision.CREATE_TESTS);
    }

    @Test
    @DisplayName("confidence < 0.4 sets fullRegressionRequired = true")
    void lowScore_fullRegression() {
        TestStrategy strategy = agent.decide(envelope(RiskLevel.LOW, 0.1,
                List.of(ChangeType.DEPENDENCY_UPDATE), null, 2, 0));
        assertThat(strategy.isFullRegressionRequired()).isTrue();
    }

    @Test
    @DisplayName("CRITICAL risk sets expandedScope = true")
    void criticalRisk_expandedScope() {
        TestStrategy strategy = agent.decide(envelope(RiskLevel.CRITICAL, 0.95,
                List.of(ChangeType.BREAKING_CHANGE), null, 3, 0));
        assertThat(strategy.isExpandedScope()).isTrue();
    }

    @Test
    @DisplayName("returned strategy contains prId from envelope")
    void strategyHasPrId() {
        TestStrategy strategy = agent.decide(envelope(RiskLevel.MEDIUM, 0.55,
                List.of(ChangeType.NEW_FEATURE), null, 3, 0));
        assertThat(strategy.getPrId()).isEqualTo("PR-T");
    }

    @Test
    @DisplayName("testAreasTocover uses untestedComponents from E2E analysis")
    void testAreas_fromE2eAnalysis() {
        coverageAnalyzer.setReport(CoverageReport.builder()
                .level(CoverageReport.CoverageLevel.NONE)
                .source(CoverageReport.CoverageSource.REPO_SCAN)
                .untestedComponents(List.of("NewPaymentController")).testedComponents(List.of())
                .requiredTestTypes(List.of("API")).requiresNewTests(true).build());
        TestStrategy strategy = agent.decide(envelope(RiskLevel.MEDIUM, 0.55,
                List.of(ChangeType.NEW_FEATURE), null, 3, 0));
        assertThat(strategy.getTestAreasTocover()).contains("NewPaymentController");
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private ImpactEnvelope envelope(RiskLevel risk, double score,
                                     List<ChangeType> types,
                                     CoverageReport coverage,
                                     int totalFiles, int testFiles) {
        return ImpactEnvelope.builder()
                .prId("PR-T").riskLevel(risk).overallRiskScore(score)
                .totalFilesChanged(totalFiles).affectedTestFiles(testFiles)
                .detectedChangeTypes(types).coverageReport(coverage)
                .impactedComponents(List.of(svc("AuthService")))
                .suggestedTestAreas(List.of("AuthService"))
                .directDependencies(List.of()).transitiveDependencies(List.of())
                .existingTestFiles(List.of()).impactedModules(List.of("auth"))
                .build();
    }

    private ImpactedComponent svc(String name) {
        return ImpactedComponent.builder()
                .componentName(name).type(ImpactedComponent.ComponentType.SERVICE)
                .callers(List.of()).callees(List.of()).build();
    }
}
