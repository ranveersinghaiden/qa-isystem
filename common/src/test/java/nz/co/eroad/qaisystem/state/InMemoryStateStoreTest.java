package nz.co.eroad.qaisystem.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real round-trip tests for {@link InMemoryStateStore} — the default {@link StateStore}. No Mockito:
 * the store is a genuine {@link java.util.concurrent.ConcurrentHashMap}-backed implementation, so
 * these tests double as the executable spec every {@code one-shot} runner relies on.
 */
@DisplayName("InMemoryStateStore round-trip")
class InMemoryStateStoreTest {

    private final InMemoryStateStore store = new InMemoryStateStore();

    @Test
    @DisplayName("savePr then findPr returns the persisted row")
    void savePr_findPr_roundTrip() {
        store.savePr(PrHistory.builder()
                .prId("PR-1").tenantId("t1").repo("repo").owner("acme")
                .prNumber(42).headSha("abc").branch("feature/x")
                .payload("{\"prId\":\"PR-1\"}").gateState(GateState.NEW)
                .build());

        Optional<PrHistory> found = store.findPr("PR-1");
        assertThat(found).isPresent();
        assertThat(found.get().getRepo()).isEqualTo("repo");
        assertThat(found.get().getOwner()).isEqualTo("acme");
        assertThat(found.get().getPrNumber()).isEqualTo(42);
        assertThat(found.get().getPayload()).isEqualTo("{\"prId\":\"PR-1\"}");
        assertThat(found.get().getGateState()).isEqualTo(GateState.NEW);
    }

    @Test
    @DisplayName("findPr for an unknown prId returns empty")
    void findPr_unknown_empty() {
        assertThat(store.findPr("nope")).isEmpty();
    }

    @Test
    @DisplayName("savePr is an upsert that merges non-null fields over an existing row")
    void savePr_mergesNonNull() {
        store.savePr(PrHistory.builder()
                .prId("PR-2").repo("repo").owner("acme").headSha("sha1")
                .payload("PAYLOAD").gateState(GateState.NEW).build());
        // Second save carries only a subset of columns; null fields must NOT clobber existing values.
        store.savePr(PrHistory.builder()
                .prId("PR-2").branch("added-branch").build());

        PrHistory merged = store.findPr("PR-2").orElseThrow();
        assertThat(merged.getPayload()).isEqualTo("PAYLOAD");      // preserved
        assertThat(merged.getRepo()).isEqualTo("repo");            // preserved
        assertThat(merged.getBranch()).isEqualTo("added-branch");  // added
    }

    @Test
    @DisplayName("savePr rejects a null prId")
    void savePr_nullPrId_throws() {
        assertThatThrownBy(() -> store.savePr(PrHistory.builder().build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("update* mutators create-or-update the pr_history row")
    void updates_applyToRow() {
        store.savePr(PrHistory.builder().prId("PR-3").repo("r").owner("o").headSha("s").build());

        store.updateGate("PR-3", GateState.AWAITING_BDD_APPROVAL);
        store.updateRisk("PR-3", "HIGH");
        store.saveImpactEnvelope("PR-3", "{\"env\":1}");
        store.saveStrategy("PR-3", "{\"strat\":1}");
        store.saveBddPrNumber("PR-3", 101);
        store.saveTestsPrNumber("PR-3", 202);

        PrHistory pr = store.findPr("PR-3").orElseThrow();
        assertThat(pr.getGateState()).isEqualTo(GateState.AWAITING_BDD_APPROVAL);
        assertThat(pr.getRisk()).isEqualTo("HIGH");
        assertThat(pr.getImpactEnvelope()).isEqualTo("{\"env\":1}");
        assertThat(pr.getStrategy()).isEqualTo("{\"strat\":1}");
        assertThat(pr.getBddPrNumber()).isEqualTo(101);
        assertThat(pr.getTestsPrNumber()).isEqualTo(202);
    }

    @Test
    @DisplayName("upsertScenario + scenariosFor returns rows ordered by scenarioId")
    void scenarios_upsertAndListOrdered() {
        store.upsertScenario(ScenarioState.builder()
                .prId("PR-4").scenarioId("S2").status(ScenarioState.PLANNED).scenario("{\"i\":2}").build());
        store.upsertScenario(ScenarioState.builder()
                .prId("PR-4").scenarioId("S1").status(ScenarioState.PLANNED).scenario("{\"i\":1}").build());
        store.upsertScenario(ScenarioState.builder()
                .prId("PR-4").scenarioId("S10").status(ScenarioState.PLANNED).scenario("{\"i\":10}").build());

        List<ScenarioState> rows = store.scenariosFor("PR-4");
        assertThat(rows).extracting(ScenarioState::getScenarioId)
                .containsExactly("S1", "S10", "S2"); // lexicographic by scenarioId
        assertThat(rows).allMatch(s -> ScenarioState.PLANNED.equals(s.getStatus()));
    }

    @Test
    @DisplayName("upsertScenario on the same id updates in place (no duplicate row)")
    void upsertScenario_replacesSameId() {
        store.upsertScenario(ScenarioState.builder()
                .prId("PR-5").scenarioId("S1").status(ScenarioState.PLANNED).build());
        store.upsertScenario(ScenarioState.builder()
                .prId("PR-5").scenarioId("S1").status(ScenarioState.GENERATING).build());

        List<ScenarioState> rows = store.scenariosFor("PR-5");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(ScenarioState.GENERATING);
    }

    @Test
    @DisplayName("scenariosFor an unknown prId is empty")
    void scenariosFor_unknown_empty() {
        assertThat(store.scenariosFor("none")).isEmpty();
    }

    @Test
    @DisplayName("updateScenarioResult sets status/testPath/result and increments attempts")
    void updateScenarioResult_recordsOutcomeAndAttempts() {
        store.upsertScenario(ScenarioState.builder()
                .prId("PR-6").scenarioId("S1").status(ScenarioState.PLANNED).attempts(0).build());

        store.updateScenarioResult("PR-6", "S1", ScenarioState.PASSED, "src/test/Foo.java", "{\"passed\":true}");

        ScenarioState row = store.scenariosFor("PR-6").get(0);
        assertThat(row.getStatus()).isEqualTo(ScenarioState.PASSED);
        assertThat(row.getTestPath()).isEqualTo("src/test/Foo.java");
        assertThat(row.getResult()).isEqualTo("{\"passed\":true}");
        assertThat(row.getAttempts()).isEqualTo(1); // incremented from 0

        store.updateScenarioResult("PR-6", "S1", ScenarioState.FAILED, null, "{\"passed\":false}");
        ScenarioState afterRetry = store.scenariosFor("PR-6").get(0);
        assertThat(afterRetry.getStatus()).isEqualTo(ScenarioState.FAILED);
        assertThat(afterRetry.getAttempts()).isEqualTo(2); // incremented again
    }

    @Test
    @DisplayName("updateScenarioResult creates a row when the scenario was never planned")
    void updateScenarioResult_createsWhenMissing() {
        store.updateScenarioResult("PR-7", "S9", ScenarioState.PASSED, null, "{}");

        List<ScenarioState> rows = store.scenariosFor("PR-7");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getScenarioId()).isEqualTo("S9");
        assertThat(rows.get(0).getAttempts()).isEqualTo(1);
    }
}
