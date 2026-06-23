package nz.co.eroad.qaisystem.agent;

import nz.co.eroad.qaisystem.config.TargetRepoProperties;
import nz.co.eroad.qaisystem.context.ProductExpertContext;
import nz.co.eroad.qaisystem.execution.RepoContext;
import nz.co.eroad.qaisystem.github.GitHubService;
import nz.co.eroad.qaisystem.github.GitHubService.GitHubPrResult;
import nz.co.eroad.qaisystem.github.PrTracker;
import nz.co.eroad.qaisystem.model.PrRecord;
import nz.co.eroad.qaisystem.model.PrType;
import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.TestScript;
import nz.co.eroad.qaisystem.service.RepoContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Handles Pull Request rejection events (closed without merge) for both
 * BDD scenario PRs and test code PRs.
 *
 * <h3>BDD PR rejection flow</h3>
 * <ol>
 *   <li>Fetch all review comments from GitHub for the rejected PR.</li>
 *   <li>Ask the AI to classify the feedback:
 *       <ul>
 *         <li>Does it reveal a <em>product knowledge gap</em>?</li>
 *         <li>What specific changes to the scenarios are requested?</li>
 *       </ul>
 *   </li>
 *   <li>If a knowledge gap is identified:
 *       <ul>
 *         <li>Fetch the existing {@code productExpert/{product}/PRODUCT.md} from the repo.</li>
 *         <li>Ask the AI to append the new knowledge to the file.</li>
 *         <li>Create a PR to update the file — titled
 *             {@code [AI-QA] Product Expert Update: {product}}.</li>
 *       </ul>
 *   </li>
 *   <li>Re-generate the BDD scenarios incorporating the review feedback.</li>
 *   <li>Create a new BDD review PR and register it in {@link PrTracker}.</li>
 * </ol>
 *
 * <h3>Test code PR rejection flow</h3>
 * <ol>
 *   <li>Fetch review comments.</li>
 *   <li>Re-generate the test code with the feedback as additional context.</li>
 *   <li>Create a new test code PR and register it in {@link PrTracker}.</li>
 *   <li>If feedback reveals product knowledge gap → same product expert update as above.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrFeedbackService {

    private final GitHubService        gitHubService;
    private final ConductorAgentRunner conductorAgentRunner;
    private final PrTracker            prTracker;
    private final RepoContextService   repoContextService;
    private final TargetRepoProperties repoProps;

    /**
     * Delegates a prompt to the repository's Conductor agent via a monitored copilot
     * subprocess running in the cloned target repo directory. Returns {@code null} on
     * interruption or subprocess failure so callers can fall back to template output.
     */
    private String delegateToConductor(String prompt) {
        java.nio.file.Path workingDir = repoContextService.getLocalRepoPath();
        try {
            return conductorAgentRunner.delegateToConductor(prompt, workingDir);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[PrFeedbackService] Interrupted during Conductor delegation: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("[PrFeedbackService] Conductor delegation failed: {}", e.getMessage());
            return null;
        }
    }

    // ─── BDD rejection ────────────────────────────────────────────────────────

    /**
     * Called when a BDD review PR is closed without being merged.
     *
     * @param record     the tracker record for the rejected PR
     * @param prNumber   GitHub PR number (for fetching review comments)
     */
    public void handleBddRejection(PrRecord record, int prNumber) {
        BddScenario original = record.getBddScenario();
        log.info("[PrFeedbackService] Handling BDD PR #{} rejection — prId='{}'",
                prNumber, original.getPrId());

        // 1. Fetch review feedback
        String feedback = gitHubService.getPrAllComments(prNumber);
        if (feedback.isBlank()) {
            log.warn("[PrFeedbackService] No review feedback found for PR #{} — " +
                    "cannot improve without comments. Skipping re-generation.", prNumber);
            return;
        }
        log.info("[PrFeedbackService] Received {} chars of feedback for PR #{}", feedback.length(), prNumber);

        // 2. Load current repo context for product expert content
        RepoContext context = repoContextService.getContext("API");

        // 3. Check for product knowledge gaps and update product expert if needed
        handleProductExpertUpdate(feedback, original.getPrId(), context);

        // 4. Re-generate BDD scenarios with the feedback
        String revisedGherkin = regenerateBdd(original, feedback, context);

        // 5. Create a new BDD PR with the improved scenarios
        createRevisedBddPr(original, revisedGherkin, prNumber);
    }

    // ─── Test code rejection ──────────────────────────────────────────────────

    /**
     * Called when a final test-code PR is closed without being merged.
     *
     * @param record   the tracker record for the rejected PR
     * @param prNumber GitHub PR number (for fetching review comments)
     */
    public void handleTestRejection(PrRecord record, int prNumber) {
        TestScript original = record.getTestScript();
        log.info("[PrFeedbackService] Handling TEST PR #{} rejection — prId='{}' type='{}'",
                prNumber, original.getPrId(), original.getTestType());

        // 1. Fetch review feedback
        String feedback = gitHubService.getPrAllComments(prNumber);
        if (feedback.isBlank()) {
            log.warn("[PrFeedbackService] No review feedback found for TEST PR #{} — skipping.", prNumber);
            return;
        }

        // 2. Load repo context
        RepoContext context = repoContextService.getContext(
                original.getTestType() != null ? original.getTestType().name() : "API");

        // 3. Check for product knowledge gaps
        handleProductExpertUpdate(feedback, original.getPrId(), context);

        // 4. Re-generate the test code
        String revisedCode = regenerateTestCode(original, feedback, context);

        // 5. Create a new test code PR
        createRevisedTestPr(original, revisedCode, prNumber);
    }

    // ─── Product expert update ────────────────────────────────────────────────

    /**
     * Analyses review feedback to determine whether it reveals a product
     * knowledge gap.  If so, updates the {@code productExpert/} files in
     * the repository by opening a separate PR.
     */
    private void handleProductExpertUpdate(String feedback, String prId, RepoContext context) {
        // Ask the Conductor agent whether the feedback indicates a knowledge gap
        String classifyPrompt = """
                Review the following PR feedback for QA test scenarios.
                Determine if the reviewer is pointing out a gap in PRODUCT KNOWLEDGE (domain understanding, 
                business flows, product-specific behaviour) vs just requesting better test structure/style.
                
                Respond with EXACTLY one of:
                KNOWLEDGE_GAP: <brief description of what domain knowledge is missing>
                STYLE_ONLY: <brief reason>
                
                Feedback:
                %s
                """.formatted(feedback);

        String classification = delegateToConductor(classifyPrompt);

        if (classification == null || !classification.startsWith("KNOWLEDGE_GAP:")) {
            log.info("[PrFeedbackService] Feedback classified as style/structure — no product expert update needed");
            return;
        }

        String missingKnowledge = classification.substring("KNOWLEDGE_GAP:".length()).trim();
        log.info("[PrFeedbackService] Knowledge gap identified: {}", missingKnowledge);

        // Determine which product expert file to update
        // Prefer the first loaded product, or use a default name derived from the PR
        String productName = context.getProductExpertSections() != null
                && !context.getProductExpertSections().isEmpty()
                ? context.getProductExpertSections().keySet().iterator().next()
                : deriveProductFromPrId(prId);

        String expertFilePath = "productExpert/" + productName + "/PRODUCT.md";

        // Read existing content (may be null if file doesn't yet exist)
        String existing = gitHubService.getFileContent(expertFilePath, repoProps.getBranch());
        String existingContent = existing != null ? existing : "";

        // Ask AI to append the new knowledge
        String updatePrompt = """
                You are updating a product expert knowledge file for QA test generation.
                
                Current content of %s:
                ---
                %s
                ---
                
                Knowledge gap identified from PR review:
                %s
                
                Full review feedback:
                %s
                
                Please append a new section to the existing content that documents this knowledge.
                Keep the existing content intact. Add a clear heading for the new section.
                Return ONLY the complete updated file content (markdown format).
                """.formatted(expertFilePath, existingContent, missingKnowledge, feedback);

        String updatedContent = delegateToConductor(updatePrompt);

        if (updatedContent == null || updatedContent.isBlank()) {
            log.warn("[PrFeedbackService] AI returned empty product expert update — skipping");
            return;
        }

        // Create a branch and PR for the product expert update
        String updateBranch = "qa/product-expert/" + productName + "-" + Instant.now().getEpochSecond();
        String existingSha   = gitHubService.getFileSha(expertFilePath, repoProps.getBranch());

        if (!gitHubService.createBranch(updateBranch, repoProps.getBranch())) {
            log.error("[PrFeedbackService] Could not create branch for product expert update");
            return;
        }

        boolean committed;
        if (existingSha != null) {
            committed = gitHubService.updateFile(updateBranch, expertFilePath,
                    updatedContent, existingSha,
                    "Update product expert knowledge: " + missingKnowledge);
        } else {
            committed = gitHubService.createFile(updateBranch, expertFilePath,
                    updatedContent, "Add product expert knowledge: " + missingKnowledge);
        }

        if (!committed) {
            log.error("[PrFeedbackService] Could not commit product expert update");
            return;
        }

        String prBody = """
                ## 🧠 AI-Detected Product Knowledge Gap
                
                **Source PR:** %s
                **Product:** %s
                **Gap identified:** %s
                
                ### Context
                A QA scenario PR was rejected with feedback that revealed a gap in the product
                expert knowledge used to generate test scenarios. This PR updates the product
                expert file so future test generation is more accurate.
                
                ### Review Checklist
                - [ ] The added knowledge is accurate
                - [ ] Existing content is unchanged
                - [ ] The description is clear for future AI context use
                """.formatted(prId, productName, missingKnowledge);

        GitHubPrResult updatePr = gitHubService.createPullRequest(
                "[AI-QA] Product Expert Update: " + productName,
                prBody, updateBranch, repoProps.getBranch());

        if (updatePr != null) {
            log.info("[PrFeedbackService] Product expert update PR #{} created: {}",
                    updatePr.prNumber(), updatePr.url());
        }
    }

    // ─── BDD re-generation ────────────────────────────────────────────────────

    private String regenerateBdd(BddScenario original, String feedback, RepoContext context) {
        String userPrompt = """
                You previously generated BDD scenarios that were REJECTED by a human reviewer.

                Original feature title: %s
                Original description: %s

                Reviewer feedback:
                %s

                Please generate improved Gherkin BDD scenarios addressing all the feedback,
                following this repository's QA conventions.
                Return ONLY the Gherkin content starting with "Feature:".
                """.formatted(
                original.getFeatureTitle(),
                original.getFeatureDescription(),
                feedback);

        String revised = delegateToConductor(userPrompt);
        if (revised != null && !revised.isBlank()) {
            log.info("[PrFeedbackService] Conductor generated revised BDD for PR '{}'", original.getPrId());
            return revised;
        }

        // Fallback: add feedback as a comment to the original Gherkin
        return "# REVISED (incorporating review feedback)\n" +
               "# Original reviewer feedback:\n" +
               feedback.lines().map(l -> "# " + l).reduce("", (a, b) -> a + b + "\n") +
               "\n" +
               buildGherkinFromScenario(original);
    }


    private String buildGherkinFromScenario(BddScenario scenario) {
        StringBuilder sb = new StringBuilder("Feature: ").append(scenario.getFeatureTitle()).append("\n\n");
        for (BddScenario.Scenario s : scenario.getScenarios()) {
            if (s.getTags() != null) sb.append("  ").append(String.join(" ", s.getTags())).append("\n");
            sb.append("  ").append(s.getType()).append(": ").append(s.getTitle()).append("\n");
            appendSteps(sb, "Given ", s.getGivenSteps());
            appendSteps(sb, "When ",  s.getWhenSteps());
            appendSteps(sb, "Then ",  s.getThenSteps());
            sb.append("\n");
        }
        return sb.toString();
    }

    // ─── Test code re-generation ──────────────────────────────────────────────

    private String regenerateTestCode(TestScript original, String feedback, RepoContext context) {
        String testTypeName = original.getTestType() != null ? original.getTestType().name() : "API";
        String userPrompt = """
                You previously generated %s test code that was REJECTED by a human reviewer.

                Original file: %s
                Original code:
                ```java
                %s
                ```

                Reviewer feedback:
                %s

                Please generate improved test code addressing all the feedback,
                following this repository's test conventions.
                Return ONLY the Java code (no markdown code fences, no explanation).
                """.formatted(
                testTypeName, original.getFileName(),
                original.getScriptContent(), feedback);

        String revised = delegateToConductor(userPrompt);
        if (revised != null && !revised.isBlank()) {
            log.info("[PrFeedbackService] Conductor generated revised test code for '{}'", original.getFileName());
            return revised;
        }

        // Fallback: embed feedback as comments in the original code
        return "// REVISED — reviewer feedback:\n" +
               feedback.lines().map(l -> "// " + l).reduce("", (a, b) -> a + b + "\n") +
               "\n" + original.getScriptContent();
    }


    // ─── Revised PR creation ──────────────────────────────────────────────────

    private void createRevisedBddPr(BddScenario original, String revisedGherkin, int originalPrNumber) {
        String suffix    = UUID.randomUUID().toString().substring(0, 6);
        String branch    = "qa/bdd/" + original.getPrId() + "-rev-" + suffix;
        String featurePath = "scenarios/" + original.getPrId() + ".feature";

        if (!gitHubService.createBranch(branch, repoProps.getBranch())) {
            log.error("[PrFeedbackService] Could not create branch for revised BDD PR");
            return;
        }
        if (!gitHubService.createFile(branch, featurePath, revisedGherkin,
                "Revise BDD scenarios based on PR #" + originalPrNumber + " feedback")) {
            log.error("[PrFeedbackService] Could not commit revised feature file");
            return;
        }

        String prBody = """
                ## 🔄 Revised BDD Scenarios (after review feedback)
                
                **Source PR:** %s
                **Replaces:** PR #%d (rejected)
                
                ### Changes made
                This revision addresses the feedback from PR #%d.
                
                ### Scenarios
                ```gherkin
                %s
                ```
                
                > **Merge this PR** to trigger automatic test code generation.
                """.formatted(original.getPrId(), originalPrNumber, originalPrNumber, revisedGherkin);

        String revisedBddTitle = (original.getPrTitle() != null && !original.getPrTitle().isBlank())
                ? "[AI-QA] Revised: " + original.getPrTitle()
                : "[AI-QA] Revised BDD Scenarios for PR: " + original.getPrId();
        GitHubPrResult result = gitHubService.createPullRequest(
                revisedBddTitle,
                prBody, branch, repoProps.getBranch());

        if (result != null) {
            log.info("[PrFeedbackService] Revised BDD PR #{} created: {}", result.prNumber(), result.url());
            prTracker.trackBdd(branch, result.prNumber(), original);
        }
    }

    private void createRevisedTestPr(TestScript original, String revisedCode, int originalPrNumber) {
        String suffix  = UUID.randomUUID().toString().substring(0, 6);
        String branch  = "qa/tests/" + original.getPrId() + "-rev-" + suffix;
        String pkgPath = original.getTargetPackage() != null
                ? original.getTargetPackage().replace('.', '/')
                : "nz/co/eroad/qaisystem/generated/tests";
        String filePath = "src/test/java/" + pkgPath + "/" + original.getFileName();

        if (!gitHubService.createBranch(branch, repoProps.getBranch())) {
            log.error("[PrFeedbackService] Could not create branch for revised test PR");
            return;
        }
        if (!gitHubService.createFile(branch, filePath, revisedCode,
                "Revise test code based on PR #" + originalPrNumber + " feedback")) {
            log.error("[PrFeedbackService] Could not commit revised test code");
            return;
        }

        String prBody = """
                ## 🔄 Revised Test Code (after review feedback)
                
                **Source PR:** %s
                **File:** %s
                **Replaces:** PR #%d (rejected)
                
                This revision addresses the feedback from PR #%d.
                
                ```java
                %s
                ```
                """.formatted(original.getPrId(), original.getFileName(),
                originalPrNumber, originalPrNumber, revisedCode);

        String revisedTestTitle = (original.getPrTitle() != null && !original.getPrTitle().isBlank())
                ? "[AI-QA] Revised Tests: " + original.getPrTitle()
                : "[AI-QA] Revised Tests for PR: " + original.getPrId();
        GitHubPrResult result = gitHubService.createPullRequest(
                revisedTestTitle,
                prBody, branch, repoProps.getBranch());

        if (result != null) {
            log.info("[PrFeedbackService] Revised TEST PR #{} created: {}", result.prNumber(), result.url());
            prTracker.trackTest(branch, result.prNumber(), original);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void appendSteps(StringBuilder sb, String keyword, java.util.List<String> steps) {
        if (steps == null) return;
        for (int i = 0; i < steps.size(); i++) {
            sb.append("    ").append(i == 0 ? keyword : "  And ").append(steps.get(i)).append("\n");
        }
    }

    private String deriveProductFromPrId(String prId) {
        // e.g. "PR-PaymentService-ABC123" → "paymentsservice"
        if (prId == null || prId.isBlank()) return "general";
        return prId.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}

