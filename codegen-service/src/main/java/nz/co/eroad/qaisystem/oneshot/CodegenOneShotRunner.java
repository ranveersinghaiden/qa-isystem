package nz.co.eroad.qaisystem.oneshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nz.co.eroad.qaisystem.execution.CodegenService;
import nz.co.eroad.qaisystem.github.GitHubService;
import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScriptRequest;
import nz.co.eroad.qaisystem.service.TestPrService;
import nz.co.eroad.qaisystem.state.GateState;
import nz.co.eroad.qaisystem.state.ScenarioState;
import nz.co.eroad.qaisystem.state.StateStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One-shot entry point for the codegen stage. Active only under the {@code oneshot} profile; the
 * always-on {@code TestScriptsConsumer} Kafka path is unaffected.
 *
 * <ul>
 *   <li>{@code --mode oneshot --pr-id X --scenario S [--target-dir DIR]}: load the planned
 *       {@link TestScriptRequest} JSON for scenario {@code S} from {@code scenario_state}, run
 *       {@link CodegenService#generateAndExecuteOne(TestScriptRequest, boolean, String)} with
 *       {@code openTestPr=false} so NO per-scenario PR is opened (the gather step opens one aggregate
 *       PR), consuming {@code --target-dir} for the output location, then record the
 *       {@link TestResult} — including the generated {@code test_path} — on the scenario row.</li>
 *   <li>{@code --mode gather --pr-id X}: aggregate the per-scenario results into a SINGLE test PR
 *       (recording {@code tests_pr_number}) and advance the gate to
 *       {@link GateState#AWAITING_TESTS_APPROVAL}.</li>
 * </ul>
 *
 * <p>Exit codes: {@code 0} the pod did its job (a recorded test failure is NOT a pod failure),
 * {@code 2} missing input, {@code 1} unexpected failure.
 */
@Slf4j
@Component
@Profile("oneshot")
@RequiredArgsConstructor
public class CodegenOneShotRunner implements CommandLineRunner {

    private final CodegenService codegenService;
    private final StateStore stateStore;
    private final ObjectMapper objectMapper;
    private final TestPrService testPrService;

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
            log.error("[CodegenOneShotRunner] Missing required --pr-id");
            return 2;
        }
        String prId = prIdOpt.get();

        try {
            return switch (mode) {
                case "oneshot" -> runScenario(prId, parsed.scenario().orElse(null), parsed.targetDir().orElse(null));
                case "gather" -> runGather(prId);
                default -> {
                    log.error("[CodegenOneShotRunner] Unsupported --mode '{}'", mode);
                    yield 2;
                }
            };
        } catch (Exception e) {
            log.error("[CodegenOneShotRunner] Failed for prId='{}' mode='{}': {}",
                    prId, mode, e.getMessage(), e);
            return 1;
        }
    }

    private int runScenario(String prId, String scenarioId, String targetDir) throws Exception {
        if (scenarioId == null || scenarioId.isBlank()) {
            log.error("[CodegenOneShotRunner] Missing required --scenario for oneshot mode");
            return 2;
        }

        ScenarioState planned = findScenario(prId, scenarioId);
        if (planned == null || planned.getScenario() == null || planned.getScenario().isBlank()) {
            log.error("[CodegenOneShotRunner] No planned scenario '{}' for prId='{}'", scenarioId, prId);
            return 2;
        }

        TestScriptRequest req = objectMapper.readValue(planned.getScenario(), TestScriptRequest.class);
        log.info("[CodegenOneShotRunner] Generating test for prId='{}' scenarioId='{}' targetDir='{}'",
                prId, scenarioId, targetDir);

        // One-shot: openTestPr=false (the gather step opens ONE aggregate PR) and targetDir controls
        // the generated file location. The Kafka path keeps using the 1-arg overload unchanged.
        TestResult result = codegenService.generateAndExecuteOne(req, false, targetDir);
        if (result == null) {
            // Duplicate claim (already processed by another pod) — idempotent skip.
            log.info("[CodegenOneShotRunner] Scenario '{}' already processed for prId='{}' — skipping", scenarioId, prId);
            return 0;
        }

        String status = result.isPassed() ? ScenarioState.PASSED : ScenarioState.FAILED;
        String resultJson = objectMapper.writeValueAsString(result);
        // test_path is now surfaced by the one-shot codegen overload; the result JSON also carries
        // finalScriptContent + testPath so feedback reconstruction is faithful.
        String testPath = result.getTestPath();
        stateStore.updateScenarioResult(prId, scenarioId, status, testPath, resultJson);

        log.info("[CodegenOneShotRunner] Done prId='{}' scenarioId='{}' status='{}' testPath='{}'",
                prId, scenarioId, status, testPath);
        return 0;
    }

    private int runGather(String prId) throws Exception {
        List<ScenarioState> scenarios = stateStore.scenariosFor(prId);
        long passed = scenarios.stream().filter(s -> ScenarioState.PASSED.equals(s.getStatus())).count();
        long failed = scenarios.stream().filter(s -> ScenarioState.FAILED.equals(s.getStatus())).count();
        log.info("[CodegenOneShotRunner] gather prId='{}' total={} passed={} failed={}",
                prId, scenarios.size(), passed, failed);

        // Build the aggregate file set from the per-scenario results persisted by runScenario.
        List<TestPrService.AggregateTestFile> files = new ArrayList<>();
        String prTitle = null;
        boolean titleResolved = false;
        for (ScenarioState s : scenarios) {
            if (!titleResolved && s.getScenario() != null && !s.getScenario().isBlank()) {
                prTitle = objectMapper.readValue(s.getScenario(), TestScriptRequest.class).getPrTitle();
                titleResolved = true;
            }
            if (s.getResult() == null || s.getResult().isBlank()) {
                continue;
            }
            TestResult result = objectMapper.readValue(s.getResult(), TestResult.class);
            String content = result.getFinalScriptContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            String path = (s.getTestPath() != null && !s.getTestPath().isBlank())
                    ? s.getTestPath()
                    : result.getTestPath();
            if (path == null || path.isBlank()) {
                continue;
            }
            files.add(new TestPrService.AggregateTestFile(path, content));
        }

        // Order matters: open the PR + persist its number BEFORE advancing the gate, so any failure
        // (exception) leaves the gate un-advanced and the pod can be retried. Exceptions propagate to
        // the outer try/catch (non-zero exit) — a PR-creation failure is NOT swallowed.
        if (files.isEmpty()) {
            log.warn("[CodegenOneShotRunner] gather prId='{}' — no generated test files to aggregate", prId);
        } else {
            GitHubService.GitHubPrResult pr = testPrService.createAggregateTestPr(prId, prTitle, files);
            stateStore.saveTestsPrNumber(prId, pr.prNumber());
            log.info("[CodegenOneShotRunner] Aggregate test PR #{} opened for prId='{}' ({} files)",
                    pr.prNumber(), prId, files.size());
        }

        stateStore.updateGate(prId, GateState.AWAITING_TESTS_APPROVAL);
        log.info("[CodegenOneShotRunner] gather prId='{}' gate -> '{}'", prId, GateState.AWAITING_TESTS_APPROVAL);
        return 0;
    }

    private ScenarioState findScenario(String prId, String scenarioId) {
        for (ScenarioState s : stateStore.scenariosFor(prId)) {
            if (scenarioId.equals(s.getScenarioId())) {
                return s;
            }
        }
        return null;
    }
}
