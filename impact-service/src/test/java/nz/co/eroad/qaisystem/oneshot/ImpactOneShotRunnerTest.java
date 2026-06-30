package nz.co.eroad.qaisystem.oneshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.engine.ImpactEngine;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.RiskLevel;
import nz.co.eroad.qaisystem.model.PullRequest;
import nz.co.eroad.qaisystem.state.InMemoryStateStore;
import nz.co.eroad.qaisystem.state.PrHistory;
import nz.co.eroad.qaisystem.state.StateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routing tests for {@link ImpactOneShotRunner} with a real {@link InMemoryStateStore} and a
 * capturing {@link ImpactEngine} double (no Mockito). Confirms the one-shot pod reads the stored
 * {@code payload}, calls the SAME {@code analyze} bean the Kafka consumer uses, and persists the
 * envelope JSON + risk level.
 */
@DisplayName("ImpactOneShotRunner routing")
class ImpactOneShotRunnerTest {

    /** Captures the PR passed in and returns a canned envelope — bypasses real diff/AI analysis. */
    static class CapturingImpactEngine extends ImpactEngine {
        private final ImpactEnvelope canned;
        PullRequest seen;
        CapturingImpactEngine(ImpactEnvelope canned) {
            super(null, null, null, null, null, null);
            this.canned = canned;
        }
        @Override public ImpactEnvelope analyze(PullRequest pr) {
            this.seen = pr;
            return canned;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private String payloadFor(String prId) throws Exception {
        return mapper.writeValueAsString(PullRequest.builder().prId(prId).title("Add feature").build());
    }

    @Test
    @DisplayName("oneshot reads payload, runs analyze, persists envelope + risk, exits 0")
    void oneshot_happyPath() throws Exception {
        StateStore store = new InMemoryStateStore();
        store.savePr(PrHistory.builder()
                .prId("PR-1").repo("r").owner("o").headSha("s")
                .payload(payloadFor("PR-1")).build());

        ImpactEnvelope envelope = ImpactEnvelope.builder()
                .prId("PR-1").riskLevel(RiskLevel.HIGH).overallRiskScore(0.8).build();
        CapturingImpactEngine engine = new CapturingImpactEngine(envelope);
        ImpactOneShotRunner runner = new ImpactOneShotRunner(engine, store, mapper);

        int code = runner.execute(new String[]{"--mode", "oneshot", "--pr-id", "PR-1"});

        assertThat(code).isZero();
        assertThat(engine.seen).isNotNull();
        assertThat(engine.seen.getPrId()).isEqualTo("PR-1");
        PrHistory after = store.findPr("PR-1").orElseThrow();
        assertThat(after.getImpactEnvelope()).contains("\"riskLevel\":\"HIGH\"");
        assertThat(after.getRisk()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("missing --pr-id exits 2")
    void missingPrId_exits2() {
        ImpactOneShotRunner runner = new ImpactOneShotRunner(
                new CapturingImpactEngine(ImpactEnvelope.builder().build()), new InMemoryStateStore(), mapper);
        assertThat(runner.execute(new String[]{"--mode", "oneshot"})).isEqualTo(2);
    }

    @Test
    @DisplayName("no pr_history row exits 2")
    void noPrRow_exits2() {
        ImpactOneShotRunner runner = new ImpactOneShotRunner(
                new CapturingImpactEngine(ImpactEnvelope.builder().build()), new InMemoryStateStore(), mapper);
        assertThat(runner.execute(new String[]{"--mode", "oneshot", "--pr-id", "ABSENT"})).isEqualTo(2);
    }

    @Test
    @DisplayName("PR row without a payload exits 2")
    void missingPayload_exits2() {
        StateStore store = new InMemoryStateStore();
        store.savePr(PrHistory.builder().prId("PR-2").repo("r").owner("o").headSha("s").build());
        ImpactOneShotRunner runner = new ImpactOneShotRunner(
                new CapturingImpactEngine(ImpactEnvelope.builder().build()), store, mapper);
        assertThat(runner.execute(new String[]{"--mode", "oneshot", "--pr-id", "PR-2"})).isEqualTo(2);
    }

    @Test
    @DisplayName("unsupported --mode exits 2")
    void unsupportedMode_exits2() {
        ImpactOneShotRunner runner = new ImpactOneShotRunner(
                new CapturingImpactEngine(ImpactEnvelope.builder().build()), new InMemoryStateStore(), mapper);
        assertThat(runner.execute(new String[]{"--mode", "bogus", "--pr-id", "PR-1"})).isEqualTo(2);
    }
}
