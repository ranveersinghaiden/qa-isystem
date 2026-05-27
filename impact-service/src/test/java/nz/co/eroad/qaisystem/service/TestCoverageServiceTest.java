package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.model.CoverageReport;
import nz.co.eroad.qaisystem.model.CoverageReport.CoverageLevel;
import nz.co.eroad.qaisystem.model.CoverageReport.CoverageSource;
import nz.co.eroad.qaisystem.model.GitDiff;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent.ComponentType;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TestCoverageService.
 *
 * The service now identifies WHICH components need integration/E2E testing based on
 * component type — it does NOT assess whether tests exist (that's E2ECoverageAnalyzer's job).
 * Level is always UNKNOWN; untestedComponents lists components requiring integration tests.
 */
@DisplayName("TestCoverageService unit tests")
class TestCoverageServiceTest {
    TestCoverageService service;
    @BeforeEach void setUp() { service = new TestCoverageService(); }

    @Test
    @DisplayName("level is always UNKNOWN — real coverage determined by E2ECoverageAnalyzer in strategy-service")
    void level_alwaysUnknown() {
        CoverageReport r = service.assess(List.of(svc("AuthService")), List.of(src("AuthService.java")));
        assertThat(r.getLevel()).isEqualTo(CoverageLevel.UNKNOWN);
        assertThat(r.getSource()).isEqualTo(CoverageSource.UNKNOWN);
    }

    @Test
    @DisplayName("SERVICE component appears in untestedComponents — it requires integration testing")
    void serviceComponent_inUntested() {
        CoverageReport r = service.assess(List.of(svc("AuthService")), List.of(src("AuthService.java")));
        assertThat(r.getUntestedComponents()).contains("AuthService");
        assertThat(r.isRequiresNewTests()).isTrue();
    }

    @Test
    @DisplayName("CONTROLLER component appears in untestedComponents and adds API to requiredTestTypes")
    void controllerComponent_apiTestRequired() {
        CoverageReport r = service.assess(List.of(ctrl("PaymentController")), List.of(src("PaymentController.java")));
        assertThat(r.getUntestedComponents()).contains("PaymentController");
        assertThat(r.getRequiredTestTypes()).contains("API");
    }

    @Test
    @DisplayName("REPOSITORY component adds INTEGRATION to requiredTestTypes")
    void repositoryComponent_integrationTestRequired() {
        CoverageReport r = service.assess(List.of(repo("OrderRepository")), List.of(src("OrderRepository.java")));
        assertThat(r.getUntestedComponents()).contains("OrderRepository");
        assertThat(r.getRequiredTestTypes()).contains("INTEGRATION");
    }

    @Test
    @DisplayName("TEST component is excluded from untestedComponents — no integration test needed")
    void testComponent_excluded() {
        CoverageReport r = service.assess(List.of(tcomp("FooTest")), List.of(src("FooTest.java")));
        assertThat(r.getUntestedComponents()).doesNotContain("FooTest");
    }

    @Test
    @DisplayName("MODEL component is excluded from untestedComponents — no integration test needed")
    void modelComponent_excluded() {
        CoverageReport r = service.assess(List.of(model("OrderDto")), List.of(src("OrderDto.java")));
        assertThat(r.getUntestedComponents()).doesNotContain("OrderDto");
    }

    @Test
    @DisplayName("empty diffs and no components — requiresNewTests false, level UNKNOWN")
    void empty_noTests() {
        CoverageReport r = service.assess(List.of(), List.of());
        assertThat(r.getLevel()).isEqualTo(CoverageLevel.UNKNOWN);
        assertThat(r.isRequiresNewTests()).isFalse();
        assertThat(r.getUntestedComponents()).isEmpty();
    }

    @Test
    @DisplayName("security keyword in diff content adds E2E to requiredTestTypes")
    void securityDiff_addsE2eType() {
        GitDiff secDiff = GitDiff.builder()
                .filePath("src/main/SecurityConfig.java")
                .isTestFile(false)
                .linesAdded(3).linesDeleted(0)
                .hunks(List.of(GitDiff.DiffHunk.builder()
                        .lines(List.of(
                                GitDiff.DiffLine.builder().content("  @PreAuthorize(\"hasRole('ADMIN')\")").build()))
                        .build()))
                .build();
        CoverageReport r = service.assess(List.of(svc("SecurityConfig")), List.of(secDiff));
        assertThat(r.getRequiredTestTypes()).contains("E2E");
    }

    @Test
    @DisplayName("testedComponents is empty — real tested list comes from E2ECoverageAnalyzer")
    void testedComponents_alwaysEmpty() {
        CoverageReport r = service.assess(
                List.of(svc("AuthService")),
                List.of(src("AuthService.java"), tst("AuthServiceIT.java")));
        // Test files in the PR diff are irrelevant — we don't check the repo here
        assertThat(r.getTestedComponents()).isEmpty();
    }

    // ─── Builders ─────────────────────────────────────────────────────────────

    private GitDiff src(String n) {
        return GitDiff.builder().filePath("src/main/" + n).isTestFile(false)
                .linesAdded(5).linesDeleted(0).hunks(List.of()).build();
    }
    private GitDiff tst(String n) {
        return GitDiff.builder().filePath("src/test/" + n).isTestFile(true)
                .linesAdded(3).linesDeleted(0).hunks(List.of()).build();
    }
    private ImpactedComponent svc(String n) {
        return ImpactedComponent.builder().componentName(n).type(ComponentType.SERVICE).callers(List.of()).build();
    }
    private ImpactedComponent ctrl(String n) {
        return ImpactedComponent.builder().componentName(n).type(ComponentType.CONTROLLER).callers(List.of()).build();
    }
    private ImpactedComponent repo(String n) {
        return ImpactedComponent.builder().componentName(n).type(ComponentType.REPOSITORY).callers(List.of()).build();
    }
    private ImpactedComponent model(String n) {
        return ImpactedComponent.builder().componentName(n).type(ComponentType.MODEL).callers(List.of()).build();
    }
    private ImpactedComponent tcomp(String n) {
        return ImpactedComponent.builder().componentName(n).type(ComponentType.TEST).callers(List.of()).build();
    }
}
