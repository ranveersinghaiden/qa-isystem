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
    private String strategyId;
    private BddType bddType;        // NEW or UPDATED

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
