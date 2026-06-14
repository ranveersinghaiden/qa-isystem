package nz.co.eroad.qaisystem.service;

import lombok.extern.slf4j.Slf4j;
import nz.co.eroad.qaisystem.agent.AiClient;
import nz.co.eroad.qaisystem.model.PullRequest;

import java.util.List;

/**
 * Compresses the textual context of a {@link PullRequest} using GitHub Copilot CLI
 * before the PR is published to Kafka.
 *
 * <h2>What is compressed</h2>
 * <ul>
 *   <li>{@code description} — PR template boilerplate, markdown checklists, screenshots,
 *       and redundant phrasing are stripped; a concise, factual summary is stored in
 *       {@code contextSummary} on the returned {@link PullRequest}.</li>
 * </ul>
 *
 * <h2>What is NOT compressed</h2>
 * <ul>
 *   <li>{@code rawDiffContent} / {@code diffs} — these are structural and are consumed
 *       deterministically by impact-service; compressing them would lose precision.</li>
 *   <li>Structured fields ({@code jiraIds}, {@code labels}, {@code products}, etc.) —
 *       these are already concise.</li>
 * </ul>
 *
 * <p>If the {@link AiClient} is {@code null} or unavailable, the original
 * {@link PullRequest} is returned unchanged (compression is best-effort).
 *
 * <p>Not annotated with {@code @Service}; created by
 * {@link nz.co.eroad.qaisystem.config.CompressionConfig}.
 */
@Slf4j
public class ContextCompressionService {

    private static final String SYSTEM_PROMPT =
            "You are a QA test-planning assistant. Compress the following GitHub PR context " +
            "into a concise, structured plain-text summary for use in automated test generation. " +
            "Focus only on: what changed, why it changed, the business area affected, and key " +
            "risk areas for QA. " +
            "Remove ALL markdown formatting, PR template checklists, screenshot placeholders, " +
            "boilerplate phrases (e.g. 'Please review', 'See description above'), and any " +
            "information that is obvious or redundant. " +
            "Output plain text only, maximum 250 words. Do not add headings or bullet points.";

    private static final String USER_PROMPT_TEMPLATE =
            "PR Title: %s%n" +
            "PR Description:%n%s%n" +
            "Labels: %s%n" +
            "Jira IDs: %s%n" +
            "Products: %s";

    private final AiClient aiClient;   // null when compression is disabled

    /** Constructs the service; pass {@code null} for a no-op (disabled) instance. */
    public ContextCompressionService(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    /** Returns {@code true} when an AI client is configured and available. */
    public boolean isEnabled() {
        return aiClient != null && aiClient.isAvailable();
    }

    /**
     * Compresses the textual context of the given PR and returns a new
     * {@link PullRequest} with the {@code contextSummary} field populated.
     *
     * <p>If compression is disabled or the AI call fails, the original PR is
     * returned unchanged.
     *
     * @param pr the enriched pull request (after diff parsing and context extraction)
     * @return PR with {@code contextSummary} set, or the original on failure
     */
    public PullRequest compress(PullRequest pr) {
        if (!isEnabled()) {
            log.debug("[ContextCompressionService] Compression disabled — passing through PR '{}'", pr.getPrId());
            return pr;
        }

        log.info("[ContextCompressionService] Compressing context for PR '{}'", pr.getPrId());

        try {
            String userPrompt = String.format(USER_PROMPT_TEMPLATE,
                    nvl(pr.getTitle()),
                    nvl(pr.getDescription()),
                    joinOrNone(pr.getLabels()),
                    joinOrNone(pr.getJiraIds()),
                    joinOrNone(pr.getProducts()));

            String summary = aiClient.complete(SYSTEM_PROMPT, userPrompt);

            if (summary == null || summary.isBlank()) {
                log.warn("[ContextCompressionService] AI returned empty summary for PR '{}' — keeping original", pr.getPrId());
                return pr;
            }

            summary = summary.strip();
            log.info("[ContextCompressionService] Compressed context for PR '{}': {} chars → {} chars",
                    pr.getPrId(),
                    pr.getDescription() != null ? pr.getDescription().length() : 0,
                    summary.length());

            return PullRequest.builder()
                    .prId(pr.getPrId()).title(pr.getTitle()).description(pr.getDescription())
                    .author(pr.getAuthor()).sourceBranch(pr.getSourceBranch())
                    .targetBranch(pr.getTargetBranch()).repositoryName(pr.getRepositoryName())
                    .repositoryUrl(pr.getRepositoryUrl()).repoOwner(pr.getRepoOwner())
                    .createdAt(pr.getCreatedAt()).status(pr.getStatus())
                    .diffs(pr.getDiffs()).rawDiffContent(pr.getRawDiffContent())
                    .jiraIds(pr.getJiraIds()).jiraLinks(pr.getJiraLinks())
                    .confluenceLinks(pr.getConfluenceLinks())
                    .labels(pr.getLabels()).changedFiles(pr.getChangedFiles())
                    .products(pr.getProducts())
                    .contextSummary(summary)
                    .build();

        } catch (Exception e) {
            log.error("[ContextCompressionService] Compression failed for PR '{}': {}", pr.getPrId(), e.getMessage(), e);
            return pr;
        }
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    private static String joinOrNone(List<String> list) {
        return (list == null || list.isEmpty()) ? "none" : String.join(", ", list);
    }
}

