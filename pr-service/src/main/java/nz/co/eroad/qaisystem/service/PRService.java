package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.kafka.FeatureUpdatesProducer;
import nz.co.eroad.qaisystem.model.GitDiff;
import nz.co.eroad.qaisystem.model.PullRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Entry-point service for new Pull Request events.
 * Validates, enriches, and publishes PRs to the FeatureUpdatesQueue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PRService {

    private final FeatureUpdatesProducer featureUpdatesProducer;

    /**
     * Accepts a new PR (from webhook or API call), validates it,
     * assigns an ID if missing, and publishes it to Kafka.
     */
    public PullRequest processPullRequest(PullRequest pr) {
        log.info("[PRService] Received PR from '{}': '{}'",
                pr.getAuthor(), pr.getTitle());

        // ── Enrich ────────────────────────────────────────────────────────────
        PullRequest enriched = enrich(pr);

        // ── Validate ──────────────────────────────────────────────────────────
        validate(enriched);

        // ── Publish to Kafka ──────────────────────────────────────────────────
        featureUpdatesProducer.publishPullRequest(enriched);

        log.info("[PRService] PR '{}' published to FeatureUpdatesQueue",
                enriched.getPrId());

        return enriched;
    }

    /**
     * Creates a sample PR with mock diff data — useful for demos / testing
     * without a real Git webhook.
     */
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
                .diffs(buildSampleDiffs())
                .build();
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private PullRequest enrich(PullRequest pr) {
        return PullRequest.builder()
                .prId(pr.getPrId() != null ? pr.getPrId()
                        : "PR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .title(pr.getTitle())
                .description(pr.getDescription())
                .author(pr.getAuthor())
                .sourceBranch(pr.getSourceBranch())
                .targetBranch(pr.getTargetBranch() != null ? pr.getTargetBranch() : "main")
                .repositoryName(pr.getRepositoryName())
                .repositoryUrl(pr.getRepositoryUrl())
                .createdAt(pr.getCreatedAt() != null
                        ? pr.getCreatedAt() : LocalDateTime.now())
                .status(pr.getStatus() != null
                        ? pr.getStatus() : PullRequest.PrStatus.OPEN)
                .diffs(pr.getDiffs() != null ? pr.getDiffs() : new ArrayList<>())
                .rawDiffContent(pr.getRawDiffContent())
                .build();
    }

    private void validate(PullRequest pr) {
        List<String> errors = new ArrayList<>();

        if (pr.getTitle() == null || pr.getTitle().isBlank()) {
            errors.add("PR title is required");
        }
        if (pr.getAuthor() == null || pr.getAuthor().isBlank()) {
            errors.add("PR author is required");
        }
        if (pr.getRepositoryName() == null || pr.getRepositoryName().isBlank()) {
            errors.add("Repository name is required");
        }
        if ((pr.getDiffs() == null || pr.getDiffs().isEmpty())
                && (pr.getRawDiffContent() == null || pr.getRawDiffContent().isBlank())) {
            log.warn("[PRService] PR '{}' has no diffs — " +
                    "impact analysis will be minimal", pr.getPrId());
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid PullRequest: " + String.join(", ", errors));
        }
    }

    // ─── Sample data builders ──────────────────────────────────────────────────

    private List<GitDiff> buildSampleDiffs() {
        List<GitDiff> diffs = new ArrayList<>();

        // New controller file
        diffs.add(GitDiff.builder()
                .filePath("src/main/java/com/example/controller/AuthController.java")
                .oldFilePath("src/main/java/com/example/controller/AuthController.java")
                .diffType(GitDiff.DiffType.ADDED)
                .fileExtension("java")
                .isTestFile(false)
                .linesAdded(85)
                .linesDeleted(0)
                .hunks(buildSampleControllerHunks())
                .build());

        // Modified service file
        diffs.add(GitDiff.builder()
                .filePath("src/main/java/com/example/service/UserService.java")
                .oldFilePath("src/main/java/com/example/service/UserService.java")
                .diffType(GitDiff.DiffType.MODIFIED)
                .fileExtension("java")
                .isTestFile(false)
                .linesAdded(45)
                .linesDeleted(12)
                .hunks(buildSampleServiceHunks())
                .build());

        // New model file
        diffs.add(GitDiff.builder()
                .filePath("src/main/java/com/example/model/JwtToken.java")
                .oldFilePath("src/main/java/com/example/model/JwtToken.java")
                .diffType(GitDiff.DiffType.ADDED)
                .fileExtension("java")
                .isTestFile(false)
                .linesAdded(30)
                .linesDeleted(0)
                .hunks(new ArrayList<>())
                .build());

        return diffs;
    }

    private List<GitDiff.DiffHunk> buildSampleControllerHunks() {
        List<GitDiff.DiffLine> lines = new ArrayList<>();
        lines.add(makeLine(GitDiff.DiffLine.LineType.ADDED, 1,
                "import org.springframework.web.bind.annotation.RestController;"));
        lines.add(makeLine(GitDiff.DiffLine.LineType.ADDED, 2,
                "import org.springframework.web.bind.annotation.PostMapping;"));
        lines.add(makeLine(GitDiff.DiffLine.LineType.ADDED, 3,
                "@RestController"));
        lines.add(makeLine(GitDiff.DiffLine.LineType.ADDED, 4,
                "public class AuthController {"));
        lines.add(makeLine(GitDiff.DiffLine.LineType.ADDED, 5,
                "    @PostMapping(\"/auth/login\")"));
        lines.add(makeLine(GitDiff.DiffLine.LineType.ADDED, 6,
                "    public ResponseEntity<JwtToken> login(@RequestBody LoginRequest req) {"));
        lines.add(makeLine(GitDiff.DiffLine.LineType.ADDED, 7,
                "        return ResponseEntity.ok(authService.authenticate(req));"));
        lines.add(makeLine(GitDiff.DiffLine.LineType.ADDED, 8,
                "    }"));

        return List.of(GitDiff.DiffHunk.builder()
                .oldStart(0).oldCount(0)
                .newStart(1).newCount(8)
                .lines(lines)
                .build());
    }

    private List<GitDiff.DiffHunk> buildSampleServiceHunks() {
        List<GitDiff.DiffLine> lines = new ArrayList<>();
        lines.add(makeLine(GitDiff.DiffLine.LineType.ADDED, 50,
                "    public JwtToken authenticate(LoginRequest request) {"));
        lines.add(makeLine(GitDiff.DiffLine.LineType.ADDED, 51,
                "        // validate credentials"));
        lines.add(makeLine(GitDiff.DiffLine.LineType.ADDED, 52,
                "        User user = userRepository.findByEmail(request.getEmail())"));
        lines.add(makeLine(GitDiff.DiffLine.LineType.ADDED, 53,
                "                .orElseThrow(() -> new AuthException(\"User not found\"));"));
        lines.add(makeLine(GitDiff.DiffLine.LineType.REMOVED, 50,
                "    // TODO: implement authentication"));

        return List.of(GitDiff.DiffHunk.builder()
                .oldStart(48).oldCount(3)
                .newStart(48).newCount(7)
                .lines(lines)
                .build());
    }

    private GitDiff.DiffLine makeLine(GitDiff.DiffLine.LineType type,
                                      int lineNumber, String content) {
        return GitDiff.DiffLine.builder()
                .type(type)
                .lineNumber(lineNumber)
                .content(content)
                .build();
    }

    private String buildSampleDiff() {
        return """
                diff --git a/src/main/java/com/example/controller/AuthController.java \
                b/src/main/java/com/example/controller/AuthController.java
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
                diff --git a/src/main/java/com/example/service/UserService.java \
                b/src/main/java/com/example/service/UserService.java
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
