package nz.co.eroad.qaisystem.state;

/**
 * Canonical {@code pr_history.gate_state} values for the QA gate state-machine.
 *
 * <p>String constants (not an enum) so they bind directly to the {@code text} column and
 * survive across one-shot pod runs without enum-ordinal coupling.
 */
public final class GateState {

    private GateState() {
    }

    /** Freshly ingested PR, no analysis yet. */
    public static final String NEW = "new";
    /** BDD scenarios generated; a BDD review PR is open and awaiting human approval. */
    public static final String AWAITING_BDD_APPROVAL = "awaiting_bdd_approval";
    /** BDD review PR merged/approved; ready for codegen. */
    public static final String BDD_APPROVED = "bdd_approved";
    /** Test code generated; a test PR is open and awaiting human approval. */
    public static final String AWAITING_TESTS_APPROVAL = "awaiting_tests_approval";
    /** Test PR merged/approved. */
    public static final String TESTS_APPROVED = "tests_approved";
    /** Terminal success (or SKIP) — nothing further to do. */
    public static final String DONE = "done";
    /** A QA PR was rejected; the feedback loop owns it. */
    public static final String REJECTED = "rejected";
}
