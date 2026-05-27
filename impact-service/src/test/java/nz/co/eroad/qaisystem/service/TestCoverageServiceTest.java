package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.model.CoverageReport;
import nz.co.eroad.qaisystem.model.CoverageReport.CoverageLevel;
import nz.co.eroad.qaisystem.model.GitDiff;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TestCoverageService unit tests")
class TestCoverageServiceTest {
    TestCoverageService service;
    @BeforeEach void setUp() { service = new TestCoverageService(); }

    @Test @DisplayName("NONE when no test files")
    void none_noTests() {
        CoverageReport r = service.assess(List.of(svc("AuthService")), List.of(src("AuthService.java")));
        assertThat(r.getLevel()).isEqualTo(CoverageLevel.NONE);
        assertThat(r.isRequiresNewTests()).isTrue();
        assertThat(r.getMissingCoverageAreas()).contains("AuthService");
    }

    @Test @DisplayName("GOOD when ratio >= 0.8")
    void good_coverage() {
        CoverageReport r = service.assess(List.of(svc("AuthService")),
            List.of(src("AuthService.java"), tst("AuthServiceTest.java")));
        assertThat(r.getLevel()).isEqualTo(CoverageLevel.GOOD);
        assertThat(r.isRequiresNewTests()).isFalse();
    }

    @Test @DisplayName("matching test not in missing areas")
    void matchingTest_notMissing() {
        CoverageReport r = service.assess(List.of(svc("UserService")),
            List.of(src("UserService.java"), tst("UserServiceTest.java")));
        assertThat(r.getMissingCoverageAreas()).doesNotContain("UserService");
    }

    @Test @DisplayName("TEST components excluded from missing")
    void testComp_excluded() {
        CoverageReport r = service.assess(List.of(tcomp("FooTest")), List.of(src("FooTest.java")));
        assertThat(r.getMissingCoverageAreas()).doesNotContain("FooTest");
    }

    @Test @DisplayName("coverage ratio is correct (1 test / 2 src = 0.5)")
    void ratio() {
        CoverageReport r = service.assess(List.of(),
            List.of(src("A.java"), src("B.java"), tst("ATest.java")));
        assertThat(r.getCoverageRatio()).isEqualTo(0.5);
    }

    @Test @DisplayName("empty diffs gives zero ratio")
    void empty() {
        CoverageReport r = service.assess(List.of(), List.of());
        assertThat(r.getCoverageRatio()).isEqualTo(0.0);
        assertThat(r.getLevel()).isEqualTo(CoverageLevel.NONE);
    }

    private GitDiff src(String n) { return GitDiff.builder().filePath("src/main/"+n).isTestFile(false).linesAdded(5).linesDeleted(0).build(); }
    private GitDiff tst(String n) { return GitDiff.builder().filePath("src/test/"+n).isTestFile(true).linesAdded(3).linesDeleted(0).build(); }
    private ImpactedComponent svc(String n) { return ImpactedComponent.builder().componentName(n).type(ImpactedComponent.ComponentType.SERVICE).callers(List.of()).build(); }
    private ImpactedComponent tcomp(String n) { return ImpactedComponent.builder().componentName(n).type(ImpactedComponent.ComponentType.TEST).callers(List.of()).build(); }
}
