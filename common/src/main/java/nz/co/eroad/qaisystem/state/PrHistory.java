package nz.co.eroad.qaisystem.state;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Immutable carrier for a {@code pr_history} row — the cross-run source of truth for one
 * source PR as it moves through the QA gate state-machine.
 *
 * <p>The {@code jsonb} columns ({@link #payload}, {@link #impactEnvelope}, {@link #strategy})
 * are held as raw JSON text; callers serialize/deserialize with their own {@code ObjectMapper}.
 */
@Value
@Builder(toBuilder = true)
public class PrHistory {

    /** Primary key — the source PR id (e.g. {@code "PR-123"}). */
    String prId;
    /** Nullable SaaS-seam tenant id (single-org today). */
    String tenantId;
    String repo;
    String owner;
    Integer prNumber;
    String headSha;
    String branch;
    /** Raw JSON of the original webhook / {@code PullRequest} payload ({@code jsonb}). */
    String payload;
    /** Raw JSON of the {@code ImpactEnvelope} produced by the impact pod ({@code jsonb}). */
    String impactEnvelope;
    /** Raw JSON of the {@code TestStrategy} produced by the strategy pod ({@code jsonb}). */
    String strategy;
    String risk;
    /** One of {@link GateState}. */
    String gateState;
    Integer bddPrNumber;
    Integer testsPrNumber;
    Instant createdAt;
    Instant updatedAt;
}
