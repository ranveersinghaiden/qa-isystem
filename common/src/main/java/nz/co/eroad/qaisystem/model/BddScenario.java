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
public class BddScenario {

    private String scenarioId;
    private String featureTitle;
    private String featureDescription;
    private List<Scenario> scenarios;
    private String prId;
    /** Original title of the source pull request — used as the GitHub PR title. */
    private String prTitle;
    private String strategyId;
    private BddType bddType;        // NEW or UPDATED

    /**
     * External context forwarded from the source PR (Jira tickets, Confluence links,
     * labels, products). Carried here so codegen-service can embed this context in
     * generated test code without requiring a separate lookup.
     * {@code null} when no external context was collected.
     */
    private PrContext prContext;

    public enum BddType {
        NEW, UPDATED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Scenario {
        private String scenarioId;
        private String title;
        private String type;        // Scenario or Scenario Outline
        private List<String> tags;
        private List<String> givenSteps;
        private List<String> whenSteps;
        private List<String> thenSteps;
        private List<String> andSteps;
        private String testType;    // API, UI, MOBILE
        private List<ExampleRow> examples;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ExampleRow {
            private List<String> headers;
            private List<List<String>> rows;
        }
    }
}
