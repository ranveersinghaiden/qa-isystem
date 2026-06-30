package nz.co.eroad.qaisystem.oneshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.execution.CodegenService;
import nz.co.eroad.qaisystem.github.GitHubService;
import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScriptRequest;
import nz.co.eroad.qaisystem.service.TestPrService;
import nz.co.eroad.qaisystem.state.GateState;
import nz.co.eroad.qaisystem.state.InMemoryStateStore;
import nz.co.eroad.qaisystem.state.PrHistory;
import nz.co.eroad.qaisystem.state.ScenarioState;
import nz.co.eroad.qaisystem.state.StateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routing tests for {@link CodegenOneShotRunner} with a real {@link InMemoryStateStore} and
 * capturing {@link CodegenService} / {@link TestPrService} doubles (no Mockito). Confirms
 * {@code oneshot} runs the one-shot {@code generateAndExecuteOne} overload with {@code openTestPr=false},
 * threads {@code --target-dir}, records a per-scenario {@code test_path}, that a {@code null} return is
 * an idempotent skip, and that {@code gather} opens ONE aggregate test PR, records
 * {@code tests_pr_number}, and advances the gate.
 */
@DisplayName("CodegenOneShotRunner routing")
class CodegenOneShotRunnerTest {

    /** Captures the one-shot codegen call args; returns a canned {@link TestResult} (null = duplicate claim). */
    static class CapturingCodegenService extends CodegenService {
        private final TestResult canned;
        TestScriptRequest seen;
        Boolean seenOpenTestPr;
        String seenTargetDir;
        CapturingCodegenService(TestResult canned) {
            super(null, null, null, null);
            this.canned = canned;
        }
        @Override
        public TestResult generateAndExecuteOne(TestScriptRequest req, boolean openTestPr, String targetDir) {
            this.seen = req;
            this.seenOpenTestPr = openTestPr;
            this.seenTargetDir = targetDir;
            return canned;
        }
    }

    /** Records {@code createAggregateTestPr} calls; returns a canned {@link GitHubService.GitHubPrResult}. */
    static class CapturingTestPrService extends TestPrService {
        int calls;
        String seenPrId;
        String seenPrTitle;
        List<TestPrService.AggregateTestFile> seenFiles;
        private final GitHubService.GitHubPrResult result;
        CapturingTestPrService(GitHubService.GitHubPrResult result) {
            super(null, null, null);
            this.result = result;
        }
        @Override
        public GitHubService.GitHubPrResult createAggregateTestPr(String prId, String prTitle,
                                                                  List<TestPrService.AggregateTestFile> files) {
            this.calls++;
            this.seenPrId = prId;
            this.seenPrTitle = prTitle;
            this.seenFiles = files;
            return result;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final CapturingTestPrService prSvc =
            new CapturingTestPrService(new GitHubService.GitHubPrResult(42, "http://pr/42", "qa/tests/PR-1-abc123"));

    private StateStore storeWithPlannedScenario(String prId, String scenarioId) throws Exception {
        StateStore store = new InMemoryStateStore();
        String reqJson = mapper.writeValueAsString(
                TestScriptRequest.builder().prId(prId).scenarioId(scenarioId).build());
        store.upsertScenario(ScenarioState.builder()
                .prId(prId).scenarioId(scenarioId).status(ScenarioState.PLANNED).attempts(0)
                .scenario(reqJson).build());
        return store;
    }

    /** Seeds a PLANNED scenario with a faithful stored result (finalScriptContent + testPath). */
    private void seedResult(StateStore store, String prId, String scenarioId, String prTitle,
                            String scriptContent, String testPath) throws Exception {
        store.upsertScenario(ScenarioState.builder()
                .prId(prId).scenarioId(scenarioId).status(ScenarioState.PLANNED)
                .scenario(mapper.writeValueAsString(
                        TestScriptRequest.builder().prId(prId).prTitle(prTitle).scenarioId(scenarioId).build()))
                .build());
        String resultJson = mapper.writeValueAsString(TestResult.builder()
                .prId(prId).passed(true).finalScriptContent(scriptContent).testPath(testPath).build());
        store.updateScenarioResult(prId, scenarioId, ScenarioState.PASSED, testPath, resultJson);
    }

    @Test
    @DisplayName("oneshot generates a passing test and records PASSED + result")
    void oneshot_recordsPassed() throws Exception {
        StateStore store = storeWithPlannedScenario("PR-1", "S1");
        CapturingCodegenService codegen = new CapturingCodegenService(
                TestResult.builder().prId("PR-1").passed(true).build());
        CodegenOneShotRunner runner = new CodegenOneShotRunner(codegen, store, mapper, prSvc);

        int code = runner.execute(new String[]{"--mode", "oneshot", "--pr-id", "PR-1", "--scenario", "S1"});

        assertThat(code).isZero();
        assertThat(codegen.seen).isNotNull();
        assertThat(codegen.seen.getScenarioId()).isEqualTo("S1");
        ScenarioState row = store.scenariosFor("PR-1").get(0);
        assertThat(row.getStatus()).isEqualTo(ScenarioState.PASSED);
        assertThat(row.getResult()).isNotBlank();
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("oneshot threads --target-dir (openTestPr=false) and records a non-null test_path")
    void oneshot_threadsTargetDir_recordsTestPath() throws Exception {
        StateStore store = storeWithPlannedScenario("PR-1", "S1");
        CapturingCodegenService codegen = new CapturingCodegenService(
                TestResult.builder().prId("PR-1").passed(true)
                        .finalScriptContent("class FooApiTest {}")
                        .testPath("custom/dir/FooApiTest.java").build());
        CodegenOneShotRunner runner = new CodegenOneShotRunner(codegen, store, mapper, prSvc);

        int code = runner.execute(new String[]{
                "--mode", "oneshot", "--pr-id", "PR-1", "--scenario", "S1", "--target-dir", "custom/dir"});

        assertThat(code).isZero();
        // one-shot calls the overload with openTestPr=false and threads the target dir
        assertThat(codegen.seenOpenTestPr).isFalse();
        assertThat(codegen.seenTargetDir).isEqualTo("custom/dir");
        // the surfaced testPath is persisted and the stored result JSON carries it for feedback
        ScenarioState row = store.scenariosFor("PR-1").get(0);
        assertThat(row.getTestPath()).isEqualTo("custom/dir/FooApiTest.java");
        assertThat(row.getResult()).contains("custom/dir/FooApiTest.java");
    }

    @Test
    @DisplayName("oneshot records FAILED when the generated test fails")
    void oneshot_recordsFailed() throws Exception {
        StateStore store = storeWithPlannedScenario("PR-1", "S1");
        CodegenOneShotRunner runner = new CodegenOneShotRunner(
                new CapturingCodegenService(TestResult.builder().prId("PR-1").passed(false).build()),
                store, mapper, prSvc);

        assertThat(runner.execute(new String[]{"--mode", "oneshot", "--pr-id", "PR-1", "--scenario", "S1"})).isZero();
        assertThat(store.scenariosFor("PR-1").get(0).getStatus()).isEqualTo(ScenarioState.FAILED);
    }

    @Test
    @DisplayName("oneshot null result is an idempotent skip (exit 0, scenario left planned)")
    void oneshot_nullResult_skips() throws Exception {
        StateStore store = storeWithPlannedScenario("PR-1", "S1");
        CodegenOneShotRunner runner = new CodegenOneShotRunner(
                new CapturingCodegenService(null), store, mapper, prSvc);

        assertThat(runner.execute(new String[]{"--mode", "oneshot", "--pr-id", "PR-1", "--scenario", "S1"})).isZero();
        // status untouched — still PLANNED, attempts not incremented
        ScenarioState row = store.scenariosFor("PR-1").get(0);
        assertThat(row.getStatus()).isEqualTo(ScenarioState.PLANNED);
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    @DisplayName("oneshot without --scenario exits 2")
    void oneshot_missingScenario_exits2() throws Exception {
        StateStore store = storeWithPlannedScenario("PR-1", "S1");
        CodegenOneShotRunner runner = new CodegenOneShotRunner(
                new CapturingCodegenService(TestResult.builder().passed(true).build()), store, mapper, prSvc);
        assertThat(runner.execute(new String[]{"--mode", "oneshot", "--pr-id", "PR-1"})).isEqualTo(2);
    }

    @Test
    @DisplayName("oneshot for an unknown scenario exits 2")
    void oneshot_unknownScenario_exits2() throws Exception {
        StateStore store = storeWithPlannedScenario("PR-1", "S1");
        CodegenOneShotRunner runner = new CodegenOneShotRunner(
                new CapturingCodegenService(TestResult.builder().passed(true).build()), store, mapper, prSvc);
        assertThat(runner.execute(new String[]{"--mode", "oneshot", "--pr-id", "PR-1", "--scenario", "SX"})).isEqualTo(2);
    }

    @Test
    @DisplayName("gather opens ONE aggregate test PR, records tests_pr_number, advances the gate")
    void gather_opensAggregatePr() throws Exception {
        StateStore store = new InMemoryStateStore();
        store.savePr(PrHistory.builder().prId("PR-1").repo("r").owner("o").headSha("s").build());
        seedResult(store, "PR-1", "S1", "Title One", "class A {}", "src/test/java/p/AApiTest.java");
        seedResult(store, "PR-1", "S2", "Title One", "class B {}", "src/test/java/p/BApiTest.java");
        CodegenOneShotRunner runner = new CodegenOneShotRunner(
                new CapturingCodegenService(null), store, mapper, prSvc);

        int code = runner.execute(new String[]{"--mode", "gather", "--pr-id", "PR-1"});

        assertThat(code).isZero();
        assertThat(prSvc.calls).isEqualTo(1);
        assertThat(prSvc.seenPrId).isEqualTo("PR-1");
        assertThat(prSvc.seenPrTitle).isEqualTo("Title One");
        assertThat(prSvc.seenFiles).hasSize(2);
        PrHistory pr = store.findPr("PR-1").orElseThrow();
        assertThat(pr.getTestsPrNumber()).isEqualTo(42);
        assertThat(pr.getGateState()).isEqualTo(GateState.AWAITING_TESTS_APPROVAL);
    }

    @Test
    @DisplayName("gather with no usable results opens NO PR but still advances the gate")
    void gather_noResults_advancesGate() {
        StateStore store = new InMemoryStateStore();
        store.savePr(PrHistory.builder().prId("PR-1").repo("r").owner("o").headSha("s").build());
        store.updateScenarioResult("PR-1", "S1", ScenarioState.PASSED, null, "{}");
        store.updateScenarioResult("PR-1", "S2", ScenarioState.FAILED, null, "{}");
        CodegenOneShotRunner runner = new CodegenOneShotRunner(
                new CapturingCodegenService(null), store, mapper, prSvc);

        int code = runner.execute(new String[]{"--mode", "gather", "--pr-id", "PR-1"});

        assertThat(code).isZero();
        assertThat(prSvc.calls).isZero();
        assertThat(store.findPr("PR-1").orElseThrow().getGateState())
                .isEqualTo(GateState.AWAITING_TESTS_APPROVAL);
    }

    @Test
    @DisplayName("missing --pr-id exits 2")
    void missingPrId_exits2() {
        CodegenOneShotRunner runner = new CodegenOneShotRunner(
                new CapturingCodegenService(null), new InMemoryStateStore(), mapper, prSvc);
        assertThat(runner.execute(new String[]{"--mode", "gather"})).isEqualTo(2);
    }

    @Test
    @DisplayName("unsupported --mode exits 2")
    void unsupportedMode_exits2() {
        CodegenOneShotRunner runner = new CodegenOneShotRunner(
                new CapturingCodegenService(null), new InMemoryStateStore(), mapper, prSvc);
        assertThat(runner.execute(new String[]{"--mode", "bogus", "--pr-id", "PR-1"})).isEqualTo(2);
    }
}
