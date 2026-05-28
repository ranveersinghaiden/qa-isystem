package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.github.BddScenarioStore;
import nz.co.eroad.qaisystem.github.GitHubService;
import nz.co.eroad.qaisystem.github.GitHubService.GitHubPrResult;
import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScript;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Creates Pull Requests on the target test repository for human review.
 *
 * <h3>BDD Review PR flow</h3>
 * <ol>
 *   <li>Create branch  {@code qa/bdd/{prId}-{shortId}} from {@code main}.</li>
 *   <li>Commit         {@code scenarios/{prId}.feature} with the Gherkin content.</li>
 *   <li>Open PR        titled {@code [AI-QA] BDD Scenarios for PR: {prId}}.</li>
 *   <li>Register the scenario in {@link BddScenarioStore} keyed by branch name.</li>
 * </ol>
 * When the human merges the PR, GitHub fires a {@code pull_request} webhook to
 * {@code /api/strategy/github-webhook}, which looks up the scenario and publishes
 * it to {@code TestScriptsQueue} to kick off codegen.
 *
 * <h3>Final Test Code PR flow</h3>
 * <ol>
 *   <li>Create branch  {@code qa/tests/{prId}-{shortId}} from {@code main}.</li>
 *   <li>Commit         {@code src/test/java/{package}/{fileName}} with the Java test.</li>
 *   <li>Open PR        for human review.</li>
 * </ol>
 *
 * <p>Falls back to simulation logging when GitHub is not configured
 * ({@code TARGET_REPO_URL} or {@code TARGET_REPO_TOKEN} not set).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestPrService {

    private final ObjectMapper     objectMapper;
    private final GitHubService    gitHubService;
    private final BddScenarioStore bddScenarioStore;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ─── BDD Review PR ────────────────────────────────────────────────────────

    /**
     * Creates a PR containing the generated BDD scenarios for human review.
     *
     * @return GitHub PR URL, or a synthetic ID in simulation mode
     */
    public String createBddPr(BddScenario scenario) {
        String branch = "qa/bdd/" + scenario.getPrId() + "-"
                + scenario.getScenarioId().substring(0, 6);
        String title  = "[AI-QA] BDD Scenarios for PR: " + scenario.getPrId();
        String body   = buildBddPrBody(scenario);

        if (gitHubService.isConfigured()) {
            return createRealBddPr(scenario, branch, title, body);
        }
        return simulateBddPr(scenario, branch, title, body);
    }

    // ─── Final Test Code PR ───────────────────────────────────────────────────

    /**
     * Creates a PR containing the final executable test code for human review.
     *
     * @return GitHub PR URL, or a synthetic ID in simulation mode
     */
    public String createFinalTestPr(TestScript script, TestResult result) {
        String branch = "qa/tests/" + script.getPrId() + "-"
                + script.getScriptId().substring(0, 6);
        String title  = buildTestPrTitle(script, result);
        String body   = buildTestPrBody(script, result);

        if (gitHubService.isConfigured()) {
            return createRealTestPr(script, branch, title, body);
        }
        return simulateTestPr(script, result, branch, title, body);
    }

    // ─── Real GitHub integration ──────────────────────────────────────────────

    private String createRealBddPr(BddScenario scenario, String branch,
                                    String title, String body) {
        log.info("[TestPrService] Creating BDD PR on GitHub — branch='{}'", branch);
        try {
            if (!gitHubService.createBranch(branch, "main")) {
                log.warn("[TestPrService] Branch creation failed — falling back to simulation");
                return simulateBddPr(scenario, branch, title, body);
            }

            String featurePath    = "scenarios/" + scenario.getPrId() + ".feature";
            String featureContent = buildFeatureFileContent(scenario);
            gitHubService.createFile(branch, featurePath, featureContent,
                    "Add BDD scenarios for " + scenario.getPrId());

            GitHubPrResult result = gitHubService.createPullRequest(title, body, branch, "main");
            if (result == null) {
                log.warn("[TestPrService] PR creation failed — falling back to simulation");
                return simulateBddPr(scenario, branch, title, body);
            }

            // Register in store so the merge webhook can trigger codegen
            bddScenarioStore.put(branch, scenario);

            log.info("[TestPrService] BDD Review PR #{} created: {}", result.prNumber(), result.url());
            return result.url();

        } catch (Exception e) {
            log.error("[TestPrService] Real BDD PR failed ({}), falling back to simulation",
                    e.getMessage(), e);
            return simulateBddPr(scenario, branch, title, body);
        }
    }

    private String createRealTestPr(TestScript script, String branch,
                                     String title, String body) {
        log.info("[TestPrService] Creating final-test PR on GitHub — branch='{}'", branch);
        try {
            if (!gitHubService.createBranch(branch, "main")) {
                log.warn("[TestPrService] Branch creation failed — falling back to simulation");
                return simulateTestPr(script, null, branch, title, body);
            }

            String packagePath = script.getTargetPackage() != null
                    ? script.getTargetPackage().replace('.', '/')
                    : "nz/co/eroad/qaisystem/generated/tests";
            String filePath = "src/test/java/" + packagePath + "/" + script.getFileName();
            gitHubService.createFile(branch, filePath, script.getScriptContent(),
                    "Add generated tests for " + script.getPrId());

            GitHubPrResult result = gitHubService.createPullRequest(title, body, branch, "main");
            if (result == null) {
                log.warn("[TestPrService] PR creation failed — falling back to simulation");
                return simulateTestPr(script, null, branch, title, body);
            }

            log.info("[TestPrService] Final Test PR #{} created: {}", result.prNumber(), result.url());
            return result.url();

        } catch (Exception e) {
            log.error("[TestPrService] Real final-test PR failed ({}), falling back to simulation",
                    e.getMessage(), e);
            return simulateTestPr(script, null, branch, title, body);
        }
    }

    // ─── Simulation (no GitHub configured) ───────────────────────────────────

    private String simulateBddPr(BddScenario scenario, String branch,
                                  String title, String body) {
        String prId = "BDD-PR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        logPrCreation("BDD Review", new PrPayload(prId, title, branch, "main", body,
                scenario.getPrId(), "BDD_REVIEW", LocalDateTime.now().format(FMT)));
        // Register so the manual /approve-bdd endpoint still works in simulation mode
        bddScenarioStore.put(branch, scenario);
        return prId;
    }

    private String simulateTestPr(TestScript script, TestResult result,
                                   String branch, String title, String body) {
        String prId = "TEST-PR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        logPrCreation("Final Test Code", new PrPayload(prId, title, branch, "main", body,
                script.getPrId(), "FINAL_TEST_CODE", LocalDateTime.now().format(FMT)));
        return prId;
    }

    // ─── Content / body builders ──────────────────────────────────────────────

    /** Builds the raw Gherkin .feature file content (committed to the branch). */
    private String buildFeatureFileContent(BddScenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("Feature: ").append(scenario.getFeatureTitle()).append("\n\n");
        for (BddScenario.Scenario s : scenario.getScenarios()) {
            if (s.getTags() != null && !s.getTags().isEmpty()) {
                sb.append("  ").append(String.join(" ", s.getTags())).append("\n");
            }
            sb.append("  ").append(s.getType()).append(": ").append(s.getTitle()).append("\n");
            appendSteps(sb, "Given ", s.getGivenSteps());
            appendSteps(sb, "When ", s.getWhenSteps());
            appendSteps(sb, "Then ", s.getThenSteps());
            if (s.getAndSteps() != null) {
                s.getAndSteps().forEach(a -> sb.append("    And ").append(a).append("\n"));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void appendSteps(StringBuilder sb, String keyword, java.util.List<String> steps) {
        if (steps == null) return;
        for (int i = 0; i < steps.size(); i++) {
            sb.append("    ").append(i == 0 ? keyword : "  And ").append(steps.get(i)).append("\n");
        }
    }

    private String buildBddPrBody(BddScenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("## \uD83E\uDD16 AI-Generated BDD Scenarios\n\n");
        sb.append("**Source PR:** ").append(scenario.getPrId()).append("\n");
        sb.append("**Feature:** ").append(scenario.getFeatureTitle()).append("\n");
        sb.append("**Type:** ").append(scenario.getBddType()).append("\n");
        sb.append("**Generated At:** ").append(LocalDateTime.now().format(FMT)).append("\n\n---\n\n");
        sb.append("### Description\n").append(scenario.getFeatureDescription()).append("\n\n---\n\n");
        sb.append("### Scenarios (").append(scenario.getScenarios().size()).append(")\n\n```gherkin\n");
        sb.append("Feature: ").append(scenario.getFeatureTitle()).append("\n\n");
        for (BddScenario.Scenario s : scenario.getScenarios()) {
            if (s.getTags() != null && !s.getTags().isEmpty()) {
                sb.append("  ").append(String.join(" ", s.getTags())).append("\n");
            }
            sb.append("  ").append(s.getType()).append(": ").append(s.getTitle()).append("\n");
            appendSteps(sb, "Given ", s.getGivenSteps());
            appendSteps(sb, "When ", s.getWhenSteps());
            appendSteps(sb, "Then ", s.getThenSteps());
            if (s.getAndSteps() != null) {
                s.getAndSteps().forEach(a -> sb.append("    And ").append(a).append("\n"));
            }
            if (s.getExamples() != null && !s.getExamples().isEmpty()) {
                sb.append("\n    Examples:\n");
                for (BddScenario.Scenario.ExampleRow ex : s.getExamples()) {
                    sb.append("      | ").append(String.join(" | ", ex.getHeaders())).append(" |\n");
                    for (java.util.List<String> row : ex.getRows()) {
                        sb.append("      | ").append(String.join(" | ", row)).append(" |\n");
                    }
                }
            }
            sb.append("\n");
        }
        sb.append("```\n\n---\n\n### \u2705 Review Checklist\n\n");
        sb.append("- [ ] Scenario titles are descriptive\n");
        sb.append("- [ ] Given/When/Then steps are clear\n");
        sb.append("- [ ] Edge cases are covered\n");
        sb.append("- [ ] Test types are appropriate (API/UI/Mobile)\n");
        sb.append("- [ ] Examples table (if present) covers boundary values\n\n");
        sb.append("> **After approval**, merge this PR — the AI system will auto-generate executable test code.\n");
        return sb.toString();
    }

    private String buildTestPrTitle(TestScript script, TestResult result) {
        String status = result != null && result.isPassed()
                ? (result.isStabilized() ? "\u2705 [STABILIZED]" : "\u2705 [PASSING]")
                : "\u26A0\uFE0F [NEEDS REVIEW]";
        return status + " AI-Generated Tests for PR: " + script.getPrId();
    }

    private String buildTestPrBody(TestScript script, TestResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("## \uD83E\uDD16 AI-Generated Test Code\n\n");
        sb.append("**Source PR:** ").append(script.getPrId()).append("\n");
        sb.append("**Test Type:** ").append(script.getTestType()).append("\n");
        sb.append("**File:** ").append(script.getFileName()).append("\n");
        sb.append("**Status:** ").append(script.getStatus()).append("\n");
        sb.append("**Retries Used:** ").append(script.getRetryCount()).append(" / 3\n");
        sb.append("**Generated At:** ").append(LocalDateTime.now().format(FMT)).append("\n\n---\n\n");
        sb.append("### Execution Summary\n\n| Metric | Value |\n|--------|-------|\n");
        if (result != null) {
            sb.append("| Result | ").append(result.isPassed() ? "\u2705 PASS" : "\u274C FAIL").append(" |\n");
            sb.append("| Stabilized | ").append(result.isStabilized() ? "Yes" : "No").append(" |\n");
            sb.append("| Attempts | ").append(result.getAttemptNumber()).append(" |\n");
            sb.append("| Exec Time | ").append(result.getExecutionTimeMs()).append("ms |\n\n");
            if (!result.isPassed() && result.getErrorMessage() != null) {
                sb.append("### \u26A0\uFE0F Failure Details\n\n```\n")
                  .append(result.getErrorMessage()).append("\n```\n\n");
                if (result.getFailureReasons() != null && !result.getFailureReasons().isEmpty()) {
                    sb.append("**Failure Reasons:**\n");
                    result.getFailureReasons().forEach(r -> sb.append("- ").append(r).append("\n"));
                    sb.append("\n");
                }
            }
        }
        sb.append("---\n\n### Generated Test Code\n\n```java\n")
          .append(script.getScriptContent()).append("\n```\n\n");
        sb.append("---\n\n### \u2705 Review Checklist\n\n");
        sb.append("- [ ] Test logic matches the feature intent\n");
        sb.append("- [ ] Assertions are meaningful\n");
        sb.append("- [ ] No hardcoded secrets or environment values\n");
        sb.append("- [ ] Test is idempotent (can run multiple times)\n");
        sb.append("- [ ] Dependencies are correctly declared\n\n");
        if (result != null) {
            if (!result.isPassed()) {
                sb.append("> \u26A0\uFE0F **This test did not pass during stabilization.**\n")
                  .append("> Manual review and fixes are required before merging.\n");
            } else if (result.isStabilized()) {
                sb.append("> \u2139\uFE0F **This test was auto-stabilized** (required retry fixes).\n")
                  .append("> Please verify the applied fixes are appropriate.\n");
            }
        }
        return sb.toString();
    }

    // ─── Logging ──────────────────────────────────────────────────────────────

    private void logPrCreation(String prType, PrPayload payload) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            log.info("[TestPrService] {} PR created:\n{}", prType, json);
        } catch (JsonProcessingException e) {
            log.info("[TestPrService] {} PR created: id={} title={}",
                    prType, payload.prId(), payload.title());
        }
    }

    // ─── Inner record ─────────────────────────────────────────────────────────

    private record PrPayload(
            String prId, String title, String sourceBranch, String targetBranch,
            String body, String sourcePrId, String prType, String createdAt) {}
}
