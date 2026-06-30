package nz.co.eroad.qaisystem.state;

import java.util.List;
import java.util.Optional;

/**
 * Cross-run state API for the scale-to-zero migration — the strangler-fig replacement for the
 * always-on Redis state. Each one-shot pod reads the state it needs, does one thing, persists the
 * result, and exits. Two implementations exist:
 *
 * <ul>
 *   <li>{@link InMemoryStateStore} — default ({@code @ConditionalOnMissingBean}); used by tests and
 *       by any boot without a datasource configured.</li>
 *   <li>{@link PostgresStateStore} — active only when {@code spring.datasource.url} is set.</li>
 * </ul>
 *
 * <p>{@code *Json} parameters are raw JSON text bound straight into {@code jsonb} columns.
 * The {@code update*} mutators assume the PR row already exists (created via {@link #savePr}) —
 * they target an existing {@code pr_history} row keyed by {@code prId}.
 */
public interface StateStore {

    /** Looks up the {@code pr_history} row for {@code prId}. */
    Optional<PrHistory> findPr(String prId);

    /** Inserts or merges (upsert by {@code pr_id}) the given PR row; non-null fields win. */
    void savePr(PrHistory pr);

    /** Advances the gate state-machine for {@code prId} (see {@link GateState}). */
    void updateGate(String prId, String gateState);

    /** Records the computed risk level for {@code prId}. */
    void updateRisk(String prId, String risk);

    /** Persists the raw {@code ImpactEnvelope} JSON for {@code prId}. */
    void saveImpactEnvelope(String prId, String envelopeJson);

    /** Persists the raw {@code TestStrategy} JSON for {@code prId}. */
    void saveStrategy(String prId, String strategyJson);

    /** Records the opened BDD review PR number for {@code prId}. */
    void saveBddPrNumber(String prId, int prNumber);

    /** Records the opened final-test PR number for {@code prId}. */
    void saveTestsPrNumber(String prId, int prNumber);

    /** Inserts or updates (upsert by {@code (pr_id, scenario_id)}) one scenario row. */
    void upsertScenario(ScenarioState s);

    /** Lists all scenario rows for {@code prId}, ordered by {@code scenario_id}. */
    List<ScenarioState> scenariosFor(String prId);

    /**
     * Records the outcome of a single scenario codegen run — sets {@code status}/{@code test_path}/
     * {@code result} and increments {@code attempts} for {@code (prId, scenarioId)}.
     */
    void updateScenarioResult(String prId, String scenarioId, String status, String testPath, String resultJson);
}
