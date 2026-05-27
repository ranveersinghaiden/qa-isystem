package nz.co.eroad.qaisystem.agent;

import nz.co.eroad.qaisystem.model.*;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ChangeType;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.RiskLevel;
import nz.co.eroad.qaisystem.model.TestStrategy.StrategyDecision;
import nz.co.eroad.qaisystem.service.TestPrService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StrategyAgent decision logic tests")
class StrategyAgentTest {

    @Mock BddGenerator  bddGenerator;
    @Mock TestPrService testPrService;
    @InjectMocks StrategyAgent agent;

    @BeforeEach void setUp() {}

    // ─── Core decision tests ─────────────────────────────────────────────────

    @Test
    @DisplayName("NEW_FEATURE + MEDIUM risk -> CREATE_TESTS and invokes bddGenerator")
    void newFeature_createsTests() {
        TestStrategy strategy = agent.decide(envelope(RiskLevel.MEDIUM, 0.55,
                List.of(ChangeType.NEW_FEATURE), null, 3, 0));
        assertThat(strategy.getDecision()).isEqualTo(StrategyDecision.CREATE_TESTS);
        verify(bddGenerator).generate(any(), any());
    }

    @Test
    @DisplayName("only CONFIGURATION_CHANGE + LOW risk -> SKIP")
    void infraLow_skips() {
        TestStrategy strategy = agent.decide(envelope(RiskLevel.LOW, 0.2,
                List.of(ChangeType.CONFIGURATION_CHANGE), null, 2, 0));
        assertThat(strategy.getDecision()).isEqualTo(StrategyDecision.SKIP);
        verifyNoInteractions(bddGenerator);
    }

    @Test
    @DisplayName("all changed files are test files -> SKIP")
    void allTestFiles_skips() {
        TestStrategy strategy = agent.decide(envelope(RiskLevel.MEDIUM, 0.5,
                List.of(ChangeType.NEW_FEATURE), null, 2, 2));
        assertThat(strategy.getDecision()).isEqualTo(StrategyDecision.SKIP);
    }

    @Test
    @DisplayName("coverage NONE forces CREATE_TESTS regardless of change type")
    void coverageNone_forcesCreate() {
        CoverageReport noCoverage = CoverageReport.builder()
                .level(CoverageReport.CoverageLevel.NONE)
                .missingCoverageAreas(List.of("AuthService")).build();
        TestStrategy strategy = agent.decide(envelope(RiskLevel.LOW, 0.15,
                List.of(ChangeType.REFACTORING), noCoverage, 2, 0));
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

    // ─── Helper ──────────────────────────────────────────────────────────────

    private ImpactEnvelope envelope(RiskLevel risk, double score,
                                     List<ChangeType> types,
                                     CoverageReport coverage,
                                     int totalFiles, int testFiles) {
        return ImpactEnvelope.builder()
                .prId("PR-T")
                .riskLevel(risk)
                .overallRiskScore(score)
                .totalFilesChanged(totalFiles)
                .affectedTestFiles(testFiles)
                .detectedChangeTypes(types)
                .coverageReport(coverage)
                .impactedComponents(List.of(svc("AuthService")))
                .suggestedTestAreas(List.of("AuthService"))
                .directDependencies(List.of())
                .transitiveDependencies(List.of())
                .impactedModules(List.of("auth"))
                .build();
    }

    private ImpactedComponent svc(String name) {
        return ImpactedComponent.builder()
                .componentName(name)
                .type(ImpactedComponent.ComponentType.SERVICE)
                .callers(List.of()).callees(List.of())
                .build();
    }
}
