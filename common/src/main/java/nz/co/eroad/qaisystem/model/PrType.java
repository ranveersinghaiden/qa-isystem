package nz.co.eroad.qaisystem.model;

/** Type of a QA-generated Pull Request tracked in {@link nz.co.eroad.qaisystem.github.PrTracker}. */
public enum PrType {
    /** BDD scenario review PR — merging triggers code generation. */
    BDD,
    /** Final test-code review PR — merging completes the pipeline. */
    TEST
}

