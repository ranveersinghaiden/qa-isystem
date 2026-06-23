package nz.co.eroad.qaisystem.execution;

import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.ChatMessage;
import nz.co.eroad.qaisystem.model.ConversationHistory;
import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScript;
import nz.co.eroad.qaisystem.model.TestScriptRequest;
import nz.co.eroad.qaisystem.service.ConversationStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Generates and stabilises the test code for a <b>single</b> BDD scenario delivered as a
 * {@link TestScriptRequest}. Scenarios are fanned out by strategy-service so they can be processed
 * in parallel across consumer threads and horizontally-scaled instances.
 *
 * <p>Generation is delegated to the repository's Conductor agent (via {@link ConductorCodeGenerator});
 * no template generation and no AI API call happen here. A {@link CodegenProgressTracker} provides
 * idempotency (dedup on redelivery) and per-PR completion reporting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodegenService {

    private static final String DEFAULT_PACKAGE_PREFIX = "nz.co.eroad.qaisystem.generated.tests.";

    private final ConductorCodeGenerator  conductorCodeGenerator;
    private final StabilizationLoop       stabilizationLoop;
    private final ConversationStore       conversationStore;
    private final CodegenProgressTracker  progressTracker;

    /**
     * Processes one fanned-out scenario: dedup → Conductor generation → stabilisation → test PR,
     * then records PR-level completion.
     *
     * @return the {@link TestResult}, or {@code null} when the scenario was a duplicate and skipped
     */
    public TestResult generateAndExecuteOne(TestScriptRequest req) {
        String scenarioId = req.getScenarioId();

        if (!progressTracker.claimScenario(scenarioId)) {
            log.info("[CodegenService] Scenario '{}' (PR '{}') already processed — skipping duplicate",
                    scenarioId, req.getPrId());
            return null;
        }

        BddScenario.Scenario scenario = req.getScenario();
        String type = scenario.getTestType() != null ? scenario.getTestType().toUpperCase() : "API";

        log.info("[CodegenService] Generating {} test for PR '{}' scenario {}/{} '{}' — delegating to Conductor",
                type, req.getPrId(), req.getScenarioIndex() + 1, req.getScenarioCount(), scenario.getTitle());

        BddScenario parent = BddScenario.builder()
                .prId(req.getPrId())
                .prTitle(req.getPrTitle())
                .scenarioId(req.getBddScenarioId())
                .strategyId(req.getStrategyId())
                .prContext(req.getPrContext())
                .build();

        TestScript script = generateScript(scenario, parent, type);
        TestResult result = stabilizationLoop.execute(script);

        int remaining = progressTracker.completeAndRemaining(req.getPrId(), req.getScenarioCount());
        if (remaining <= 0) {
            log.info("[CodegenService] PR '{}' codegen COMPLETE — all {} scenario(s) processed",
                    req.getPrId(), req.getScenarioCount());
        } else {
            log.info("[CodegenService] PR '{}' progress — {} of {} scenario(s) remaining",
                    req.getPrId(), remaining, req.getScenarioCount());
        }
        return result;
    }

    private TestScript generateScript(BddScenario.Scenario scenario, BddScenario parent, String type) {
        String content = conductorCodeGenerator.generate(scenario, parent, type);

        // Save initial conversation so feedback-service has history context for test rejections
        if (content != null && !content.isBlank()) {
            try {
                String userTurn = "Generate " + type + " test for scenario: " + scenario.getTitle();
                var turns = List.of(ChatMessage.user(userTurn), ChatMessage.assistant(content));
                conversationStore.save(parent.getPrId() + ":test",
                        new ConversationHistory(parent.getPrId(), turns, 1, Instant.now()));
                log.debug("[CodegenService] Saved initial test conversation for PR '{}'", parent.getPrId());
            } catch (Exception e) {
                log.warn("[CodegenService] Could not save conversation history for PR '{}': {}",
                        parent.getPrId(), e.getMessage());
            }
        }

        String targetPackage = DEFAULT_PACKAGE_PREFIX + type.toLowerCase();

        return TestScript.builder()
                .scriptId(UUID.randomUUID().toString())
                .bddScenarioId(parent.getScenarioId())
                .prId(parent.getPrId())
                .prTitle(parent.getPrTitle())
                .testType(TestScript.TestType.valueOf(type))
                .scriptContent(content)
                .fileName(toFileName(scenario.getTitle(), type))
                .targetPackage(targetPackage)
                .dependencies(resolveDependencies(type))
                .status(TestScript.ScriptStatus.GENERATED)
                .retryCount(0)
                .executionErrors(new ArrayList<>())
                .build();
    }

    private String toFileName(String title, String type) {
        String safe = title.replaceAll("[^A-Za-z0-9]", "_")
                           .replaceAll("_+", "_")
                           .replaceAll("^_|_$", "");
        return safe + type.charAt(0) + type.substring(1).toLowerCase() + "Test.java";
    }

    private List<String> resolveDependencies(String type) {
        return switch (type) {
            case "UI"     -> List.of("selenium", "webdriver-manager");
            case "MOBILE" -> List.of("appium", "selenium");
            default       -> List.of("restassured", "junit5", "assertj");
        };
    }
}

