package nz.co.eroad.qaisystem.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Data;

/**
 * Immutable record of a QA-generated Pull Request.
 * Stored in {@link nz.co.eroad.qaisystem.github.PrTracker} keyed by branch name.
 *
 * <p>Jackson deserialization uses the Lombok-generated builder so no
 * no-args constructor is required (compatible with all-final fields).
 */
@Data
@Builder
@JsonDeserialize(builder = PrRecord.PrRecordBuilder.class)
public class PrRecord {
    private final String branchName;
    private final int    prNumber;
    private final PrType type;

    /** Non-null when {@code type == BDD}. */
    private final BddScenario bddScenario;

    /** Non-null when {@code type == TEST}. */
    private final TestScript testScript;

    /**
     * Tells Jackson that the Lombok builder methods have no prefix
     * (e.g. {@code .branchName(...)} not {@code .withBranchName(...)}).
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class PrRecordBuilder {
        // Lombok generates the builder body — this class only carries the annotation.
    }
}


