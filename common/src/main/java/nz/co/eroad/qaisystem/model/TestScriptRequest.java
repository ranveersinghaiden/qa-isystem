package nz.co.eroad.qaisystem.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single-scenario unit of work published to {@code TestScriptsQueue}.
 *
 * <p>Replaces the previous "one message = whole {@link BddScenario}" contract. Strategy
 * fans a BDD feature out into one {@code TestScriptRequest} per inner scenario, keyed by
 * {@link #scenarioId}, so codegen-service can process scenarios in parallel across consumer
 * threads and across horizontally-scaled instances instead of looping them serially.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestScriptRequest {

    /** Source pull-request id (groups scenarios for completion tracking). */
    private String prId;

    /** Source pull-request title — used as the generated test PR title. */
    private String prTitle;

    /** Strategy decision id that produced this work. */
    private String strategyId;

    /** Id of the parent BDD feature ({@link BddScenario#getScenarioId()}). */
    private String bddScenarioId;

    /** Unique id of this individual scenario — used as the Kafka key and dedup id. */
    private String scenarioId;

    /** 0-based index of this scenario within the parent feature. */
    private int scenarioIndex;

    /** Total number of scenarios fanned out for {@link #prId} (for completion tracking). */
    private int scenarioCount;

    /** The single scenario to generate a test for. */
    private BddScenario.Scenario scenario;

    /** External PR context (Jira/Confluence/labels/products) forwarded for traceability. */
    private PrContext prContext;
}

