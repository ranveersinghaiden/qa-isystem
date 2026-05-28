package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.github.BddScenarioStore;
import nz.co.eroad.qaisystem.github.GitHubService;
import nz.co.eroad.qaisystem.github.GitHubService.GitHubPrResult;
import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScript;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Creates Pull Requests on the target test repository for human review.
 *
 * <h3>BDD Review PR flow</h3>
 * <ol>
 *   <li>Create branch  {@code qa/bdd/{prId}-{shortId}} from {@code main}.</li>
 *   <li>Commit         {@code scenarios/{prId}.feature} with the Gherkin content.</li>
 *   <li>Open PR        titled {@code [AI-QA] BDD Scenarios for PR: {prId}}.</li>
 *   <li>Register the scenario in {@link BddScenarioStore} keyed by branch name so
 *       the GitHub merge webhook can look it up and trigger codegen.</li>
 * </ol>
 *
 * <h3>Final Test Code PR flow</h3>
 * <ol>
 *   <li>Create branch  {@code qa/tests/{prId}-{shortId}} from {@code main}.</li>
 *   <li>Commit         {@code src/test/java/{package}/{fileName}} with the Java test.</li>
 *   <li>Open PR        for human review.</li>
 * </ol>
 *
 * <p>Requires GitHub to be configured ({@code TARGET_REPO_URL} + a valid token via
 * env var or the system credential store).  Throws {@link IllegalStateException} if
 * unconfigured and {@link GitHubPrException} if an API call fails — no silent fallback.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestPrService {

    private final GitHubService    gitHubService;
    private final BddScenarioStore bddScenarioStore;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ─── BDD Review PR ────────────────────────────────────────────────────────

    /**
     * Creates a BDD review PR on the target repository.
     *
     * @return GitHub PR URL
     * @throws IllegalStateException if GitHub is not configured
     * @throws GitHubPrException     if a GitHub API call fails
     */
    public String createBddPr(BddScenario scenario) {
        requireConfigured();

        String branch = "qa/bdd/" + scenario.getPrId() + "-"
                + scenario.getScenarioId().substring(0, 6);
        String title  = "[AI-QA] BDD Scenarios for PR: " + scenario.getPrId();
        String body   = buildBddPrBody(scenario);

        log.info("[TestPrService] Creating BDD PR on GitHub — branch='{}'", branch);

        if (!gitHubService.createBranch(branch, "main")) {
            throw new GitHubPrException("Failed to create branch '" + branch + "' — check logs above");
        }

        String featurePath    = "scenarios/" + scenario.getPrId() + ".feature";
        String featureContent = buildFeatureFileContent(scenario);
        if (!gitHubService.createFile(branch, featurePath, featureContent,
                "Add BDD scenarios for " + scenario.getPrId())) {
            throw new GitHubPrException("Failed to commit feature file '" + featurePath + "'");
        }

        GitHubPrResult result = gitHubService.createPullRequest(title, body, branch, "main");
        if (result == null) {
            throw new GitHubPrException("GitHub API returned null for PR creation (head=" + branch + ")");
        }

        // Register so the merge webhook can trigger codegen
        bddScenarioStore.put(branch, scenario);

        log.info("[TestPrService] BDD Review PR #{} created: {}", result.prNumber(), result.url());
        return result.url();
    }

    // ─── Final Test Code PR ───────────────────────────────────────────────────

    /**
     * Creates a final test-code review PR on the target repository.
     *
     * @return GitHub PR URL
     * @throws IllegalStateException if GitHub is not configured
     * @throws GitHubPrException     if a GitHub API call fails
     */
    public String createFinalTestPr(TestScript script, TestResult result) {
        requireConfigured();

        String branch = "qa/tests/" + script.getPrId() + "-"
                + script.getScriptId().substring(0, 6);
        String title  = buildTestPrTitle(script, result);
        String body   = buildTestPrBody(script, result);

        log.info("[TestPrService] Creating final-test PR on GitHub — branch='{}'", branch);

        if (!gitHubService.createBranch(branch, "main")) {
            throw new GitHubPrException("Failed to create branch '" + branch + "'");
        }

        String packagePath = script.getTargetPackage() != null
                ? script.getTargetPackage().replace('.', '/')
                : "nz/co/eroad/qaisystem/generated/tests";
        String filePath = "src/test/java/" + packagePath + "/" + script.getFileName();

        if (!gitHubService.createFile(branch, filePath, script.getScriptContent(),
                "Add generated tests for " + script.getPrId())) {
            throw new GitHubPrException("Failed to commit test file '" + filePath + "'");
        }

        GitHubPrResult pr = gitHubService.createPullRequest(title, body, branch, "main");
        if (pr == null) {
            throw new GitHubPrException("GitHub API returned null for PR creation (head=" + branch + ")");
        }

        log.info("[TestPrService] Final Test PR #{} created: {}", pr.prNumber(), pr.url());
        return pr.url();
    }

    // ─── Guard ────────────────────────────────────────────────────────────────

    private void requireConfigured() {
        if (!gitHubService.isConfigured()) {
            throw new IllegalStateException(
                    "GitHub is not configured. Set TARGET_REPO_URL and ensure a token is " +
                    "available via TARGET_REPO_TOKEN or the system git credential helper " +
                    "(osxkeychain / IntelliJ GitHub auth).");
        }
    }

    // ─── Content builders ─────────────────────────────────────────────────────

    private String buildFeatureFileContent(BddScenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("Feature: ").append(scenario.getFeatureTitle()).append("\n\n");
        for (BddScenario.Scenario s : scenario.getScenarios()) {
            if (s.getTags() != null && !s.getTags().isEmpty()) {
                sb.append("  ").append(String.join(" ", s.getTags())).append("\n");
            }
            sb.append("  ").append(s.getType()).append(": ").append(s.getTitle()).append("\n");
            appendSteps(sb, "Given ", s.getGivenSteps());
            appendSteps(sb, "When ",  s.getWhenSteps());
            appendSteps(sb, "Then ",  s.getThenSteps());
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
        sb.append(buildFeatureFileContent(scenario));
        sb.append("```\n\n---\n\n### \u2705 Review Checklist\n\n");
        sb.append("- [ ] Scenario titles are descriptive\n");
        sb.append("- [ ] Given/When/Then steps are clear\n");
        sb.append("- [ ] Edge cases are covered\n");
        sb.append("- [ ] Test types are appropriate (API/UI/Mobile)\n");
        sb.append("- [ ] Examples table (if present) covers boundary values\n\n");
        sb.append("> **Merge this PR** to trigger automatic test code generation.\n");
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
        if (result != null) {
            sb.append("### Execution Summary\n\n| Metric | Value |\n|--------|-------|\n");
            sb.append("| Result | ").append(result.isPassed() ? "\u2705 PASS" : "\u274C FAIL").append(" |\n");
            sb.append("| Stabilized | ").append(result.isStabilized() ? "Yes" : "No").append(" |\n");
            sb.append("| Attempts | ").append(result.getAttemptNumber()).append(" |\n");
            sb.append("| Exec Time | ").append(result.getExecutionTimeMs()).append("ms |\n\n");
            if (!result.isPassed() && result.getErrorMessage() != null) {
                sb.append("### \u26A0\uFE0F Failure Details\n\n```\n")
                  .append(result.getErrorMessage()).append("\n```\n\n");
            }
        }
        sb.append("---\n\n### Generated Test Code\n\n```java\n")
          .append(script.getScriptContent()).append("\n```\n\n");
        sb.append("---\n\n### \u2705 Review Checklist\n\n");
        sb.append("- [ ] Test logic matches the feature intent\n");
        sb.append("- [ ] Assertions are meaningful\n");
        sb.append("- [ ] No hardcoded secrets or environment values\n");
        sb.append("- [ ] Test is idempotent (can run multiple times)\n");
        sb.append("- [ ] Dependencies are correctly declared\n");
        return sb.toString();
    }

    // ─── Exception ────────────────────────────────────────────────────────────

    /** Thrown when a GitHub API call fails during PR creation. */
    public static class GitHubPrException extends RuntimeException {
        public GitHubPrException(String message) { super(message); }
    }
}
