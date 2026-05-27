package nz.co.eroad.qaisystem.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestStrategy {

    private String strategyId;
    private String envelopeId;
    private String prId;
    private StrategyDecision decision;
    private String reasoning;
    private double confidenceScore;

    // For UPDATE_TESTS
    private List<String> testsToUpdate;
    private List<String> updateInstructions;

    // For CREATE_TESTS
    private List<TestRequirement> newTestRequirements;
    private List<String> testTypes;

    // For both
    private List<String> testAreasTocover;
    private String priority;

    // ── Fallback flags ────────────────────────────────────────────────────────
    /** LOW confidence (< 0.4) → trigger full regression suite */
    private boolean fullRegressionRequired;

    /** HIGH/CRITICAL risk → expand test scope beyond directly impacted areas */
    private boolean expandedScope;

    /** Extra test areas added due to scope expansion */
    private List<String> expandedAreas;

    public enum StrategyDecision { CREATE_TESTS, UPDATE_TESTS, SKIP }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestRequirement {
        private String featureName;
        private String description;
        private List<String> scenarios;
        private TestType testType;
        private String targetComponent;

        public enum TestType { API, UI, MOBILE, UNIT, INTEGRATION }
    }
}

