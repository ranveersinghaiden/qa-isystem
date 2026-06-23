package nz.co.eroad.qaisystem.execution;

/**
 * Tracks per-scenario codegen progress to support idempotency (dedup on Kafka redelivery /
 * rebalance) and per-PR completion reporting when scenarios are fanned out and processed in
 * parallel across threads and instances.
 */
public interface CodegenProgressTracker {

    /**
     * Atomically claims a scenario for processing.
     *
     * @return {@code true} if this caller is the first to claim it (proceed), {@code false} if it
     *         was already claimed/processed (skip to avoid duplicate test PRs)
     */
    boolean claimScenario(String scenarioId);

    /**
     * Records a scenario as completed for its PR and returns how many remain.
     *
     * @param prId  the parent PR id
     * @param total total scenarios fanned out for the PR
     * @return remaining scenarios for the PR (0 when this was the last one)
     */
    int completeAndRemaining(String prId, int total);
}

