package nz.co.eroad.qaisystem.state;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC-backed {@link StateStore} against Neon Postgres (schema in {@code db/schema.sql}). Active
 * only when {@code spring.datasource.url} is set ({@code @ConditionalOnProperty}); otherwise the
 * default {@link InMemoryStateStore} wins. Real scale-to-zero pods supply the URL and use this.
 *
 * <p>{@code jsonb} columns are written by binding the JSON {@code String} and casting in SQL
 * ({@code ?::jsonb}). {@link #savePr} is an upsert that merges with {@code coalesce(excluded.x,
 * pr_history.x)} so a partial save never clobbers an existing value with {@code null}. The
 * {@code update*} mutators are plain {@code UPDATE}s — they assume the row exists (created by
 * {@link #savePr}); a blind insert would violate the {@code NOT NULL} constraints on
 * {@code repo/owner/head_sha}.
 *
 * <p>Logging is sizes-only: payload / envelope / strategy / result JSON is never logged at INFO.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.datasource.url")
public class PostgresStateStore implements StateStore {

    private final JdbcTemplate jdbc;

    public PostgresStateStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        log.info("[PostgresStateStore] Initialised against configured datasource");
    }

    private static final String SELECT_PR = """
            select pr_id, tenant_id, repo, owner, pr_number, head_sha, branch,
                   payload::text, impact_envelope::text, strategy::text, risk, gate_state,
                   bdd_pr_number, tests_pr_number, created_at, updated_at
              from pr_history
             where pr_id = ?
            """;

    @Override
    public Optional<PrHistory> findPr(String prId) {
        List<PrHistory> rows = jdbc.query(SELECT_PR, PR_MAPPER, prId);
        log.info("[PostgresStateStore] findPr prId='{}' -> {}", prId, rows.isEmpty() ? "miss" : "hit");
        return rows.stream().findFirst();
    }

    private static final String UPSERT_PR = """
            insert into pr_history (pr_id, tenant_id, repo, owner, pr_number, head_sha, branch,
                                    payload, impact_envelope, strategy, risk, gate_state,
                                    bdd_pr_number, tests_pr_number)
            values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, coalesce(?, 'new'), ?, ?)
            on conflict (pr_id) do update set
                tenant_id       = coalesce(excluded.tenant_id, pr_history.tenant_id),
                repo            = coalesce(excluded.repo, pr_history.repo),
                owner           = coalesce(excluded.owner, pr_history.owner),
                pr_number       = coalesce(excluded.pr_number, pr_history.pr_number),
                head_sha        = coalesce(excluded.head_sha, pr_history.head_sha),
                branch          = coalesce(excluded.branch, pr_history.branch),
                payload         = coalesce(excluded.payload, pr_history.payload),
                impact_envelope = coalesce(excluded.impact_envelope, pr_history.impact_envelope),
                strategy        = coalesce(excluded.strategy, pr_history.strategy),
                risk            = coalesce(excluded.risk, pr_history.risk),
                gate_state      = coalesce(excluded.gate_state, pr_history.gate_state),
                bdd_pr_number   = coalesce(excluded.bdd_pr_number, pr_history.bdd_pr_number),
                tests_pr_number = coalesce(excluded.tests_pr_number, pr_history.tests_pr_number),
                updated_at      = now()
            """;

    @Override
    public void savePr(PrHistory pr) {
        jdbc.update(UPSERT_PR,
                pr.getPrId(), pr.getTenantId(), pr.getRepo(), pr.getOwner(), pr.getPrNumber(),
                pr.getHeadSha(), pr.getBranch(), pr.getPayload(), pr.getImpactEnvelope(),
                pr.getStrategy(), pr.getRisk(), pr.getGateState(), pr.getBddPrNumber(),
                pr.getTestsPrNumber());
        log.info("[PostgresStateStore] savePr prId='{}' gate='{}'", pr.getPrId(), pr.getGateState());
    }

    @Override
    public void updateGate(String prId, String gateState) {
        int n = jdbc.update("update pr_history set gate_state = ?, updated_at = now() where pr_id = ?",
                gateState, prId);
        log.info("[PostgresStateStore] updateGate prId='{}' -> '{}' ({} row(s))", prId, gateState, n);
    }

    @Override
    public void updateRisk(String prId, String risk) {
        int n = jdbc.update("update pr_history set risk = ?, updated_at = now() where pr_id = ?",
                risk, prId);
        log.info("[PostgresStateStore] updateRisk prId='{}' -> '{}' ({} row(s))", prId, risk, n);
    }

    @Override
    public void saveImpactEnvelope(String prId, String envelopeJson) {
        int n = jdbc.update(
                "update pr_history set impact_envelope = ?::jsonb, updated_at = now() where pr_id = ?",
                envelopeJson, prId);
        log.info("[PostgresStateStore] saveImpactEnvelope prId='{}' ({} bytes, {} row(s))",
                prId, envelopeJson == null ? 0 : envelopeJson.length(), n);
    }

    @Override
    public void saveStrategy(String prId, String strategyJson) {
        int n = jdbc.update(
                "update pr_history set strategy = ?::jsonb, updated_at = now() where pr_id = ?",
                strategyJson, prId);
        log.info("[PostgresStateStore] saveStrategy prId='{}' ({} bytes, {} row(s))",
                prId, strategyJson == null ? 0 : strategyJson.length(), n);
    }

    @Override
    public void saveBddPrNumber(String prId, int prNumber) {
        int n = jdbc.update(
                "update pr_history set bdd_pr_number = ?, updated_at = now() where pr_id = ?",
                prNumber, prId);
        log.info("[PostgresStateStore] saveBddPrNumber prId='{}' -> #{} ({} row(s))", prId, prNumber, n);
    }

    @Override
    public void saveTestsPrNumber(String prId, int prNumber) {
        int n = jdbc.update(
                "update pr_history set tests_pr_number = ?, updated_at = now() where pr_id = ?",
                prNumber, prId);
        log.info("[PostgresStateStore] saveTestsPrNumber prId='{}' -> #{} ({} row(s))", prId, prNumber, n);
    }

    private static final String UPSERT_SCENARIO = """
            insert into scenario_state (pr_id, scenario_id, tenant_id, status, test_path,
                                        attempts, result, scenario, updated_at)
            values (?, ?, ?, ?, ?, coalesce(?, 0), ?::jsonb, ?::jsonb, now())
            on conflict (pr_id, scenario_id) do update set
                tenant_id  = coalesce(excluded.tenant_id, scenario_state.tenant_id),
                status     = coalesce(excluded.status, scenario_state.status),
                test_path  = coalesce(excluded.test_path, scenario_state.test_path),
                attempts   = coalesce(excluded.attempts, scenario_state.attempts),
                result     = coalesce(excluded.result, scenario_state.result),
                scenario   = coalesce(excluded.scenario, scenario_state.scenario),
                updated_at = now()
            """;

    @Override
    public void upsertScenario(ScenarioState s) {
        jdbc.update(UPSERT_SCENARIO,
                s.getPrId(), s.getScenarioId(), s.getTenantId(), s.getStatus(), s.getTestPath(),
                s.getAttempts(), s.getResult(), s.getScenario());
        log.info("[PostgresStateStore] upsertScenario prId='{}' scenarioId='{}' status='{}'",
                s.getPrId(), s.getScenarioId(), s.getStatus());
    }

    private static final String SELECT_SCENARIOS = """
            select pr_id, scenario_id, tenant_id, status, test_path, attempts,
                   result::text, scenario::text, updated_at
              from scenario_state
             where pr_id = ?
             order by scenario_id
            """;

    @Override
    public List<ScenarioState> scenariosFor(String prId) {
        List<ScenarioState> rows = jdbc.query(SELECT_SCENARIOS, SCENARIO_MAPPER, prId);
        log.info("[PostgresStateStore] scenariosFor prId='{}' -> {} row(s)", prId, rows.size());
        return rows;
    }

    @Override
    public void updateScenarioResult(String prId, String scenarioId, String status,
                                     String testPath, String resultJson) {
        int n = jdbc.update("""
                update scenario_state
                   set status = ?, test_path = ?, result = ?::jsonb,
                       attempts = coalesce(attempts, 0) + 1, updated_at = now()
                 where pr_id = ? and scenario_id = ?
                """, status, testPath, resultJson, prId, scenarioId);
        log.info("[PostgresStateStore] updateScenarioResult prId='{}' scenarioId='{}' status='{}' ({} row(s))",
                prId, scenarioId, status, n);
    }

    // ─── row mappers ──────────────────────────────────────────────────────────────

    private static final RowMapper<PrHistory> PR_MAPPER = (ResultSet rs, int rowNum) ->
            PrHistory.builder()
                    .prId(rs.getString("pr_id"))
                    .tenantId(rs.getString("tenant_id"))
                    .repo(rs.getString("repo"))
                    .owner(rs.getString("owner"))
                    .prNumber(intOrNull(rs, "pr_number"))
                    .headSha(rs.getString("head_sha"))
                    .branch(rs.getString("branch"))
                    .payload(rs.getString("payload"))
                    .impactEnvelope(rs.getString("impact_envelope"))
                    .strategy(rs.getString("strategy"))
                    .risk(rs.getString("risk"))
                    .gateState(rs.getString("gate_state"))
                    .bddPrNumber(intOrNull(rs, "bdd_pr_number"))
                    .testsPrNumber(intOrNull(rs, "tests_pr_number"))
                    .createdAt(instantOrNull(rs, "created_at"))
                    .updatedAt(instantOrNull(rs, "updated_at"))
                    .build();

    private static final RowMapper<ScenarioState> SCENARIO_MAPPER = (ResultSet rs, int rowNum) ->
            ScenarioState.builder()
                    .prId(rs.getString("pr_id"))
                    .scenarioId(rs.getString("scenario_id"))
                    .tenantId(rs.getString("tenant_id"))
                    .status(rs.getString("status"))
                    .testPath(rs.getString("test_path"))
                    .attempts(intOrNull(rs, "attempts"))
                    .result(rs.getString("result"))
                    .scenario(rs.getString("scenario"))
                    .updatedAt(instantOrNull(rs, "updated_at"))
                    .build();

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Instant instantOrNull(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }
}
