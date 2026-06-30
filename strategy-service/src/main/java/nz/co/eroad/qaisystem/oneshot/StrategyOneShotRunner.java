package nz.co.eroad.qaisystem.oneshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nz.co.eroad.qaisystem.agent.StrategyAgent;
import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import nz.co.eroad.qaisystem.model.TestScriptRequest;
import nz.co.eroad.qaisystem.state.GateState;
import nz.co.eroad.qaisystem.state.PrHistory;
import nz.co.eroad.qaisystem.state.ScenarioState;
import nz.co.eroad.qaisystem.state.StateStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One-shot entry point for the strategy stage. Active only under the {@code oneshot} profile; the
 * always-on {@code ImpactResultsConsumer} Kafka path is unaffected.
 *
 * <ul>
 *   <li>{@code --mode oneshot --pr-id X}: load the stored {@code impact_envelope}, run
 *       {@link StrategyAgent#decideAndPlan(ImpactEnvelope)} (mirrors the Kafka consumer's
 *       {@code strategyAgent.decide(...)} plus surfaces the generated {@link BddScenario}), persist
 *       the strategy JSON, advance the gate to {@link GateState#AWAITING_BDD_APPROVAL}, and fan the
 *       scenarios out into {@code scenario_state} (status {@code planned}) — each row stores a fully
 *       built {@link TestScriptRequest} JSON so the codegen pod can consume it directly.</li>
 *   <li>{@code --mode list-scenarios --pr-id X --per-pod N}: print the GitHub Actions dynamic-matrix
 *       JSON ({@code {"include":[{"id":"S1"},{"id":"S2"}]}}) to stdout and exit. With {@code N>1},
 *       {@code N} scenario ids are comma-joined per matrix cell ({@code {"id":"S1,S2"}}).</li>
 * </ul>
 *
 * <p>Exit codes: {@code 0} success, {@code 2} missing input, {@code 1} unexpected failure.
 */
@Slf4j
@Component
@Profile("oneshot")
@RequiredArgsConstructor
public class StrategyOneShotRunner implements CommandLineRunner {

    private final StrategyAgent strategyAgent;
    private final StateStore stateStore;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) {
        System.exit(execute(args));
    }

    /** Testable core: returns the process exit code without calling {@link System#exit(int)}. */
    int execute(String[] args) {
        OneShotArgs parsed = OneShotArgs.parse(args);
        String mode = parsed.mode();
        Optional<String> prIdOpt = parsed.prId();

        if (prIdOpt.isEmpty()) {
            log.error("[StrategyOneShotRunner] Missing required --pr-id");
            return 2;
        }
        String prId = prIdOpt.get();

        try {
            return switch (mode) {
                case "oneshot" -> runOneShot(prId);
                case "list-scenarios" -> runListScenarios(prId, parsed.perPod());
                default -> {
                    log.error("[StrategyOneShotRunner] Unsupported --mode '{}'", mode);
                    yield 2;
                }
            };
        } catch (Exception e) {
            log.error("[StrategyOneShotRunner] Failed for prId='{}' mode='{}': {}",
                    prId, mode, e.getMessage(), e);
            return 1;
        }
    }

    private int runOneShot(String prId) throws Exception {
        Optional<PrHistory> prOpt = stateStore.findPr(prId);
        if (prOpt.isEmpty()) {
            log.error("[StrategyOneShotRunner] No pr_history row for prId='{}'", prId);
            return 2;
        }
        String envelopeJson = prOpt.get().getImpactEnvelope();
        if (envelopeJson == null || envelopeJson.isBlank()) {
            log.error("[StrategyOneShotRunner] No impact_envelope stored for prId='{}'", prId);
            return 2;
        }

        ImpactEnvelope envelope = objectMapper.readValue(envelopeJson, ImpactEnvelope.class);
        log.info("[StrategyOneShotRunner] Deciding strategy for prId='{}' ({} byte envelope)",
                prId, envelopeJson.length());

        StrategyAgent.StrategyPlan plan = strategyAgent.decideAndPlan(envelope);

        stateStore.saveStrategy(prId, objectMapper.writeValueAsString(plan.strategy()));
        stateStore.updateGate(prId, GateState.AWAITING_BDD_APPROVAL);

        int planned = fanOutScenarios(prId, plan.bddScenario());
        log.info("[StrategyOneShotRunner] Done prId='{}' decision='{}' plannedScenarios={}",
                prId, plan.strategy() == null ? null : plan.strategy().getDecision(), planned);
        return 0;
    }

    /**
     * Mirrors {@code TestScriptsProducer.publishScenarioRequests} but persists each request to
     * {@code scenario_state} (status {@code planned}) instead of publishing to Kafka.
     *
     * @return number of scenarios planned
     */
    private int fanOutScenarios(String prId, BddScenario bdd) throws Exception {
        if (bdd == null || bdd.getScenarios() == null || bdd.getScenarios().isEmpty()) {
            log.info("[StrategyOneShotRunner] No scenarios to plan for prId='{}'", prId);
            return 0;
        }
        List<BddScenario.Scenario> scenarios = bdd.getScenarios();
        int count = scenarios.size();
        for (int i = 0; i < count; i++) {
            BddScenario.Scenario s = scenarios.get(i);
            String scenarioId = (s.getScenarioId() != null && !s.getScenarioId().isBlank())
                    ? s.getScenarioId() : UUID.randomUUID().toString();

            TestScriptRequest req = TestScriptRequest.builder()
                    .prId(bdd.getPrId())
                    .prTitle(bdd.getPrTitle())
                    .strategyId(bdd.getStrategyId())
                    .bddScenarioId(bdd.getScenarioId())
                    .scenarioId(scenarioId)
                    .scenarioIndex(i)
                    .scenarioCount(count)
                    .scenario(s)
                    .prContext(bdd.getPrContext())
                    .build();

            stateStore.upsertScenario(ScenarioState.builder()
                    .prId(prId)
                    .scenarioId(scenarioId)
                    .status(ScenarioState.PLANNED)
                    .attempts(0)
                    .scenario(objectMapper.writeValueAsString(req))
                    .build());
        }
        log.info("[StrategyOneShotRunner] Planned {} scenario(s) for prId='{}'", count, prId);
        return count;
    }

    private int runListScenarios(String prId, int perPod) throws Exception {
        String matrix = buildMatrixJson(prId, perPod);
        // CONTRACT: stdout carries ONLY this matrix JSON (logs go to stderr via logback-oneshot.xml).
        System.out.println(matrix);
        return 0;
    }

    /**
     * Builds the GitHub Actions dynamic-matrix JSON: {@code {"include":[{"id":"S1"},{"id":"S2"}]}}.
     * With {@code perPod > 1}, scenario ids are grouped {@code perPod} per cell and comma-joined.
     * Package-visible and side-effect-free (other than the StateStore read) so tests can assert the
     * exact string without capturing stdout.
     */
    String buildMatrixJson(String prId, int perPod) throws Exception {
        int group = Math.max(perPod, 1);
        List<String> ids = new ArrayList<>();
        for (ScenarioState s : stateStore.scenariosFor(prId)) {
            ids.add(s.getScenarioId());
        }

        List<String> cells = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += group) {
            int end = Math.min(i + group, ids.size());
            cells.add(String.join(",", ids.subList(i, end)));
        }

        StringBuilder sb = new StringBuilder("{\"include\":[");
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            // objectMapper quotes + escapes the id value safely.
            sb.append("{\"id\":").append(objectMapper.writeValueAsString(cells.get(i))).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }
}
