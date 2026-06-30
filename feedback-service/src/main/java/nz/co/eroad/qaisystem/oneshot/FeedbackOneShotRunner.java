package nz.co.eroad.qaisystem.oneshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nz.co.eroad.qaisystem.agent.PrFeedbackService;
import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.PrRecord;
import nz.co.eroad.qaisystem.model.PrType;
import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScript;
import nz.co.eroad.qaisystem.model.TestScriptRequest;
import nz.co.eroad.qaisystem.state.PrHistory;
import nz.co.eroad.qaisystem.state.ScenarioState;
import nz.co.eroad.qaisystem.state.StateStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One-shot entry point for the feedback stage. Active only under the {@code oneshot} profile; the
 * always-on {@code FeedbackEventConsumer} Kafka path is unaffected.
 *
 * <p>{@code --mode oneshot --pr-id X --review-id R}: reconstruct a {@link PrRecord} from
 * {@code pr_history} + {@code scenario_state}, infer the rejection type from the gate state, and
 * route to {@link PrFeedbackService#handleBddRejection(PrRecord, int)} /
 * {@link PrFeedbackService#handleTestRejection(PrRecord, int)} exactly like the Kafka consumer.
 *
 * <p>Exit codes: {@code 0} success, {@code 2} missing/indeterminate input, {@code 1} unexpected
 * failure.
 */
@Slf4j
@Component
@Profile("oneshot")
@RequiredArgsConstructor
public class FeedbackOneShotRunner implements CommandLineRunner {

    private final PrFeedbackService feedbackService;
    private final StateStore stateStore;
    private final ObjectMapper objectMapper;

    /** Mirrors codegen's package scheme for the no-test-path fallback (e.g. {@code ...generated.tests.api}). */
    private static final String DEFAULT_PACKAGE_PREFIX = "nz.co.eroad.qaisystem.generated.tests.";

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
            log.error("[FeedbackOneShotRunner] Missing required --pr-id");
            return 2;
        }
        String prId = prIdOpt.get();

        if (!"oneshot".equals(mode)) {
            log.error("[FeedbackOneShotRunner] Unsupported --mode '{}' (expected 'oneshot')", mode);
            return 2;
        }

        // NOTE: --review-id is parsed for traceability but not consumed — the handlers fetch ALL PR
        // comments via gitHubService.getPrAllComments(prNumber), so the GitHub prNumber (not a review
        // id) is the key. Kept in the contract for forward-compat / logging.
        parsed.reviewId().ifPresent(r ->
                log.info("[FeedbackOneShotRunner] (note) --review-id='{}' not consumed by handlers", r));

        try {
            Optional<PrHistory> prOpt = stateStore.findPr(prId);
            if (prOpt.isEmpty()) {
                log.error("[FeedbackOneShotRunner] No pr_history row for prId='{}'", prId);
                return 2;
            }
            PrHistory pr = prOpt.get();

            PrType type = resolveType(pr);
            if (type == null) {
                log.error("[FeedbackOneShotRunner] Cannot determine rejection type for prId='{}' (gate='{}')",
                        prId, pr.getGateState());
                return 2;
            }
            Integer prNumber = (type == PrType.BDD) ? pr.getBddPrNumber() : pr.getTestsPrNumber();
            if (prNumber == null) {
                prNumber = pr.getPrNumber();
            }
            if (prNumber == null) {
                log.error("[FeedbackOneShotRunner] No PR number for prId='{}' type='{}'", prId, type);
                return 2;
            }

            String scenarioId = parsed.scenario().orElse(null);
            PrRecord record = PrRecord.builder()
                    .branchName(pr.getBranch())
                    .prNumber(prNumber)
                    .type(type)
                    .bddScenario(type == PrType.BDD ? reconstructBddScenario(prId) : null)
                    .testScript(type == PrType.TEST ? reconstructTestScript(prId, scenarioId) : null)
                    .build();

            log.info("[FeedbackOneShotRunner] Routing {} rejection for prId='{}' prNumber=#{}",
                    type, prId, prNumber);

            if (type == PrType.BDD) {
                feedbackService.handleBddRejection(record, prNumber);
            } else {
                feedbackService.handleTestRejection(record, prNumber);
            }
            log.info("[FeedbackOneShotRunner] Done prId='{}' type='{}'", prId, type);
            return 0;
        } catch (Exception e) {
            log.error("[FeedbackOneShotRunner] Failed for prId='{}': {}", prId, e.getMessage(), e);
            return 1;
        }
    }

    /** Infers the rejected-PR type from the persisted gate state / available PR numbers. */
    private PrType resolveType(PrHistory pr) {
        String gate = pr.getGateState();
        if (gate != null) {
            String g = gate.toLowerCase();
            if (g.contains("bdd")) {
                return PrType.BDD;
            }
            if (g.contains("test")) {
                return PrType.TEST;
            }
        }
        if (pr.getTestsPrNumber() != null) {
            return PrType.TEST;
        }
        if (pr.getBddPrNumber() != null) {
            return PrType.BDD;
        }
        return null;
    }

    /**
     * Faithfully rebuilds the original {@link BddScenario} from the planned {@code scenario_state}
     * rows (each holds a {@link TestScriptRequest} JSON written by the strategy one-shot runner).
     */
    private BddScenario reconstructBddScenario(String prId) throws Exception {
        List<ScenarioState> rows = stateStore.scenariosFor(prId);
        List<BddScenario.Scenario> scenarios = new ArrayList<>();
        String prTitle = null;
        String strategyId = null;
        String bddScenarioId = null;
        nz.co.eroad.qaisystem.model.PrContext prContext = null;
        for (ScenarioState row : rows) {
            if (row.getScenario() == null || row.getScenario().isBlank()) {
                continue;
            }
            TestScriptRequest req = objectMapper.readValue(row.getScenario(), TestScriptRequest.class);
            if (req.getScenario() != null) {
                scenarios.add(req.getScenario());
            }
            if (prTitle == null) {
                prTitle = req.getPrTitle();
            }
            if (strategyId == null) {
                strategyId = req.getStrategyId();
            }
            if (bddScenarioId == null) {
                bddScenarioId = req.getBddScenarioId();
            }
            if (prContext == null) {
                prContext = req.getPrContext();
            }
        }
        if (scenarios.isEmpty()) {
            log.warn("[FeedbackOneShotRunner] No planned scenarios to rebuild BddScenario for prId='{}'", prId);
            return null;
        }
        return BddScenario.builder()
                .prId(prId)
                .prTitle(prTitle)
                .strategyId(strategyId)
                .scenarioId(bddScenarioId)
                .scenarios(scenarios)
                .prContext(prContext)
                .build();
    }

    /**
     * Faithful {@link TestScript} reconstruction for a TEST rejection.
     *
     * <p>The codegen one-shot path now persists, per scenario, the final post-fix script content
     * ({@code finalScriptContent}) and the generated test file's repo-relative path ({@code testPath})
     * inside the stored {@link TestResult}. Reconstruction therefore returns the exact generated script
     * rather than a placeholder.
     *
     * <p>Selection honors {@code --scenario}: when {@code scenarioId} is non-blank we rebuild that
     * specific scenario's script; otherwise we rebuild a representative script from the first scenario
     * that has a stored result. Because the gather stage opens ONE aggregate test PR covering all
     * scenarios, a TEST rejection targets the {@code --scenario} row (or a representative scenario when
     * not given); regenerating the full set in a single feedback pass is a future enhancement.
     *
     * @return a faithful {@link TestScript}, or {@code null} when no usable script content is stored
     *         (the caller tolerates a {@code null} test script).
     */
    private TestScript reconstructTestScript(String prId, String scenarioId) throws Exception {
        List<ScenarioState> rows = stateStore.scenariosFor(prId);

        // Choose the row: an explicit --scenario match wins; otherwise the first row with a stored result.
        ScenarioState chosen = null;
        if (scenarioId != null && !scenarioId.isBlank()) {
            for (ScenarioState row : rows) {
                if (scenarioId.equals(row.getScenarioId())) {
                    chosen = row;
                    break;
                }
            }
        }
        if (chosen == null || chosen.getResult() == null || chosen.getResult().isBlank()) {
            for (ScenarioState row : rows) {
                if (row.getResult() != null && !row.getResult().isBlank()) {
                    chosen = row;
                    break;
                }
            }
        }
        if (chosen == null || chosen.getResult() == null || chosen.getResult().isBlank()) {
            log.warn("[FeedbackOneShotRunner] No stored results to rebuild TestScript for prId='{}'", prId);
            return null;
        }

        TestResult result = objectMapper.readValue(chosen.getResult(), TestResult.class);
        String scriptContent = result.getFinalScriptContent();
        if (scriptContent == null || scriptContent.isBlank()) {
            log.warn("[FeedbackOneShotRunner] No usable script content to rebuild TestScript for prId='{}' scenarioId='{}'",
                    prId, chosen.getScenarioId());
            return null;
        }

        TestScriptRequest req = (chosen.getScenario() != null && !chosen.getScenario().isBlank())
                ? objectMapper.readValue(chosen.getScenario(), TestScriptRequest.class)
                : null;

        String testPath = (chosen.getTestPath() != null && !chosen.getTestPath().isBlank())
                ? chosen.getTestPath()
                : result.getTestPath();
        boolean hasPath = testPath != null && !testPath.isBlank();

        TestScript.TestType testType = mapTestType(req);
        String fileName = hasPath ? basename(testPath) : fileNameFromRequest(req, testType);
        String targetPackage = hasPath
                ? targetPackageFromTestPath(testPath)
                : DEFAULT_PACKAGE_PREFIX + testType.name().toLowerCase();

        // sizes-only INFO logging — never log script content at INFO.
        log.info("[FeedbackOneShotRunner] Rebuilt TestScript prId='{}' scenarioId='{}' file='{}' ({} chars)",
                prId, chosen.getScenarioId(), fileName, scriptContent.length());

        return TestScript.builder()
                .prId(prId)
                .prTitle(req != null ? req.getPrTitle() : null)
                .bddScenarioId(req != null ? req.getBddScenarioId() : null)
                .testType(testType)
                .scriptContent(scriptContent)
                .fileName(fileName)
                .targetPackage(targetPackage)
                .status(TestScript.ScriptStatus.GENERATED)
                .build();
    }

    /** Maps the planned scenario's test type string to {@link TestScript.TestType} (defaults to API). */
    private TestScript.TestType mapTestType(TestScriptRequest req) {
        String raw = (req != null && req.getScenario() != null) ? req.getScenario().getTestType() : null;
        if (raw == null || raw.isBlank()) {
            return TestScript.TestType.API;
        }
        try {
            return TestScript.TestType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.debug("[FeedbackOneShotRunner] Unknown testType '{}' — defaulting to API", raw);
            return TestScript.TestType.API;
        }
    }

    /** Returns the final path segment (file name) of a repo-relative path. */
    private String basename(String path) {
        String trimmed = path.replaceAll("/+$", "");
        int idx = trimmed.lastIndexOf('/');
        return idx >= 0 ? trimmed.substring(idx + 1) : trimmed;
    }

    /**
     * Derives a Java package from a {@code src/test/java/...} test path, e.g.
     * {@code src/test/java/nz/co/eroad/.../api/FooApiTest.java} &rarr; {@code nz.co.eroad.....api}.
     */
    private String targetPackageFromTestPath(String testPath) {
        String trimmed = testPath.replaceAll("/+$", "");
        int lastSlash = trimmed.lastIndexOf('/');
        if (lastSlash < 0) {
            return DEFAULT_PACKAGE_PREFIX.substring(0, DEFAULT_PACKAGE_PREFIX.length() - 1);
        }
        String dir = trimmed.substring(0, lastSlash);
        String marker = "src/test/java/";
        int idx = dir.indexOf(marker);
        if (idx >= 0) {
            dir = dir.substring(idx + marker.length());
        }
        return dir.replace('/', '.');
    }

    /** Fallback file-name derivation mirroring codegen's {@code toFileName} when no test path is stored. */
    private String fileNameFromRequest(TestScriptRequest req, TestScript.TestType testType) {
        String title = (req != null && req.getScenario() != null && req.getScenario().getTitle() != null)
                ? req.getScenario().getTitle()
                : (req != null && req.getScenarioId() != null ? req.getScenarioId() : "Generated");
        String safe = title.replaceAll("[^A-Za-z0-9]", "_")
                           .replaceAll("_+", "_")
                           .replaceAll("^_|_$", "");
        String type = testType.name();
        return safe + type.charAt(0) + type.substring(1).toLowerCase() + "Test.java";
    }
}
