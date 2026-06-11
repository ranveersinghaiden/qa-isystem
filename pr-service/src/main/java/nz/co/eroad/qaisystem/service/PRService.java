package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.kafka.FeatureUpdatesProducer;
import nz.co.eroad.qaisystem.model.GitDiff;
import nz.co.eroad.qaisystem.model.PrContext;
import nz.co.eroad.qaisystem.model.PullRequest;
import nz.co.eroad.qaisystem.parser.GitDiffParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Entry-point service for new Pull Request events.
 * Validates, enriches, parses the raw diff (when present), and publishes PRs to Kafka.
 *
 * <h2>Diff content resolution order</h2>
 * <ol>
 *   <li>Pre-parsed {@code git_diff} list already in the payload — used as-is.</li>
 *   <li>{@code raw_diff} string in the payload — parsed into {@link GitDiff} objects
 *       by {@link GitDiffParser} before the message is published to Kafka.</li>
 * </ol>
 *
 * <p>The caller (CI pipeline, Git webhook integrator, developer) is responsible for
 * capturing the diff (e.g. {@code git diff main...BRANCH}) and supplying it in the
 * payload.  No external GitHub API calls are made from this service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PRService {

    private final FeatureUpdatesProducer featureUpdatesProducer;
    private final GitDiffParser          gitDiffParser;
    private final PrContextExtractor     prContextExtractor;

    public PullRequest processPullRequest(PullRequest pr) {
        log.info("[PRService] Received PR from '{}': '{}'", pr.getAuthor(), pr.getTitle());

        PullRequest enriched = enrich(pr);
        enriched = parseDiffIfNeeded(enriched);
        validate(enriched);
        featureUpdatesProducer.publishPullRequest(enriched);

        log.info("[PRService] PR '{}' published to FeatureUpdatesQueue " +
                        "(diffsCount={}, rawDiffLen={})",
                enriched.getPrId(),
                enriched.getDiffs() != null ? enriched.getDiffs().size() : 0,
                enriched.getRawDiffContent() != null
                        ? enriched.getRawDiffContent().length() : 0);

        return enriched;
    }

    public PullRequest createSamplePullRequest() {
        return PullRequest.builder()
                .prId("PR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .title("feat: Add user authentication with JWT")
                .description("This PR adds JWT-based authentication to the user service, "
                        + "including login, token refresh, and logout endpoints.")
                .author("dev@example.com")
                .sourceBranch("feature/jwt-auth")
                .targetBranch("main")
                .repositoryName("user-service")
                .repositoryUrl("https://github.com/example/user-service")
                .createdAt(LocalDateTime.now())
                .status(PullRequest.PrStatus.OPEN)
                .rawDiffContent(buildSampleDiff())
                .build();
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    /**
     * If the enriched PR has no pre-parsed diffs but carries a {@code raw_diff}
     * string, parse it now so downstream consumers receive fully-structured data.
     */
    private PullRequest parseDiffIfNeeded(PullRequest pr) {
        boolean hasDiffs = pr.getDiffs() != null && !pr.getDiffs().isEmpty();
        boolean hasRaw   = pr.getRawDiffContent() != null && !pr.getRawDiffContent().isBlank();

        if (hasDiffs || !hasRaw) return pr;

        List<GitDiff> parsed = gitDiffParser.parse(pr.getRawDiffContent());
        log.info("[PRService] Parsed {} file diffs from raw_diff for PR '{}'",
                parsed.size(), pr.getPrId());

        return PullRequest.builder()
                .prId(pr.getPrId()).title(pr.getTitle()).description(pr.getDescription())
                .author(pr.getAuthor()).sourceBranch(pr.getSourceBranch())
                .targetBranch(pr.getTargetBranch()).repositoryName(pr.getRepositoryName())
                .repositoryUrl(pr.getRepositoryUrl()).repoOwner(pr.getRepoOwner())
                .createdAt(pr.getCreatedAt()).status(pr.getStatus())
                .diffs(parsed).rawDiffContent(pr.getRawDiffContent())
                .jiraIds(pr.getJiraIds()).jiraLinks(pr.getJiraLinks())
                .confluenceLinks(pr.getConfluenceLinks())
                .labels(pr.getLabels()).changedFiles(pr.getChangedFiles())
                .products(pr.getProducts())
                .build();
    }

    private PullRequest enrich(PullRequest pr) {
        PullRequest base = PullRequest.builder()
                .prId(pr.getPrId() != null ? pr.getPrId()
                        : deterministicPrId(pr))
                .title(pr.getTitle()).description(pr.getDescription()).author(pr.getAuthor())
                .sourceBranch(pr.getSourceBranch())
                .targetBranch(pr.getTargetBranch() != null ? pr.getTargetBranch() : "main")
                .repositoryName(pr.getRepositoryName()).repositoryUrl(pr.getRepositoryUrl())
                .repoOwner(pr.getRepoOwner())
                .createdAt(pr.getCreatedAt() != null ? pr.getCreatedAt() : LocalDateTime.now())
                .status(pr.getStatus() != null ? pr.getStatus() : PullRequest.PrStatus.OPEN)
                .diffs(pr.getDiffs() != null ? pr.getDiffs() : new ArrayList<>())
                .rawDiffContent(pr.getRawDiffContent())
                .jiraIds(pr.getJiraIds()).jiraLinks(pr.getJiraLinks())
                .confluenceLinks(pr.getConfluenceLinks())
                .labels(pr.getLabels()).changedFiles(pr.getChangedFiles())
                .products(pr.getProducts())
                .build();

        // Extract and attach all external context (Jira, Confluence, labels, products)
        PrContext ctx = prContextExtractor.extract(base);
        log.debug("[PRService] Extracted context hasContext={} for PR '{}'", ctx.hasContext(), base.getPrId());
        return base;
    }

    /**
     * Generates a deterministic PR ID from the combination of repository name,
     * source branch, and PR title. The same PR submitted multiple times always
     * gets the same ID, which lets downstream services detect re-submissions
     * and avoid generating duplicate BDD scenarios.
     *
     * <p>Falls back to a random UUID suffix only when all three key fields are blank.
     */
    static String deterministicPrId(PullRequest pr) {
        String repo   = pr.getRepositoryName() != null ? pr.getRepositoryName().trim() : "";
        String branch = pr.getSourceBranch()   != null ? pr.getSourceBranch().trim()   : "";
        String title  = pr.getTitle()           != null ? pr.getTitle().trim()          : "";
        String key    = repo + "|" + branch + "|" + title;

        if (key.equals("||")) {
            // Nothing meaningful to hash — fall back to random
            return "PR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02X", b));
            }
            return "PR-" + hex.substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java; this branch is unreachable in practice
            log.warn("[PRService] SHA-256 unavailable, falling back to random prId: {}", e.getMessage());
            return "PR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
    }

    private void validate(PullRequest pr) {        List<String> errors = new ArrayList<>();
        if (pr.getTitle() == null || pr.getTitle().isBlank())
            errors.add("PR title is required");
        if (pr.getAuthor() == null || pr.getAuthor().isBlank())
            errors.add("PR author is required");
        if (pr.getRepositoryName() == null || pr.getRepositoryName().isBlank())
            errors.add("Repository name is required");
        if (!errors.isEmpty())
            throw new IllegalArgumentException("Invalid PullRequest: " + String.join(", ", errors));

        if ((pr.getDiffs() == null || pr.getDiffs().isEmpty())
                && (pr.getRawDiffContent() == null || pr.getRawDiffContent().isBlank())) {
            log.warn("[PRService] PR '{}' has no diff content — " +
                    "impact analysis will be minimal. Include 'raw_diff' in the payload.",
                    pr.getPrId());
        }
    }

    // ─── Sample diff builder ───────────────────────────────────────────────────

    private String buildSampleDiff() {
        return """
                diff --git a/src/main/java/com/example/controller/AuthController.java b/src/main/java/com/example/controller/AuthController.java
                new file mode 100644
                --- /dev/null
                +++ b/src/main/java/com/example/controller/AuthController.java
                @@ -0,0 +1,8 @@
                +import org.springframework.web.bind.annotation.RestController;
                +import org.springframework.web.bind.annotation.PostMapping;
                +@RestController
                +public class AuthController {
                +    @PostMapping("/auth/login")
                +    public ResponseEntity<JwtToken> login(@RequestBody LoginRequest req) {
                +        return ResponseEntity.ok(authService.authenticate(req));
                +    }
                diff --git a/src/main/java/com/example/service/UserService.java b/src/main/java/com/example/service/UserService.java
                --- a/src/main/java/com/example/service/UserService.java
                +++ b/src/main/java/com/example/service/UserService.java
                @@ -48,3 +48,7 @@
                -    // TODO: implement authentication
                +    public JwtToken authenticate(LoginRequest request) {
                +        User user = userRepository.findByEmail(request.getEmail())
                +                .orElseThrow(() -> new AuthException("User not found"));
                +        return jwtService.generateToken(user);
                +    }
                """;
    }
}
