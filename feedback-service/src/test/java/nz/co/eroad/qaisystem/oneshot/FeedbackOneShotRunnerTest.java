package nz.co.eroad.qaisystem.oneshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.agent.PrFeedbackService;
import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.PrRecord;
import nz.co.eroad.qaisystem.model.PrType;
import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScriptRequest;
import nz.co.eroad.qaisystem.state.GateState;
import nz.co.eroad.qaisystem.state.InMemoryStateStore;
import nz.co.eroad.qaisystem.state.PrHistory;
import nz.co.eroad.qaisystem.state.ScenarioState;
import nz.co.eroad.qaisystem.state.StateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routing tests for {@link FeedbackOneShotRunner} with a real {@link InMemoryStateStore} and a
 * capturing {@link PrFeedbackService} double (no Mockito). Confirms the one-shot pod reconstructs a
 * {@link PrRecord} from persisted state and dispatches to the SAME handler the Kafka consumer would
 * call — {@code handleBddRejection} for a BDD gate, {@code handleTestRejection} for a TEST gate.
 *
 * <p>The double overrides both handlers to capture their arguments WITHOUT dereferencing the record,
 * so no real GitHub / AI call happens.
 */
@DisplayName("FeedbackOneShotRunner routing")
class FeedbackOneShotRunnerTest {

    static class CapturingFeedbackService extends PrFeedbackService {
        PrRecord bddRecord;
        Integer bddPrNumber;
        PrRecord testRecord;
        Integer testPrNumber;
        CapturingFeedbackService() {
            super(null, null, null, null, null, null);
        }
        @Override public void handleBddRejection(PrRecord record, int prNumber) {
            this.bddRecord = record;
            this.bddPrNumber = prNumber;
        }
        @Override public void handleTestRejection(PrRecord record, int prNumber) {
            this.testRecord = record;
            this.testPrNumber = prNumber;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("BDD gate routes to handleBddRejection with the BDD PR number")
    void bddGate_routesToBddHandler() throws Exception {
        StateStore store = new InMemoryStateStore();
        store.savePr(PrHistory.builder()
                .prId("PR-1").repo("r").owner("o").headSha("s").branch("feature/x")
                .gateState(GateState.AWAITING_BDD_APPROVAL).bddPrNumber(101).build());
        // a planned scenario lets reconstructBddScenario rebuild a faithful BddScenario
        TestScriptRequest req = TestScriptRequest.builder()
                .prId("PR-1").prTitle("Add feature").strategyId("ST-1").bddScenarioId("F-1").scenarioId("S1")
                .scenario(BddScenario.Scenario.builder().scenarioId("S1").title("a").build()).build();
        store.upsertScenario(ScenarioState.builder()
                .prId("PR-1").scenarioId("S1").status(ScenarioState.PLANNED)
                .scenario(mapper.writeValueAsString(req)).build());

        CapturingFeedbackService feedback = new CapturingFeedbackService();
        FeedbackOneShotRunner runner = new FeedbackOneShotRunner(feedback, store, mapper);

        int code = runner.execute(new String[]{"--mode", "oneshot", "--pr-id", "PR-1", "--review-id", "R1"});

        assertThat(code).isZero();
        assertThat(feedback.bddPrNumber).isEqualTo(101);
        assertThat(feedback.testRecord).isNull();
        assertThat(feedback.bddRecord).isNotNull();
        assertThat(feedback.bddRecord.getType()).isEqualTo(PrType.BDD);
        assertThat(feedback.bddRecord.getBddScenario()).isNotNull();
        assertThat(feedback.bddRecord.getBddScenario().getScenarios()).hasSize(1);
    }

    @Test
    @DisplayName("TEST gate routes to handleTestRejection with the tests PR number")
    void testGate_routesToTestHandler() throws Exception {
        StateStore store = new InMemoryStateStore();
        store.savePr(PrHistory.builder()
                .prId("PR-2").repo("r").owner("o").headSha("s").branch("feature/y")
                .gateState(GateState.AWAITING_TESTS_APPROVAL).testsPrNumber(202).build());
        // a stored result lets reconstructTestScript build a representative TestScript
        store.upsertScenario(ScenarioState.builder()
                .prId("PR-2").scenarioId("S1").status(ScenarioState.PASSED)
                .scenario(mapper.writeValueAsString(TestScriptRequest.builder().prId("PR-2").prTitle("t").build()))
                .result(mapper.writeValueAsString(
                        TestResult.builder().prId("PR-2").passed(true).finalScriptContent("class T {}").build()))
                .build());

        CapturingFeedbackService feedback = new CapturingFeedbackService();
        FeedbackOneShotRunner runner = new FeedbackOneShotRunner(feedback, store, mapper);

        int code = runner.execute(new String[]{"--mode", "oneshot", "--pr-id", "PR-2", "--review-id", "R9"});

        assertThat(code).isZero();
        assertThat(feedback.testPrNumber).isEqualTo(202);
        assertThat(feedback.bddRecord).isNull();
        assertThat(feedback.testRecord).isNotNull();
        assertThat(feedback.testRecord.getType()).isEqualTo(PrType.TEST);
    }

    @Test
    @DisplayName("missing --pr-id exits 2")
    void missingPrId_exits2() {
        FeedbackOneShotRunner runner = new FeedbackOneShotRunner(
                new CapturingFeedbackService(), new InMemoryStateStore(), mapper);
        assertThat(runner.execute(new String[]{"--mode", "oneshot"})).isEqualTo(2);
    }

    @Test
    @DisplayName("no pr_history row exits 2")
    void noPrRow_exits2() {
        FeedbackOneShotRunner runner = new FeedbackOneShotRunner(
                new CapturingFeedbackService(), new InMemoryStateStore(), mapper);
        assertThat(runner.execute(new String[]{"--mode", "oneshot", "--pr-id", "ABSENT"})).isEqualTo(2);
    }

    @Test
    @DisplayName("indeterminate rejection type (no gate / PR numbers) exits 2")
    void indeterminateType_exits2() {
        StateStore store = new InMemoryStateStore();
        store.savePr(PrHistory.builder().prId("PR-3").repo("r").owner("o").headSha("s").build());
        FeedbackOneShotRunner runner = new FeedbackOneShotRunner(
                new CapturingFeedbackService(), store, mapper);
        assertThat(runner.execute(new String[]{"--mode", "oneshot", "--pr-id", "PR-3"})).isEqualTo(2);
    }

    @Test
    @DisplayName("unsupported --mode exits 2")
    void unsupportedMode_exits2() {
        FeedbackOneShotRunner runner = new FeedbackOneShotRunner(
                new CapturingFeedbackService(), new InMemoryStateStore(), mapper);
        assertThat(runner.execute(new String[]{"--mode", "bogus", "--pr-id", "PR-1"})).isEqualTo(2);
    }

    // ─── Faithful TEST reconstruction (gap 4) ───────────────────────────────────

    @Test
    @DisplayName("reconstructTestScript honors --scenario: selects that scenario's stored script")
    void testReconstruct_withScenarioSelector() throws Exception {
        StateStore store = new InMemoryStateStore();
        store.savePr(PrHistory.builder()
                .prId("PR-9").repo("r").owner("o").headSha("s").branch("feature/z")
                .gateState(GateState.AWAITING_TESTS_APPROVAL).testsPrNumber(303).build());
        seedTestScenario(store, "PR-9", "scn-1", "class One {}", "src/test/java/p/OneApiTest.java");
        seedTestScenario(store, "PR-9", "scn-2", "class Two {}", "src/test/java/p/TwoApiTest.java");

        CapturingFeedbackService feedback = new CapturingFeedbackService();
        FeedbackOneShotRunner runner = new FeedbackOneShotRunner(feedback, store, mapper);

        int code = runner.execute(new String[]{
                "--mode", "oneshot", "--pr-id", "PR-9", "--review-id", "R9", "--scenario", "scn-2"});

        assertThat(code).isZero();
        assertThat(feedback.testRecord).isNotNull();
        assertThat(feedback.testRecord.getTestScript()).isNotNull();
        // faithful: the reconstructed script content is scn-2's persisted finalScriptContent
        assertThat(feedback.testRecord.getTestScript().getScriptContent()).isEqualTo("class Two {}");
        assertThat(feedback.testRecord.getTestScript().getFileName()).isEqualTo("TwoApiTest.java");
    }

    @Test
    @DisplayName("reconstructTestScript without --scenario picks the representative (first) scenario")
    void testReconstruct_representativeWhenNoSelector() throws Exception {
        StateStore store = new InMemoryStateStore();
        store.savePr(PrHistory.builder()
                .prId("PR-9").repo("r").owner("o").headSha("s").branch("feature/z")
                .gateState(GateState.AWAITING_TESTS_APPROVAL).testsPrNumber(303).build());
        seedTestScenario(store, "PR-9", "scn-1", "class One {}", "src/test/java/p/OneApiTest.java");
        seedTestScenario(store, "PR-9", "scn-2", "class Two {}", "src/test/java/p/TwoApiTest.java");

        CapturingFeedbackService feedback = new CapturingFeedbackService();
        FeedbackOneShotRunner runner = new FeedbackOneShotRunner(feedback, store, mapper);

        int code = runner.execute(new String[]{
                "--mode", "oneshot", "--pr-id", "PR-9", "--review-id", "R9"});

        assertThat(code).isZero();
        assertThat(feedback.testRecord).isNotNull();
        assertThat(feedback.testRecord.getTestScript()).isNotNull();
        // representative = first scenario by sorted scenarioId (scn-1), faithful non-null content
        assertThat(feedback.testRecord.getTestScript().getScriptContent()).isEqualTo("class One {}");
    }

    /** Seeds a PASSED scenario with a faithful stored TestResult (finalScriptContent + testPath). */
    private void seedTestScenario(StateStore store, String prId, String scenarioId,
                                  String scriptContent, String testPath) throws Exception {
        TestScriptRequest req = TestScriptRequest.builder()
                .prId(prId).prTitle("Title").scenarioId(scenarioId)
                .scenario(BddScenario.Scenario.builder()
                        .scenarioId(scenarioId).title("t").testType("API").build())
                .build();
        store.upsertScenario(ScenarioState.builder()
                .prId(prId).scenarioId(scenarioId).status(ScenarioState.PASSED)
                .scenario(mapper.writeValueAsString(req))
                .build());
        String resultJson = mapper.writeValueAsString(TestResult.builder()
                .prId(prId).passed(true).finalScriptContent(scriptContent).testPath(testPath).build());
        store.updateScenarioResult(prId, scenarioId, ScenarioState.PASSED, testPath, resultJson);
    }
}
