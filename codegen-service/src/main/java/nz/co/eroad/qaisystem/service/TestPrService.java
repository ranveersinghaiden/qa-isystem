package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.github.GitHubService;
import nz.co.eroad.qaisystem.github.GitHubService.GitHubPrResult;
import nz.co.eroad.qaisystem.github.PrTracker;
import nz.co.eroad.qaisystem.config.TargetRepoProperties;
import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScript;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    private final GitHubService        gitHubService;
    private final PrTracker            prTracker;
    private final TargetRepoProperties repoProps;

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
        String title  = buildBddPrTitle(scenario);
        String body   = buildBddPrBody(scenario);

        log.info("[TestPrService] Creating BDD PR on GitHub — branch='{}' base='{}'",
                branch, repoProps.getBranch());

        if (!gitHubService.createBranch(branch, repoProps.getBranch())) {
            throw new GitHubPrException("Failed to create branch '" + branch + "' — check logs above");
        }

        String featurePath    = "scenarios/" + scenario.getPrId() + ".feature";
        String featureContent = buildFeatureFileContent(scenario);
        if (!gitHubService.createFile(branch, featurePath, featureContent,
                "Add BDD scenarios for " + scenario.getPrId())) {
            throw new GitHubPrException("Failed to commit feature file '" + featurePath + "'");
        }

        GitHubPrResult result = gitHubService.createPullRequest(title, body, branch, repoProps.getBranch());
        if (result == null) {
            throw new GitHubPrException("GitHub API returned null for PR creation (head=" + branch + ")");
        }

        // Register so the merge/rejection webhook can route the event
        prTracker.trackBdd(branch, result.prNumber(), scenario);

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

        log.info("[TestPrService] Creating final-test PR on GitHub — branch='{}' base='{}'",
                branch, repoProps.getBranch());

        if (!gitHubService.createBranch(branch, repoProps.getBranch())) {
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

        GitHubPrResult pr = gitHubService.createPullRequest(title, body, branch, repoProps.getBranch());
        if (pr == null) {
            throw new GitHubPrException("GitHub API returned null for PR creation (head=" + branch + ")");
        }

        log.info("[TestPrService] Final Test PR #{} created: {}", pr.prNumber(), pr.url());

        // Register so the rejection webhook can trigger re-generation
        prTracker.trackTest(branch, pr.prNumber(), script);
        return pr.url();
    }

    // ─── Aggregate Test Code PR (K8s one-shot gather) ─────────────────────────

    /**
     * Repo-relative file to commit in an aggregate test PR: its path and Java source content.
     */
    public record AggregateTestFile(String filePath, String content) {}

    /**
     * Creates ONE aggregate test-code review PR committing ALL scenario test files for a source PR.
     *
     * <p>Used only by the K8s scale-to-zero one-shot gather step — the always-on Kafka path opens a
     * per-scenario PR via {@link #createFinalTestPr} instead. Additive: this method is never called
     * on the Kafka path. Unlike the per-scenario flow it does NOT register with {@code prTracker};
     * one-shot rejection routing is driven by {@code pr_history} gate state in the {@code StateStore}
     * (read by {@code FeedbackOneShotRunner}), not by {@code PrTracker}.
     *
     * @param prId     source PR id (groups the scenario files)
     * @param prTitle  optional source PR title for the PR title (falls back to {@code prId})
     * @param files    the per-scenario test files to commit (blank/duplicate paths are skipped)
     * @return the opened {@link GitHubService.GitHubPrResult}
     * @throws IllegalStateException if GitHub is not configured or any GitHub API call fails
     */
    public GitHubService.GitHubPrResult createAggregateTestPr(String prId, String prTitle,
                                                              List<AggregateTestFile> files) {
        requireConfigured();

        String branch = "qa/tests/" + prId + "-" + UUID.randomUUID().toString().substring(0, 6);

        log.info("[TestPrService] Creating aggregate-test PR on GitHub — branch='{}' base='{}' files={}",
                branch, repoProps.getBranch(), files == null ? 0 : files.size());

        if (!gitHubService.createBranch(branch, repoProps.getBranch())) {
            throw new IllegalStateException("Failed to create branch '" + branch + "'");
        }

        Set<String> seenPaths = new HashSet<>();
        int committed = 0;
        if (files != null) {
            for (AggregateTestFile file : files) {
                if (file == null
                        || file.filePath() == null || file.filePath().isBlank()
                        || file.content() == null || file.content().isBlank()) {
                    log.debug("[TestPrService] Skipping aggregate entry with blank path/content for prId='{}'", prId);
                    continue;
                }
                if (!seenPaths.add(file.filePath())) {
                    log.debug("[TestPrService] Skipping duplicate test file path '{}' for prId='{}'",
                            file.filePath(), prId);
                    continue;
                }
                if (!gitHubService.createFile(branch, file.filePath(), file.content(),
                        "Add generated tests for " + prId)) {
                    throw new IllegalStateException("Failed to commit aggregate test file '" + file.filePath() + "'");
                }
                committed++;
            }
        }

        String title = "\u26A0\uFE0F [NEEDS REVIEW] [AI-QA] "
                + (prTitle != null && !prTitle.isBlank() ? prTitle : prId);
        String body = buildAggregateTestPrBody(prId, committed);

        log.info("[TestPrService] Aggregate-test PR committing {} file(s) for prId='{}'", committed, prId);

        GitHubService.GitHubPrResult pr =
                gitHubService.createPullRequest(title, body, branch, repoProps.getBranch());
        if (pr == null) {
            throw new IllegalStateException("GitHub API returned null for PR creation (head=" + branch + ")");
        }

        log.info("[TestPrService] Aggregate Test PR #{} created: {}", pr.prNumber(), pr.url());
        return pr;
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

    /**
     * BDD review PR title: uses the original PR title when available,
     * e.g. "[AI-QA] VSF-3670: Limit fault tags to 2 with +N overflow indicator"
     */
    private String buildBddPrTitle(BddScenario scenario) {
        if (scenario.getPrTitle() != null && !scenario.getPrTitle().isBlank()) {
            return "[AI-QA] " + scenario.getPrTitle();
        }
        return "[AI-QA] BDD Scenarios for PR: " + scenario.getPrId();
    }

    private String buildTestPrTitle(TestScript script, TestResult result) {
        String status = result != null && result.isPassed()
                ? (result.isStabilized() ? "\u2705 [STABILIZED]" : "\u2705 [PASSING]")
                : "\u26A0\uFE0F [NEEDS REVIEW]";
        if (script.getPrTitle() != null && !script.getPrTitle().isBlank()) {
            return status + " [AI-QA] " + script.getPrTitle();
        }
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
        appendTestReviewChecklist(sb);
        return sb.toString();
    }

    /**
     * Body for the aggregate test PR: a short summary (file count) plus the standard review checklist.
     * Deliberately does NOT inline any test source (sizes-only) — the files live in the PR diff.
     */
    private String buildAggregateTestPrBody(String prId, int fileCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("## \uD83E\uDD16 AI-Generated Test Code\n\n");
        sb.append("**Source PR:** ").append(prId).append("\n");
        sb.append("**Generated Test Files:** ").append(fileCount).append("\n");
        sb.append("**Generated At:** ").append(LocalDateTime.now().format(FMT)).append("\n\n");
        appendTestReviewChecklist(sb);
        return sb.toString();
    }

    /** Appends the shared test-PR review checklist (used by both per-scenario and aggregate PRs). */
    private void appendTestReviewChecklist(StringBuilder sb) {
        sb.append("---\n\n### \u2705 Review Checklist\n\n");
        sb.append("- [ ] Test logic matches the feature intent\n");
        sb.append("- [ ] Assertions are meaningful\n");
        sb.append("- [ ] No hardcoded secrets or environment values\n");
        sb.append("- [ ] Test is idempotent (can run multiple times)\n");
        sb.append("- [ ] Dependencies are correctly declared\n");
    }

    // ─── Exception ────────────────────────────────────────────────────────────

    /** Thrown when a GitHub API call fails during PR creation. */
    public static class GitHubPrException extends RuntimeException {
        public GitHubPrException(String message) { super(message); }
    }
}
