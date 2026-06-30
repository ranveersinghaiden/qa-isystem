package nz.co.eroad.qaisystem.state;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Immutable carrier for a {@code scenario_state} row — per-scenario codegen tracking that
 * replaces the Kafka fan-out message. The GitHub Actions matrix reads these rows to build the
 * dynamic per-scenario job matrix and to detect completion (all rows terminal).
 *
 * <p>The {@code jsonb} columns ({@link #scenario}, {@link #result}) are held as raw JSON text.
 */
@Value
@Builder(toBuilder = true)
public class ScenarioState {

    /** Source PR id (part of the composite key). */
    String prId;
    /** Scenario id (part of the composite key). */
    String scenarioId;
    /** Nullable SaaS-seam tenant id. */
    String tenantId;
    /** {@code planned | generating | stabilizing | passed | failed}. */
    String status;
    String testPath;
    Integer attempts;
    /** Raw JSON of the {@code TestResult} ({@code jsonb}). */
    String result;
    /** Raw JSON of the scenario work item codegen consumes — a {@code TestScriptRequest} ({@code jsonb}). */
    String scenario;
    Instant updatedAt;

    /** Common {@link #status} values. */
    public static final String PLANNED = "planned";
    public static final String GENERATING = "generating";
    public static final String STABILIZING = "stabilizing";
    public static final String PASSED = "passed";
    public static final String FAILED = "failed";
}
