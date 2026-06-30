package nz.co.eroad.qaisystem.oneshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.agent.StrategyAgent;
import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.RiskLevel;
import nz.co.eroad.qaisystem.model.TestStrategy;
import nz.co.eroad.qaisystem.model.TestStrategy.StrategyDecision;
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
 * Routing tests for {@link StrategyOneShotRunner} with a real {@link InMemoryStateStore} and a
 * capturing {@link StrategyAgent} double (no Mockito). Confirms {@code oneshot} persists the
 * strategy, advances the gate, and fans scenarios into {@code scenario_state}; and that
 * {@code list-scenarios} emits the exact GitHub Actions matrix JSON (including {@code --per-pod}).
 */
@DisplayName("StrategyOneShotRunner routing")
class StrategyOneShotRunnerTest {

    /** Returns a canned plan, bypassing real coverage/AI/BDD generation. */
    static class CapturingStrategyAgent extends StrategyAgent {
        private final StrategyAgent.StrategyPlan plan;
        ImpactEnvelope seen;
        CapturingStrategyAgent(StrategyAgent.StrategyPlan plan) {
            super(null, null, null, null, null);
            this.plan = plan;
        }
        @Override public StrategyAgent.StrategyPlan decideAndPlan(ImpactEnvelope envelope) {
            this.seen = envelope;
            return plan;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private StrategyAgent.StrategyPlan planWith(String... scenarioIds) {
        List<BddScenario.Scenario> scenarios = java.util.Arrays.stream(scenarioIds)
                .map(id -> BddScenario.Scenario.builder().scenarioId(id).title("title-" + id).build())
                .toList();
        BddScenario bdd = BddScenario.builder()
                .prId("PR-1").prTitle("Add feature").strategyId("ST-1").scenarioId("F-1")
                .scenarios(scenarios).build();
        TestStrategy strategy = TestStrategy.builder()
                .prId("PR-1").strategyId("ST-1").decision(StrategyDecision.CREATE_TESTS).build();
        return new StrategyAgent.StrategyPlan(strategy, bdd);
    }

    private String envelopeJson() throws Exception {
        return mapper.writeValueAsString(
                ImpactEnvelope.builder().prId("PR-1").riskLevel(RiskLevel.MEDIUM).build());
    }

    @Test
    @DisplayName("oneshot decides strategy, persists it, sets gate, and plans scenarios")
    void oneshot_persistsStrategyGateAndScenarios() throws Exception {
        StateStore store = new InMemoryStateStore();
        store.savePr(PrHistory.builder()
                .prId("PR-1").repo("r").owner("o").headSha("s")
                .impactEnvelope(envelopeJson()).build());

        StrategyOneShotRunner runner = new StrategyOneShotRunner(
                new CapturingStrategyAgent(planWith("S1", "S2")), store, mapper);

        int code = runner.execute(new String[]{"--mode", "oneshot", "--pr-id", "PR-1"});

        assertThat(code).isZero();
        PrHistory after = store.findPr("PR-1").orElseThrow();
        assertThat(after.getStrategy()).contains("\"decision\":\"CREATE_TESTS\"");
        assertThat(after.getGateState()).isEqualTo(GateState.AWAITING_BDD_APPROVAL);

        List<ScenarioState> planned = store.scenariosFor("PR-1");
        assertThat(planned).extracting(ScenarioState::getScenarioId).containsExactly("S1", "S2");
        assertThat(planned).allMatch(s -> ScenarioState.PLANNED.equals(s.getStatus()));
        // each scenario row stores a complete TestScriptRequest JSON for the codegen stage
        assertThat(planned.get(0).getScenario()).contains("\"scenarioId\":\"S1\"");
    }

    @Test
    @DisplayName("oneshot with no stored impact_envelope exits 2")
    void oneshot_missingEnvelope_exits2() {
        StateStore store = new InMemoryStateStore();
        store.savePr(PrHistory.builder().prId("PR-1").repo("r").owner("o").headSha("s").build());
        StrategyOneShotRunner runner = new StrategyOneShotRunner(
                new CapturingStrategyAgent(planWith("S1")), store, mapper);
        assertThat(runner.execute(new String[]{"--mode", "oneshot", "--pr-id", "PR-1"})).isEqualTo(2);
    }

    @Test
    @DisplayName("missing --pr-id exits 2")
    void missingPrId_exits2() {
        StrategyOneShotRunner runner = new StrategyOneShotRunner(
                new CapturingStrategyAgent(planWith("S1")), new InMemoryStateStore(), mapper);
        assertThat(runner.execute(new String[]{"--mode", "oneshot"})).isEqualTo(2);
    }

    @Test
    @DisplayName("unsupported --mode exits 2")
    void unsupportedMode_exits2() {
        StrategyOneShotRunner runner = new StrategyOneShotRunner(
                new CapturingStrategyAgent(planWith("S1")), new InMemoryStateStore(), mapper);
        assertThat(runner.execute(new String[]{"--mode", "bogus", "--pr-id", "PR-1"})).isEqualTo(2);
    }

    @Test
    @DisplayName("list-scenarios builds the exact matrix JSON (one id per cell)")
    void listScenarios_matrixJson_default() throws Exception {
        StateStore store = new InMemoryStateStore();
        store.upsertScenario(ScenarioState.builder().prId("PR-1").scenarioId("S1").status(ScenarioState.PLANNED).build());
        store.upsertScenario(ScenarioState.builder().prId("PR-1").scenarioId("S2").status(ScenarioState.PLANNED).build());
        StrategyOneShotRunner runner = new StrategyOneShotRunner(
                new CapturingStrategyAgent(planWith()), store, mapper);

        assertThat(runner.buildMatrixJson("PR-1", 1))
                .isEqualTo("{\"include\":[{\"id\":\"S1\"},{\"id\":\"S2\"}]}");
        // execute() routes through and returns 0 (prints the JSON to stdout)
        assertThat(runner.execute(new String[]{"--mode", "list-scenarios", "--pr-id", "PR-1"})).isZero();
    }

    @Test
    @DisplayName("list-scenarios groups N ids per matrix cell when --per-pod N")
    void listScenarios_matrixJson_perPod() throws Exception {
        StateStore store = new InMemoryStateStore();
        store.upsertScenario(ScenarioState.builder().prId("PR-1").scenarioId("S1").status(ScenarioState.PLANNED).build());
        store.upsertScenario(ScenarioState.builder().prId("PR-1").scenarioId("S2").status(ScenarioState.PLANNED).build());
        store.upsertScenario(ScenarioState.builder().prId("PR-1").scenarioId("S3").status(ScenarioState.PLANNED).build());
        StrategyOneShotRunner runner = new StrategyOneShotRunner(
                new CapturingStrategyAgent(planWith()), store, mapper);

        assertThat(runner.buildMatrixJson("PR-1", 2))
                .isEqualTo("{\"include\":[{\"id\":\"S1,S2\"},{\"id\":\"S3\"}]}");
    }

    @Test
    @DisplayName("list-scenarios with no planned scenarios yields an empty matrix")
    void listScenarios_empty() throws Exception {
        StrategyOneShotRunner runner = new StrategyOneShotRunner(
                new CapturingStrategyAgent(planWith()), new InMemoryStateStore(), mapper);
        assertThat(runner.buildMatrixJson("PR-NONE", 1)).isEqualTo("{\"include\":[]}");
    }
}
