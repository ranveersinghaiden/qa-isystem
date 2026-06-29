package nz.co.eroad.qaisystem.model;

/**
 * Deterministic scenario-coverage taxonomy. Each capability impacted by a PR is
 * expected to be tested across a subset of these classes, selected per
 * {@link ImpactEnvelope.ChangeType}. Lets the pipeline measure <em>how well</em> a
 * capability is tested rather than the binary <em>has-a-test</em> signal alone.
 *
 * <p>Tag mapping ({@link #tag()}) is what the Gherkin generator emits so existing
 * scenarios can be parsed back into classes for the COVERED/PLANNED matrix.
 */
public enum ScenarioClass {
    HAPPY_PATH("@happy"),
    ALTERNATE("@alt"),
    BOUNDARY("@boundary"),
    NEGATIVE("@negative"),
    AUTH("@auth"),
    ERROR("@error"),
    REGRESSION("@regression"),
    COMPAT("@compat"),
    DATA_INTEGRITY("@data");

    private final String tag;

    ScenarioClass(String tag) {
        this.tag = tag;
    }

    /** Gherkin tag emitted/parsed for this class, e.g. {@code @boundary}. */
    public String tag() {
        return tag;
    }
}
