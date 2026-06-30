package nz.co.eroad.qaisystem.state;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real, working in-memory {@link StateStore} backed by {@link ConcurrentHashMap}. This is the
 * default store ({@code @ConditionalOnMissingBean}) — used by unit tests and by any boot that has
 * no {@code spring.datasource.url} configured. It is NOT a mock: every method performs genuine
 * state transitions so behaviour matches {@link PostgresStateStore} for the tested flows.
 *
 * <p>State does not survive a JVM restart, so it is unsuitable for real scale-to-zero pods — those
 * supply a datasource and get {@link PostgresStateStore}.
 *
 * <p>The {@code spring.datasource.url} property gate (active only when the URL is absent, via
 * {@code matchIfMissing}) makes the choice deterministic and mutually exclusive with
 * {@link PostgresStateStore} regardless of component-scan order; the {@code @ConditionalOnMissingBean}
 * additionally lets a test-supplied {@link StateStore} take precedence.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(StateStore.class)
@ConditionalOnProperty(name = "spring.datasource.url", havingValue = "DISABLED", matchIfMissing = true)
public class InMemoryStateStore implements StateStore {

    private final Map<String, PrHistory> prs = new ConcurrentHashMap<>();
    /** prId -> (scenarioId -> row). */
    private final Map<String, Map<String, ScenarioState>> scenarios = new ConcurrentHashMap<>();

    @Override
    public Optional<PrHistory> findPr(String prId) {
        return Optional.ofNullable(prs.get(prId));
    }

    @Override
    public void savePr(PrHistory pr) {
        if (pr == null || pr.getPrId() == null) {
            throw new IllegalArgumentException("savePr requires a non-null prId");
        }
        prs.merge(pr.getPrId(), pr, InMemoryStateStore::merge);
        log.info("[InMemoryStateStore] savePr prId='{}' (now tracking {} PR(s))",
                pr.getPrId(), prs.size());
    }

    @Override
    public void updateGate(String prId, String gateState) {
        mutate(prId, b -> b.gateState(gateState));
        log.info("[InMemoryStateStore] updateGate prId='{}' -> '{}'", prId, gateState);
    }

    @Override
    public void updateRisk(String prId, String risk) {
        mutate(prId, b -> b.risk(risk));
        log.info("[InMemoryStateStore] updateRisk prId='{}' -> '{}'", prId, risk);
    }

    @Override
    public void saveImpactEnvelope(String prId, String envelopeJson) {
        mutate(prId, b -> b.impactEnvelope(envelopeJson));
        log.info("[InMemoryStateStore] saveImpactEnvelope prId='{}' ({} bytes)",
                prId, envelopeJson == null ? 0 : envelopeJson.length());
    }

    @Override
    public void saveStrategy(String prId, String strategyJson) {
        mutate(prId, b -> b.strategy(strategyJson));
        log.info("[InMemoryStateStore] saveStrategy prId='{}' ({} bytes)",
                prId, strategyJson == null ? 0 : strategyJson.length());
    }

    @Override
    public void saveBddPrNumber(String prId, int prNumber) {
        mutate(prId, b -> b.bddPrNumber(prNumber));
        log.info("[InMemoryStateStore] saveBddPrNumber prId='{}' -> #{}", prId, prNumber);
    }

    @Override
    public void saveTestsPrNumber(String prId, int prNumber) {
        mutate(prId, b -> b.testsPrNumber(prNumber));
        log.info("[InMemoryStateStore] saveTestsPrNumber prId='{}' -> #{}", prId, prNumber);
    }

    @Override
    public void upsertScenario(ScenarioState s) {
        if (s == null || s.getPrId() == null || s.getScenarioId() == null) {
            throw new IllegalArgumentException("upsertScenario requires non-null prId and scenarioId");
        }
        scenarios.computeIfAbsent(s.getPrId(), k -> new ConcurrentHashMap<>())
                .merge(s.getScenarioId(), withStamp(s), InMemoryStateStore::mergeScenario);
        log.info("[InMemoryStateStore] upsertScenario prId='{}' scenarioId='{}' status='{}'",
                s.getPrId(), s.getScenarioId(), s.getStatus());
    }

    @Override
    public List<ScenarioState> scenariosFor(String prId) {
        List<ScenarioState> out = new ArrayList<>(
                scenarios.getOrDefault(prId, Map.of()).values());
        out.sort(Comparator.comparing(ScenarioState::getScenarioId,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    @Override
    public void updateScenarioResult(String prId, String scenarioId, String status,
                                     String testPath, String resultJson) {
        Map<String, ScenarioState> byScenario =
                scenarios.computeIfAbsent(prId, k -> new ConcurrentHashMap<>());
        byScenario.compute(scenarioId, (k, existing) -> {
            ScenarioState.ScenarioStateBuilder b = existing != null
                    ? existing.toBuilder()
                    : ScenarioState.builder().prId(prId).scenarioId(scenarioId).attempts(0);
            int attempts = (existing != null && existing.getAttempts() != null)
                    ? existing.getAttempts() : 0;
            return b.status(status)
                    .testPath(testPath)
                    .result(resultJson)
                    .attempts(attempts + 1)
                    .updatedAt(Instant.now())
                    .build();
        });
        log.info("[InMemoryStateStore] updateScenarioResult prId='{}' scenarioId='{}' status='{}'",
                prId, scenarioId, status);
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private void mutate(String prId, java.util.function.UnaryOperator<PrHistory.PrHistoryBuilder> op) {
        prs.compute(prId, (k, existing) -> {
            PrHistory.PrHistoryBuilder b = existing != null
                    ? existing.toBuilder()
                    : PrHistory.builder().prId(prId).createdAt(Instant.now());
            return op.apply(b).updatedAt(Instant.now()).build();
        });
    }

    /** Merge incoming over existing, preserving existing non-null fields when incoming is null. */
    private static PrHistory merge(PrHistory existing, PrHistory incoming) {
        return PrHistory.builder()
                .prId(incoming.getPrId())
                .tenantId(pick(incoming.getTenantId(), existing.getTenantId()))
                .repo(pick(incoming.getRepo(), existing.getRepo()))
                .owner(pick(incoming.getOwner(), existing.getOwner()))
                .prNumber(pick(incoming.getPrNumber(), existing.getPrNumber()))
                .headSha(pick(incoming.getHeadSha(), existing.getHeadSha()))
                .branch(pick(incoming.getBranch(), existing.getBranch()))
                .payload(pick(incoming.getPayload(), existing.getPayload()))
                .impactEnvelope(pick(incoming.getImpactEnvelope(), existing.getImpactEnvelope()))
                .strategy(pick(incoming.getStrategy(), existing.getStrategy()))
                .risk(pick(incoming.getRisk(), existing.getRisk()))
                .gateState(pick(incoming.getGateState(), existing.getGateState()))
                .bddPrNumber(pick(incoming.getBddPrNumber(), existing.getBddPrNumber()))
                .testsPrNumber(pick(incoming.getTestsPrNumber(), existing.getTestsPrNumber()))
                .createdAt(pick(existing.getCreatedAt(), incoming.getCreatedAt()))
                .updatedAt(Instant.now())
                .build();
    }

    private static ScenarioState mergeScenario(ScenarioState existing, ScenarioState incoming) {
        return ScenarioState.builder()
                .prId(incoming.getPrId())
                .scenarioId(incoming.getScenarioId())
                .tenantId(pick(incoming.getTenantId(), existing.getTenantId()))
                .status(pick(incoming.getStatus(), existing.getStatus()))
                .testPath(pick(incoming.getTestPath(), existing.getTestPath()))
                .attempts(pick(incoming.getAttempts(), existing.getAttempts()))
                .result(pick(incoming.getResult(), existing.getResult()))
                .scenario(pick(incoming.getScenario(), existing.getScenario()))
                .updatedAt(Instant.now())
                .build();
    }

    private static ScenarioState withStamp(ScenarioState s) {
        ScenarioState.ScenarioStateBuilder b = s.toBuilder().updatedAt(Instant.now());
        if (s.getAttempts() == null) {
            b.attempts(0);
        }
        return b.build();
    }

    private static <T> T pick(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }
}
