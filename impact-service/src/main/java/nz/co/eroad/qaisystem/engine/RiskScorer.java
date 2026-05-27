package nz.co.eroad.qaisystem.engine;

import nz.co.eroad.qaisystem.model.GitDiff;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ChangeType;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Produces a normalised risk score (0.0 – 1.0) and a RiskLevel enum.
 *
 * Factors:
 *   1. File volume & churn
 *   2. Change type severity
 *   3. Component criticality
 *   4. Existing test coverage signal
 */
@Slf4j
@Component
public class RiskScorer {

    @Value("${aiqa.strategy.risk-threshold-high:0.7}")
    private double thresholdHigh;

    @Value("${aiqa.strategy.risk-threshold-medium:0.4}")
    private double thresholdMedium;

    // Weight constants
    private static final double W_CHURN            = 0.25;
    private static final double W_CHANGE_TYPE      = 0.30;
    private static final double W_COMPONENT        = 0.25;
    private static final double W_TEST_COVERAGE    = 0.20;

    // Per-ChangeType severity (0.0 – 1.0)
    private static final Map<ChangeType, Double> CHANGE_SEVERITY = Map.of(
            ChangeType.BREAKING_CHANGE,         1.0,
            ChangeType.SECURITY_FIX,            0.9,
            ChangeType.DATABASE_CHANGE,         0.8,
            ChangeType.API_CHANGE,              0.7,
            ChangeType.DEPENDENCY_UPDATE,       0.6,
            ChangeType.NEW_FEATURE,             0.5,
            ChangeType.BUG_FIX,                 0.5,
            ChangeType.PERFORMANCE_IMPROVEMENT, 0.4,
            ChangeType.CONFIGURATION_CHANGE,    0.3,
            ChangeType.REFACTORING,             0.3
    );

    public double score(List<GitDiff> diffs,
                        List<ChangeType> changeTypes,
                        List<ImpactEnvelope.ImpactedComponent> components) {

        double churnScore       = calculateChurnScore(diffs);
        double changeTypeScore  = calculateChangeTypeScore(changeTypes);
        double componentScore   = calculateComponentScore(components);
        double coverageScore    = calculateCoverageScore(diffs);

        double raw = (W_CHURN         * churnScore)
                + (W_CHANGE_TYPE   * changeTypeScore)
                + (W_COMPONENT     * componentScore)
                + (W_TEST_COVERAGE * coverageScore);

        double normalised = Math.min(1.0, Math.max(0.0, raw));

        log.debug("[RiskScorer] churn={} changeType={} component={} coverage={} total={}",
                String.format("%.2f", churnScore),
                String.format("%.2f", changeTypeScore),
                String.format("%.2f", componentScore),
                String.format("%.2f", coverageScore),
                String.format("%.2f", normalised));

        return normalised;
    }

    public RiskLevel toLevel(double score) {
        if (score >= 0.9)             return RiskLevel.CRITICAL;
        if (score >= thresholdHigh)   return RiskLevel.HIGH;
        if (score >= thresholdMedium) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    // ─── Factor calculations ───────────────────────────────────────────────────

    private double calculateChurnScore(List<GitDiff> diffs) {
        int totalChurn = diffs.stream()
                .mapToInt(d -> d.getLinesAdded() + d.getLinesDeleted())
                .sum();
        // 300 lines = score 1.0
        return Math.min(1.0, totalChurn / 300.0);
    }

    private double calculateChangeTypeScore(List<ChangeType> changeTypes) {
        return changeTypes.stream()
                .mapToDouble(ct -> CHANGE_SEVERITY.getOrDefault(ct, 0.5))
                .average()
                .orElse(0.5);
    }

    private double calculateComponentScore(
            List<ImpactEnvelope.ImpactedComponent> components) {

        if (components.isEmpty()) return 0.0;

        // Controllers and security components are highest risk
        double avg = components.stream()
                .mapToDouble(c -> switch (c.getType()) {
                    case CONTROLLER  -> 0.8;
                    case SERVICE     -> 0.6;
                    case REPOSITORY  -> 0.7;
                    case CONFIG      -> 0.5;
                    case MODEL       -> 0.4;
                    case TEST        -> 0.1;
                    default          -> 0.3;
                })
                .average()
                .orElse(0.3);

        // Bonus for high caller count (wide blast radius)
        OptionalDouble avgCallers = components.stream()
                .mapToInt(c -> c.getCallers().size())
                .average();
        double callerBonus = avgCallers.isPresent()
                ? Math.min(0.2, avgCallers.getAsDouble() * 0.05)
                : 0.0;

        return Math.min(1.0, avg + callerBonus);
    }

    private double calculateCoverageScore(List<GitDiff> diffs) {
        long testFiles = diffs.stream().filter(GitDiff::isTestFile).count();
        long srcFiles  = diffs.stream().filter(d -> !d.isTestFile()).count();

        if (srcFiles == 0) return 0.0;

        // If test files are missing relative to source → higher risk
        double ratio = testFiles / (double) srcFiles;
        // ratio >= 1 → low risk (0.1); ratio = 0 → high risk (0.9)
        return Math.max(0.1, 0.9 - ratio * 0.8);
    }
}
