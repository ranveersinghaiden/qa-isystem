package nz.co.eroad.qaisystem.model;

import lombok.Builder;
import lombok.Data;

/**
 * Kafka message published to {@code FeedbackQueue} when a QA-generated PR is rejected.
 * Consumed by {@code feedback-service} which re-generates the content.
 */
@Data
@Builder
public class FeedbackEvent {

    /** Name of the closed GitHub branch (used to look up context in PrTracker). */
    private String   branchName;

    /** GitHub PR number of the rejected PR. */
    private int      prNumber;

    /** Whether this is a BDD scenario PR or a final test-code PR. */
    private PrType   prType;

    /** Non-null when {@code prType == BDD}. */
    private BddScenario bddScenario;

    /** Non-null when {@code prType == TEST}. */
    private TestScript  testScript;
}

