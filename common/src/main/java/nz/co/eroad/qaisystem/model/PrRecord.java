package nz.co.eroad.qaisystem.model;

import lombok.Builder;
import lombok.Data;

/**
 * Immutable record of a QA-generated Pull Request.
 * Stored in {@link nz.co.eroad.qaisystem.github.PrTracker} keyed by branch name.
 */
@Data
@Builder
public class PrRecord {
    private final String branchName;
    private final int    prNumber;
    private final PrType type;

    /** Non-null when {@code type == BDD}. */
    private final BddScenario bddScenario;

    /** Non-null when {@code type == TEST}. */
    private final TestScript testScript;
}

