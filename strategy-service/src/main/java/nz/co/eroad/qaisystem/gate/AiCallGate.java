package nz.co.eroad.qaisystem.gate;

import lombok.extern.slf4j.Slf4j;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ChangeType;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.RiskLevel;
import nz.co.eroad.qaisystem.model.TestStrategy.StrategyDecision;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluates an {@link ImpactEnvelope} against deterministic rules and returns a
 * {@link GateDecision} that indicates whether the AI pipeline should be invoked.
 *
 * <p>Rules are evaluated in priority order; the first match wins.
 */
@Slf4j
@Component
public class AiCallGate {

    private static final List<String> DOC_EXTENSIONS = List.of(".md", ".txt", ".rst");
    private static final String DOCS_PATH_SEGMENT = "/docs/";

    /** Outcome categories returned by the gate. */
    public enum GateOutcome { SKIP, RULE_HANDLED, NEEDS_AI }

    /**
     * Immutable result of a gate evaluation.
     *
     * @param outcome      the gate outcome
     * @param ruleDecision the pre-computed strategy decision when outcome is RULE_HANDLED
     * @param reason       human-readable explanation
     */
    public record GateDecision(GateOutcome outcome, StrategyDecision ruleDecision, String reason) {

        /** Factory — PR should be silently skipped, no test action needed. */
        public static GateDecision skip(String reason) {
            return new GateDecision(GateOutcome.SKIP, StrategyDecision.SKIP, reason);
        }

        /** Factory — a rule determined the strategy without needing AI. */
        public static GateDecision ruleHandled(StrategyDecision decision, String reason) {
            return new GateDecision(GateOutcome.RULE_HANDLED, decision, reason);
        }

        /** Factory — AI must be called to determine the correct strategy. */
        public static GateDecision needsAi(String reason) {
            return new GateDecision(GateOutcome.NEEDS_AI, null, reason);
        }

        /** Returns {@code true} when the AI pipeline should be invoked. */
        public boolean shouldCallAi() { return outcome == GateOutcome.NEEDS_AI; }
    }

    /**
     * Evaluates the envelope against all gate rules and returns the first matching decision.
     *
     * @param envelope the impact envelope for the PR under evaluation
     * @return the gate decision (never null)
     */
    public GateDecision evaluate(ImpactEnvelope envelope) {
        log.debug("[AiCallGate] Evaluating PR '{}'", envelope.getPrId());

        int totalFiles     = envelope.getTotalFilesChanged();
        int affectedTests  = envelope.getAffectedTestFiles();
        int linesAdded     = envelope.getTotalLinesAdded();
        int linesDeleted   = envelope.getTotalLinesDeleted();
        RiskLevel riskLevel = envelope.getRiskLevel();

        var changeTypes    = envelope.getDetectedChangeTypes();
        var components     = envelope.getImpactedComponents();
        var existingTests  = envelope.getExistingTestFiles();

        // Rule 1 — only test files modified
        if (totalFiles > 0 && totalFiles == affectedTests) {
            return GateDecision.skip("Only test files modified");
        }

        // Rule 2 — documentation-only change
        if (components != null && !components.isEmpty() && isDocumentationOnly(components)) {
            return GateDecision.skip("Documentation-only change");
        }

        // Rule 3 — trivial change under 10 lines, low risk
        if ((linesAdded + linesDeleted) < 10 && riskLevel == RiskLevel.LOW) {
            return GateDecision.skip("Trivial change under 10 lines, low risk");
        }

        // Rule 4 — version bump only (single file, only DEPENDENCY_UPDATE)
        if (changeTypes != null
                && changeTypes.size() == 1
                && changeTypes.contains(ChangeType.DEPENDENCY_UPDATE)
                && totalFiles == 1) {
            return GateDecision.skip("Version bump only");
        }

        // Rule 5 — low-risk change with existing tests
        if (existingTests != null && !existingTests.isEmpty() && riskLevel == RiskLevel.LOW) {
            return GateDecision.ruleHandled(StrategyDecision.UPDATE_TESTS,
                    "Low-risk change with existing tests");
        }

        // Rule 6 — all configuration changes with existing tests
        if (changeTypes != null
                && !changeTypes.isEmpty()
                && changeTypes.stream().allMatch(ct -> ct == ChangeType.CONFIGURATION_CHANGE)
                && existingTests != null && !existingTests.isEmpty()) {
            return GateDecision.ruleHandled(StrategyDecision.UPDATE_TESTS, "Config change");
        }

        // Rule 7 — high or critical risk
        if (riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL) {
            return GateDecision.needsAi("High/critical risk change");
        }

        // Rule 8 — new feature with no existing coverage
        if (changeTypes != null && changeTypes.contains(ChangeType.NEW_FEATURE)
                && (existingTests == null || existingTests.isEmpty())) {
            return GateDecision.needsAi("New feature with no existing coverage");
        }

        // Rule 9 — breaking change detected
        if (changeTypes != null && changeTypes.contains(ChangeType.BREAKING_CHANGE)) {
            return GateDecision.needsAi("Breaking change detected");
        }

        // Rule 10 — security fix
        if (changeTypes != null && changeTypes.contains(ChangeType.SECURITY_FIX)) {
            return GateDecision.needsAi("Security fix — thorough coverage needed");
        }

        // Rule 11 — new feature (with existing tests)
        if (changeTypes != null && changeTypes.contains(ChangeType.NEW_FEATURE)) {
            return GateDecision.needsAi("New feature detected");
        }

        // Default — template generation is sufficient
        return GateDecision.ruleHandled(StrategyDecision.CREATE_TESTS,
                "Standard change — template generation sufficient");
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private boolean isDocumentationOnly(List<ImpactEnvelope.ImpactedComponent> components) {
        return components.stream()
                .map(ImpactEnvelope.ImpactedComponent::getFilePath)
                .allMatch(this::isDocPath);
    }

    private boolean isDocPath(String filePath) {
        if (filePath == null) return false;
        String lower = filePath.toLowerCase();
        if (lower.contains(DOCS_PATH_SEGMENT)) return true;
        for (String ext : DOC_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }
}

