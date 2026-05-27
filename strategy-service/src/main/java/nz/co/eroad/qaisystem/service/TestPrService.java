package nz.co.eroad.qaisystem.service;

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
 * Simulates raising Pull Requests for human review.
 *
 * In production this would call:
 *   - GitHub API  : POST /repos/{owner}/{repo}/pulls
 *   - GitLab API  : POST /projects/{id}/merge_requests
 *   - Bitbucket   : POST /repositories/{workspace}/{repo}/pullrequests
 *
 * All methods log the PR payload so you can see exactly what would be sent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestPrService {

    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ─── BDD Review PR ─────────────────────────────────────────────────────────

    /**
     * Creates a PR containing the generated BDD scenarios for human review.
     * Human approves / edits before codegen runs.
     */
    public String createBddPr(BddScenario scenario) {
        String prId    = "BDD-PR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String branch  = "qa/bdd/" + scenario.getPrId() + "-" + scenario.getScenarioId()
                .substring(0, 6);
        String title   = "[AI-QA] BDD Scenarios for PR: " + scenario.getPrId();
        String body    = buildBddPrBody(scenario);

        PrPayload payload = new PrPayload(
                prId, title, branch, "main", body,
                scenario.getPrId(), "BDD_REVIEW",
                LocalDateTime.now().format(FMT)
        );

        logPrCreation("BDD Review", payload);
        return prId;
    }

    // ─── Final Test Code PR ────────────────────────────────────────────────────

    /**
     * Creates a PR containing the final executable test code for human review.
     */
    public String createFinalTestPr(TestScript script, TestResult result) {
        String prId   = "TEST-PR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String branch = "qa/tests/" + script.getPrId() + "-" + script.getScriptId()
                .substring(0, 6);
        String title  = buildTestPrTitle(script, result);
        String body   = buildTestPrBody(script, result);

        PrPayload payload = new PrPayload(
                prId, title, branch, "main", body,
                script.getPrId(), "FINAL_TEST_CODE",
                LocalDateTime.now().format(FMT)
        );

        logPrCreation("Final Test Code", payload);
        return prId;
    }

    // ─── Body builders ─────────────────────────────────────────────────────────

    private String buildBddPrBody(BddScenario scenario) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 🤖 AI-Generated BDD Scenarios\n\n");
        sb.append("**Source PR:** ").append(scenario.getPrId()).append("\n");
        sb.append("**Feature:** ").append(scenario.getFeatureTitle()).append("\n");
        sb.append("**Type:** ").append(scenario.getBddType()).append("\n");
        sb.append("**Generated At:** ").append(LocalDateTime.now().format(FMT)).append("\n\n");
        sb.append("---\n\n");
        sb.append("### Description\n");
        sb.append(scenario.getFeatureDescription()).append("\n\n");
        sb.append("---\n\n");
        sb.append("### Scenarios (").append(scenario.getScenarios().size()).append(")\n\n");

        sb.append("```gherkin\n");
        sb.append("Feature: ").append(scenario.getFeatureTitle()).append("\n\n");

        for (BddScenario.Scenario s : scenario.getScenarios()) {
            // Tags
            if (s.getTags() != null && !s.getTags().isEmpty()) {
                sb.append("  ").append(String.join(" ", s.getTags())).append("\n");
            }
            sb.append("  ").append(s.getType()).append(": ").append(s.getTitle()).append("\n");

            // Given
            if (s.getGivenSteps() != null) {
                for (int i = 0; i < s.getGivenSteps().size(); i++) {
                    sb.append("    ")
                            .append(i == 0 ? "Given " : "  And ")
                            .append(s.getGivenSteps().get(i))
                            .append("\n");
                }
            }
            // When
            if (s.getWhenSteps() != null) {
                for (int i = 0; i < s.getWhenSteps().size(); i++) {
                    sb.append("    ")
                            .append(i == 0 ? "When " : "  And ")
                            .append(s.getWhenSteps().get(i))
                            .append("\n");
                }
            }
            // Then
            if (s.getThenSteps() != null) {
                for (int i = 0; i < s.getThenSteps().size(); i++) {
                    sb.append("    ")
                            .append(i == 0 ? "Then " : "  And ")
                            .append(s.getThenSteps().get(i))
                            .append("\n");
                }
            }
            // And (extra)
            if (s.getAndSteps() != null) {
                for (String and : s.getAndSteps()) {
                    sb.append("    And ").append(and).append("\n");
                }
            }
            // Examples (Scenario Outline)
            if (s.getExamples() != null && !s.getExamples().isEmpty()) {
                sb.append("\n    Examples:\n");
                for (BddScenario.Scenario.ExampleRow ex : s.getExamples()) {
                    sb.append("      | ")
                            .append(String.join(" | ", ex.getHeaders()))
                            .append(" |\n");
                    for (java.util.List<String> row : ex.getRows()) {
                        sb.append("      | ")
                                .append(String.join(" | ", row))
                                .append(" |\n");
                    }
                }
            }
            sb.append("\n");
        }
        sb.append("```\n\n");
        sb.append("---\n\n");
        sb.append("### ✅ Review Checklist\n\n");
        sb.append("- [ ] Scenario titles are descriptive\n");
        sb.append("- [ ] Given/When/Then steps are clear\n");
        sb.append("- [ ] Edge cases are covered\n");
        sb.append("- [ ] Test types are appropriate (API/UI/Mobile)\n");
        sb.append("- [ ] Examples table (if present) covers boundary values\n\n");
        sb.append("> **After approval**, the AI system will auto-generate executable test code.\n");

        return sb.toString();
    }

    private String buildTestPrTitle(TestScript script, TestResult result) {
        String status = result.isPassed()
                ? (result.isStabilized() ? "✅ [STABILIZED]" : "✅ [PASSING]")
                : "⚠️ [NEEDS REVIEW]";
        return status + " AI-Generated Tests for PR: " + script.getPrId();
    }

    private String buildTestPrBody(TestScript script, TestResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 🤖 AI-Generated Test Code\n\n");
        sb.append("**Source PR:** ").append(script.getPrId()).append("\n");
        sb.append("**Test Type:** ").append(script.getTestType()).append("\n");
        sb.append("**File:** ").append(script.getFileName()).append("\n");
        sb.append("**Status:** ").append(script.getStatus()).append("\n");
        sb.append("**Retries Used:** ").append(script.getRetryCount()).append(" / 3\n");
        sb.append("**Generated At:** ").append(LocalDateTime.now().format(FMT)).append("\n\n");
        sb.append("---\n\n");

        // Execution summary
        sb.append("### Execution Summary\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append("| Result | ").append(result.isPassed() ? "✅ PASS" : "❌ FAIL").append(" |\n");
        sb.append("| Stabilized | ").append(result.isStabilized() ? "Yes" : "No").append(" |\n");
        sb.append("| Attempts | ").append(result.getAttemptNumber()).append(" |\n");
        sb.append("| Exec Time | ").append(result.getExecutionTimeMs()).append("ms |\n\n");

        if (!result.isPassed() && result.getErrorMessage() != null) {
            sb.append("### ⚠️ Failure Details\n\n");
            sb.append("```\n").append(result.getErrorMessage()).append("\n```\n\n");

            if (result.getFailureReasons() != null && !result.getFailureReasons().isEmpty()) {
                sb.append("**Failure Reasons:**\n");
                for (String reason : result.getFailureReasons()) {
                    sb.append("- ").append(reason).append("\n");
                }
                sb.append("\n");
            }
        }

        // Generated code
        sb.append("---\n\n");
        sb.append("### Generated Test Code\n\n");
        sb.append("```java\n");
        sb.append(script.getScriptContent());
        sb.append("\n```\n\n");
        sb.append("---\n\n");
        sb.append("### ✅ Review Checklist\n\n");
        sb.append("- [ ] Test logic matches the feature intent\n");
        sb.append("- [ ] Assertions are meaningful\n");
        sb.append("- [ ] No hardcoded secrets or environment values\n");
        sb.append("- [ ] Test is idempotent (can run multiple times)\n");
        sb.append("- [ ] Dependencies are correctly declared\n\n");

        if (!result.isPassed()) {
            sb.append("> ⚠️ **This test did not pass during stabilization.**\n");
            sb.append("> Manual review and fixes are required before merging.\n");
        } else if (result.isStabilized()) {
            sb.append("> ℹ️ **This test was auto-stabilized** (required retry fixes).\n");
            sb.append("> Please verify the applied fixes are appropriate.\n");
        }

        return sb.toString();
    }

    // ─── Logging ───────────────────────────────────────────────────────────────

    private void logPrCreation(String prType, PrPayload payload) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(payload);
            log.info("[TestPrService] {} PR created:\n{}", prType, json);
        } catch (JsonProcessingException e) {
            log.info("[TestPrService] {} PR created: id={} title={}",
                    prType, payload.prId(), payload.title());
        }
    }

    // ─── Inner record ──────────────────────────────────────────────────────────

    private record PrPayload(
            String prId,
            String title,
            String sourceBranch,
            String targetBranch,
            String body,
            String sourcePrId,
            String prType,
            String createdAt
    ) {}
}
