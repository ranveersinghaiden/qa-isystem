package nz.co.eroad.qaisystem.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImpactEnvelope {

    private String envelopeId;
    private String prId;
    private String repositoryName;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime analyzedAt;

    // ── Impact results ────────────────────────────────────────────────────────
    private List<ImpactedComponent> impactedComponents;
    private List<String> impactedModules;
    private List<ChangeType> detectedChangeTypes;
    private double overallRiskScore;
    private RiskLevel riskLevel;

    /** Per-service confidence map e.g. {"AuthService": "HIGH"} */
    private Map<String, String> serviceConfidence;

    /** Test coverage snapshot at the time of analysis */
    private CoverageReport coverageReport;

    // ── Dependency graph ──────────────────────────────────────────────────────
    private List<String> directDependencies;
    private List<String> transitiveDependencies;
    private Map<String, List<String>> dependencyGraph;

    // ── Metrics ───────────────────────────────────────────────────────────────
    private int totalFilesChanged;
    private int totalLinesAdded;
    private int totalLinesDeleted;
    private int affectedTestFiles;

    // ── Strategy hints ────────────────────────────────────────────────────────
    private List<String> existingTestFiles;
    private List<String> suggestedTestAreas;
    private String changesSummary;

    /**
     * AI-generated refinement of the deterministic analysis.
     * {@code null} when AI evaluation is disabled, not configured, or not triggered
     * (score outside the gray zone).  Never {@code null} once AI ran — even if it
     * produced no changes, it records the model + reasoning.
     */
    private AIInsight aiInsight;

    public enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

    public enum ChangeType {
        NEW_FEATURE, BUG_FIX, REFACTORING,
        CONFIGURATION_CHANGE, DEPENDENCY_UPDATE,
        API_CHANGE, DATABASE_CHANGE, SECURITY_FIX,
        PERFORMANCE_IMPROVEMENT, BREAKING_CHANGE
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImpactedComponent {
        private String componentName;
        private String filePath;
        private ComponentType type;
        private double impactScore;
        private List<String> callers;
        private List<String> callees;

        public enum ComponentType {
            SERVICE, REPOSITORY, CONTROLLER,
            UTILITY, MODEL, CONFIG, TEST
        }
    }

    /**
     * Records the outcome of the AI last-resort evaluation pass.
     *
     * <p>Fields:
     * <ul>
     *   <li>{@code applied} — {@code true} if the AI result caused score or type changes</li>
     *   <li>{@code model} — the LLM model used (e.g. "gpt-4o-mini")</li>
     *   <li>{@code originalRiskScore} — the deterministic score before AI adjustment</li>
     *   <li>{@code adjustedRiskScore} — the score after AI adjustment (clamped to ±0.15)</li>
     *   <li>{@code addedChangeTypes} — change types the AI detected but regex missed</li>
     *   <li>{@code reasoning} — 1–2 sentence explanation from the LLM</li>
     * </ul>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AIInsight {
        private boolean applied;
        private String  model;
        private double  originalRiskScore;
        private double  adjustedRiskScore;
        private List<ChangeType> addedChangeTypes;
        private String  reasoning;
    }
}
