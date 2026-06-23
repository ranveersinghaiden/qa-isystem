package nz.co.eroad.qaisystem.execution;

import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.ChatMessage;
import nz.co.eroad.qaisystem.model.ConversationHistory;
import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScript;
import nz.co.eroad.qaisystem.service.ConversationStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates test-code generation for each BDD scenario by delegating to the
 * repository's Conductor agent (via {@link ConductorCodeGenerator}), then hands the
 * resulting script to the {@link StabilizationLoop} for execution.
 *
 * <p>No template generation and no AI API call happen here — the Conductor agent
 * gathers any repository context it needs by itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodegenService {

    private static final String DEFAULT_PACKAGE_PREFIX = "nz.co.eroad.qaisystem.generated.tests.";

    private final ConductorCodeGenerator conductorCodeGenerator;
    private final StabilizationLoop      stabilizationLoop;
    private final ConversationStore      conversationStore;

    public TestResult generateAndExecute(BddScenario scenario) {
        log.info("[CodegenService] Processing scenario '{}' for PR '{}' — delegating test generation to Conductor agent",
                scenario.getScenarioId(), scenario.getPrId());

        List<TestResult> results = new ArrayList<>();
        for (BddScenario.Scenario s : scenario.getScenarios()) {
            TestScript script = generateScript(s, scenario);
            results.add(stabilizationLoop.execute(script));
        }

        boolean allPassed = results.stream().allMatch(TestResult::isPassed);
        return TestResult.builder()
                .resultId(UUID.randomUUID().toString())
                .scriptId(scenario.getScenarioId())
                .prId(scenario.getPrId())
                .passed(allPassed)
                .attemptNumber(1)
                .output(buildAggregateOutput(results))
                .build();
    }

    private TestScript generateScript(BddScenario.Scenario scenario, BddScenario parent) {
        String type = scenario.getTestType() != null
                ? scenario.getTestType().toUpperCase() : "API";

        log.debug("[CodegenService] Generating {} test for '{}' via Conductor agent",
                type, scenario.getTitle());

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

    private String buildAggregateOutput(List<TestResult> results) {
        long passed = results.stream().filter(TestResult::isPassed).count();
        return String.format("%d/%d scenarios passed", passed, results.size());
    }
}
