package nz.co.eroad.qaisystem.execution;

import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScript;
import nz.co.eroad.qaisystem.service.RepoContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Routes BDD scenarios to the appropriate test generator (API / UI / Mobile),
 * enriched with context from the target test monorepo, then hands scripts to
 * the StabilizationLoop for execution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodegenService {

    private final ApiTestRunner      apiTestRunner;
    private final UITestRunner       uiTestRunner;
    private final MobileTestRunner   mobileTestRunner;
    private final StabilizationLoop  stabilizationLoop;
    private final RepoContextService repoContextService;

    public TestResult generateAndExecute(BddScenario scenario) {
        log.info("[CodegenService] Processing scenario '{}' for PR '{}' " +
                "(repoContext API={} UI={} MOBILE={})",
                scenario.getScenarioId(), scenario.getPrId(),
                repoContextService.getContext("API").isContextAvailable(),
                repoContextService.getContext("UI").isContextAvailable(),
                repoContextService.getContext("MOBILE").isContextAvailable());

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

        RepoContext context = repoContextService.getContext(type);

        log.debug("[CodegenService] Generating {} test for '{}' context={} agentInstructions={}",
                type, scenario.getTitle(), context.isContextAvailable(),
                context.hasAgentInstructions());

        String content = switch (type) {
            case "UI"     -> uiTestRunner.generateCode(scenario, parent, context);
            case "MOBILE" -> mobileTestRunner.generateCode(scenario, parent, context);
            default       -> apiTestRunner.generateCode(scenario, parent, context);
        };

        String targetPackage = context.effectivePackage("nz.co.eroad.qaisystem.generated.tests");

        return TestScript.builder()
                .scriptId(UUID.randomUUID().toString())
                .bddScenarioId(parent.getScenarioId())
                .prId(parent.getPrId())
                .testType(TestScript.TestType.valueOf(type))
                .scriptContent(content)
                .fileName(toFileName(scenario.getTitle(), type, context))
                .targetPackage(targetPackage)
                .dependencies(resolveDependencies(type))
                .status(TestScript.ScriptStatus.GENERATED)
                .retryCount(0)
                .executionErrors(new ArrayList<>())
                .build();
    }

    private String toFileName(String title, String type, RepoContext context) {
        String safe = title.replaceAll("[^A-Za-z0-9]", "_")
                           .replaceAll("_+", "_")
                           .replaceAll("^_|_$", "");
        String convention = context.isContextAvailable()
                ? context.getTestNamingConvention() : "*Test.java";
        return convention != null && convention.startsWith("Test")
                ? "Test" + safe + ".java"
                : safe + type.charAt(0) + type.substring(1).toLowerCase() + "Test.java";
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
