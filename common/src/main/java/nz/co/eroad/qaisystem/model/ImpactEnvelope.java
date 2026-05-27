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
}

