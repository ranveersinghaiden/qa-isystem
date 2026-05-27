package nz.co.eroad.qaisystem.engine;

import nz.co.eroad.qaisystem.model.GitDiff;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ChangeType;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.RiskLevel;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RiskScorer unit tests")
class RiskScorerTest {
    RiskScorer scorer;

    @BeforeEach void setUp() {
        scorer = new RiskScorer();
        ReflectionTestUtils.setField(scorer, "thresholdHigh", 0.7);
        ReflectionTestUtils.setField(scorer, "thresholdMedium", 0.4);
    }

    @Test @DisplayName("0.95 -> CRITICAL") void critical() { assertThat(scorer.toLevel(0.95)).isEqualTo(RiskLevel.CRITICAL); }
    @Test @DisplayName("0.75 -> HIGH")     void high()     { assertThat(scorer.toLevel(0.75)).isEqualTo(RiskLevel.HIGH); }
    @Test @DisplayName("0.55 -> MEDIUM")   void medium()   { assertThat(scorer.toLevel(0.55)).isEqualTo(RiskLevel.MEDIUM); }
    @Test @DisplayName("0.1 -> LOW")       void low()      { assertThat(scorer.toLevel(0.1)).isEqualTo(RiskLevel.LOW); }
    @Test @DisplayName("0.9 boundary -> CRITICAL") void boundary09() { assertThat(scorer.toLevel(0.9)).isEqualTo(RiskLevel.CRITICAL); }
    @Test @DisplayName("0.7 boundary -> HIGH")     void boundary07() { assertThat(scorer.toLevel(0.7)).isEqualTo(RiskLevel.HIGH); }

    @Test @DisplayName("score is normalised 0-1")
    void score_normalised() {
        double r = scorer.score(List.of(src(500,200)), List.of(ChangeType.BREAKING_CHANGE), List.of(ctrl()));
        assertThat(r).isBetween(0.0, 1.0);
    }

    @Test @DisplayName("BREAKING_CHANGE > REFACTORING")
    void score_breakingHigher() {
        List<GitDiff> d = List.of(src(50,10));
        List<ImpactedComponent> c = List.of(svc());
        assertThat(scorer.score(d, List.of(ChangeType.BREAKING_CHANGE), c))
            .isGreaterThan(scorer.score(d, List.of(ChangeType.REFACTORING), c));
    }

    @Test @DisplayName("test files lower risk score")
    void score_testFilesLower() {
        List<ChangeType> t = List.of(ChangeType.NEW_FEATURE);
        List<ImpactedComponent> c = List.of(svc());
        assertThat(scorer.score(List.of(src(40,0),tst(30,0)), t, c))
            .isLessThan(scorer.score(List.of(src(40,0)), t, c));
    }

    @Test @DisplayName("CONTROLLER > MODEL")
    void score_ctrlHigherThanModel() {
        List<GitDiff> d = List.of(src(50,5));
        List<ChangeType> t = List.of(ChangeType.API_CHANGE);
        assertThat(scorer.score(d, t, List.of(ctrl())))
            .isGreaterThan(scorer.score(d, t, List.of(mdl())));
    }

    @Test @DisplayName("empty inputs produce valid score")
    void score_empty() { assertThat(scorer.score(List.of(), List.of(), List.of())).isBetween(0.0, 1.0); }

    private GitDiff src(int a, int d) { return GitDiff.builder().filePath("src/F.java").isTestFile(false).linesAdded(a).linesDeleted(d).build(); }
    private GitDiff tst(int a, int d) { return GitDiff.builder().filePath("src/FTest.java").isTestFile(true).linesAdded(a).linesDeleted(d).build(); }
    private ImpactedComponent ctrl() { return ImpactedComponent.builder().componentName("C").type(ImpactedComponent.ComponentType.CONTROLLER).callers(List.of()).build(); }
    private ImpactedComponent svc()  { return ImpactedComponent.builder().componentName("S").type(ImpactedComponent.ComponentType.SERVICE).callers(List.of()).build(); }
    private ImpactedComponent mdl()  { return ImpactedComponent.builder().componentName("M").type(ImpactedComponent.ComponentType.MODEL).callers(List.of()).build(); }
}
