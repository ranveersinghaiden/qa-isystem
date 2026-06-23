package nz.co.eroad.qaisystem.execution;

import nz.co.eroad.qaisystem.agent.ConductorAgentRunner;
import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.service.RepoContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * Generates test code for a single BDD scenario by delegating to the repository's
 * <b>Conductor</b> agent via {@link ConductorAgentRunner}.
 *
 * <p>This replaces the previous template-based API/UI/Mobile generators. No AI API is
 * called directly and no other agent is invoked — the Conductor agent runs inside the
 * cloned target repository working directory and reads that repository's own
 * {@code .github/agents/} instructions to produce idiomatic test code.
 *
 * <p>Only created when {@code aiqa.github.enabled=true} (it needs {@link RepoContextService}
 * to resolve the cloned repository path used as the subprocess working directory).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "aiqa.github.enabled", havingValue = "true")
public class ConductorCodeGenerator {

    private final ConductorAgentRunner conductorAgentRunner;
    private final RepoContextService   repoContextService;

    /**
     * Delegates generation of one test-code file to the Conductor agent.
     *
     * @param scenario the individual BDD scenario to implement
     * @param parent   the parent {@link BddScenario} (PR metadata, context)
     * @param testType API / UI / MOBILE
     * @return the generated test-code source produced by the Conductor agent
     */
    public String generate(BddScenario.Scenario scenario, BddScenario parent, String testType) {
        Path workingDir = repoContextService.getLocalRepoPath();
        log.info("[ConductorCodeGenerator] Delegating {} test generation to Conductor for PR '{}' scenario='{}' workingDir='{}'",
                testType, parent.getPrId(), scenario.getTitle(), workingDir);

        String prompt = buildPrompt(scenario, parent, testType);
        try {
            String code = conductorAgentRunner.delegateToConductor(prompt, workingDir);
            log.info("[ConductorCodeGenerator] Conductor returned {} chars of {} test code for PR '{}'",
                    code == null ? 0 : code.length(), testType, parent.getPrId());
            return code;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "[ConductorCodeGenerator] Interrupted while generating " + testType
                    + " test for PR '" + parent.getPrId() + "'", e);
        }
    }

    private String buildPrompt(BddScenario.Scenario scenario, BddScenario parent, String testType) {
        var given = stepsBlock("Given", scenario.getGivenSteps());
        var when  = stepsBlock("When",  scenario.getWhenSteps());
        var then  = stepsBlock("Then",  scenario.getThenSteps());
        var and   = stepsBlock("And",   scenario.getAndSteps());
        var tags  = scenario.getTags() == null ? "" : String.join(" ", scenario.getTags());
        var externalCtx = (parent.getPrContext() != null)
                ? parent.getPrContext().asPromptSection() : "";

        return """
                QA task: generate a single %s test for the BDD scenario below, following the
                conventions of THIS repository (test framework, base classes, package layout,
                naming). Gather any context you need from the repository yourself.

                Output ONLY the test source code — no explanation, no markdown fences.

                PR ID    : %s
                PR Title : %s
                Test type: %s
                Tags     : %s

                Scenario : %s
                %s
                %s
                %s
                %s
                %s
                """.formatted(
                testType,
                parent.getPrId(),
                parent.getPrTitle() == null ? "" : parent.getPrTitle(),
                testType,
                tags,
                scenario.getTitle(),
                given, when, then, and,
                externalCtx.isBlank() ? "" : "\n" + externalCtx);
    }

    private String stepsBlock(String keyword, java.util.List<String> steps) {
        if (steps == null || steps.isEmpty()) return "";
        return steps.stream()
                .map(s -> "  " + keyword + " " + s)
                .collect(Collectors.joining("\n"));
    }
}

