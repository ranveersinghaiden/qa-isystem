package nz.co.eroad.qaisystem.github;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.config.TargetRepoProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PostConstruct;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the GitHub REST API v3.
 *
 * <h3>Token resolution — priority order</h3>
 * <ol>
 *   <li><b>Explicit env var</b> — {@code TARGET_REPO_TOKEN} set in the environment
 *       (recommended for CI / production).</li>
 *   <li><b>System credential store</b> — when no explicit token is present,
 *       {@code git credential fill} is used to retrieve whatever token the local
 *       git credential helper holds for {@code https://github.com}.
 *       On macOS this is <em>osxkeychain</em>, which is the same store that
 *       IntelliJ writes to when you authorise it with GitHub — so local dev works
 *       with zero extra configuration as long as IntelliJ is already logged in.</li>
 *   <li><b>No auth</b> — if neither source yields a token the service falls back to
 *       unauthenticated requests (public repos only) and simulation-mode logging.</li>
 * </ol>
 *
 * <p>Graceful fallback: every public method returns {@code null} / no-ops when the
 * service is not configured (no URL) or the repo is private and no token is available.
 */
@Slf4j
@Service
public class GitHubService {

    private static final String GITHUB_API_BASE = "https://api.github.com";

    private final RestClient   restClient;
    private final ObjectMapper objectMapper;

    /** Parsed from {@code TARGET_REPO_URL} — {@code null} when URL is blank or unparseable. */
    private final String owner;
    private final String repo;

    /**
     * Resolved token (may come from env var OR the system credential store).
     * {@code null} if no token could be found — unauthenticated / simulation mode.
     */
    private final String resolvedToken;

    public GitHubService(TargetRepoProperties repoProps, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        String[] parsed = parseOwnerRepo(repoProps.getUrl());
        this.owner = parsed[0];
        this.repo  = parsed[1];

        // ── Token resolution ────────────────────────────────────────────────
        this.resolvedToken = resolveToken(repoProps);

        // ── Build RestClient ────────────────────────────────────────────────
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(GITHUB_API_BASE)
                .defaultHeader("Accept",               "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28");

        if (resolvedToken != null) {
            builder.defaultHeader("Authorization", "Bearer " + resolvedToken);
        }
        this.restClient = builder.build();

        // ── Startup log ─────────────────────────────────────────────────────
        if (owner == null) {
            log.info("[GitHubService] TARGET_REPO_URL not set — GitHub PR creation disabled");
        } else if (resolvedToken != null) {
            log.info("[GitHubService] Ready for {}/{} (token source: {})",
                    owner, repo, tokenSource(repoProps));
        } else {
            log.warn("[GitHubService] URL configured ({}/{}) but no token resolved yet — "
                    + "will validate on startup completion", owner, repo);
        }
    }

    /**
     * Startup validation: if a repository URL is configured but no token could be
     * resolved (neither {@code TARGET_REPO_TOKEN} nor the git credential helper),
     * the service refuses to start with a clear error message.
     *
     * <p>If no URL is configured at all, the check is skipped — the service starts
     * without GitHub integration (PR creation will throw at runtime if invoked).
     */
    @PostConstruct
    public void validateConfiguration() {
        if (owner != null && repo != null && resolvedToken == null) {
            throw new IllegalStateException(
                    "[GitHubService] TARGET_REPO_URL is set (" + repoWebUrl() + ") but no " +
                    "GitHub token could be resolved. " +
                    "Fix one of the following:\n" +
                    "  1. Set TARGET_REPO_TOKEN=ghp_... in your environment\n" +
                    "  2. Ensure IntelliJ is connected to GitHub " +
                    "(Settings → Version Control → GitHub) so osxkeychain holds the token\n" +
                    "  3. Run: git credential fill <<< 'protocol=https\\nhost=github.com\\n' " +
                    "to verify the credential helper works");
        }

        // ── Live connectivity check ─────────────────────────────────────────
        if (owner == null || resolvedToken == null) return;

        try {
            // 1. Who does this token authenticate as?
            String meBody = restClient.get()
                    .uri("/user")
                    .retrieve()
                    .body(String.class);
            Map<String, Object> me = objectMapper.readValue(meBody, new TypeReference<>() {});
            log.info("[GitHubService] Token authenticated as: {} (login={})",
                    me.get("name"), me.get("login"));

            // 2. Can we reach the target repo?
            String repoBody = restClient.get()
                    .uri("/repos/{owner}/{repo}", owner, repo)
                    .retrieve()
                    .body(String.class);
            Map<String, Object> repoInfo = objectMapper.readValue(repoBody, new TypeReference<>() {});
            log.info("[GitHubService] Repo confirmed: {} (private={}, default_branch={})",
                    repoInfo.get("full_name"), repoInfo.get("private"), repoInfo.get("default_branch"));

            String defaultBranch = (String) repoInfo.get("default_branch");
            if (defaultBranch != null && !defaultBranch.equals("main")) {
                log.warn("[GitHubService] ⚠️  Repo default branch is '{}', not 'main'. " +
                         "Update application.yaml: aiqa.target-repo.branch: {}",
                         defaultBranch, defaultBranch);
            }

        } catch (Exception e) {
            log.error("[GitHubService] ⚠️  Startup connectivity check FAILED for {}/{}: {} — " +
                      "owner/repo in TARGET_REPO_URL may be wrong, or the token lacks repo access. " +
                      "GitHub returns 404 for private repos when the token has no permission.",
                      owner, repo, e.getMessage());
        }
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when a valid URL and a resolved token are both available —
     * i.e. real GitHub API calls can be made.
     */
    public boolean isConfigured() {
        return owner != null && repo != null && resolvedToken != null;
    }

    /**
     * Returns the HTTPS URL of the target repository,
     * e.g. {@code https://github.com/org/repo}.
     */
    public String repoWebUrl() {
        return "https://github.com/" + owner + "/" + repo;
    }

    /**
     * Creates a new branch from the tip of {@code baseBranch}.
     *
     * @return {@code true} on success or when the branch already exists
     */
    public boolean createBranch(String newBranchName, String baseBranch) {
        if (!isConfigured()) return false;
        try {
            String sha = getBranchSha(baseBranch);
            if (sha == null) {
                log.error("[GitHubService] Could not resolve SHA for branch '{}' " +
                        "— check that the branch exists and the token has repo access", baseBranch);
                return false;
            }
            restClient.post()
                    .uri("/repos/{owner}/{repo}/git/refs", owner, repo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("ref", "refs/heads/" + newBranchName, "sha", sha))
                    .retrieve()
                    .toBodilessEntity();

            log.info("[GitHubService] Branch '{}' created from '{}' (sha={})",
                    newBranchName, baseBranch, sha.substring(0, 7));
            return true;

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 422) {
                log.warn("[GitHubService] Branch '{}' already exists — continuing", newBranchName);
                return true;
            }
            log.error("[GitHubService] Failed to create branch '{}': {}",
                    newBranchName, e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("[GitHubService] Failed to create branch '{}': {}",
                    newBranchName, e.getMessage());
            return false;
        }
    }

    /**
     * Creates (or updates) a file on the given branch via a single commit.
     *
     * @param branch        target branch name
     * @param filePath      path inside the repo, e.g. {@code scenarios/PR-123.feature}
     * @param content       raw file content (UTF-8)
     * @param commitMessage commit message
     * @return {@code true} on success
     */
    public boolean createFile(String branch, String filePath,
                               String content, String commitMessage) {
        if (!isConfigured()) return false;
        try {
            String encoded = Base64.getEncoder()
                    .encodeToString(content.getBytes(StandardCharsets.UTF_8));

            // Inline filePath into the URI string — same reason as getRef():
            // a template variable would encode '/' to '%2F', breaking the GitHub API.
            restClient.put()
                    .uri("/repos/{owner}/{repo}/contents/" + filePath, owner, repo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "message", commitMessage,
                            "content", encoded,
                            "branch",  branch
                    ))
                    .retrieve()
                    .toBodilessEntity();

            log.info("[GitHubService] File '{}' committed to branch '{}'", filePath, branch);
            return true;

        } catch (Exception e) {
            log.error("[GitHubService] Failed to create file '{}' on '{}': {}",
                    filePath, branch, e.getMessage());
            return false;
        }
    }

    /**
     * Opens a Pull Request on the target repository.
     *
     * @return {@link GitHubPrResult} with PR number and URL, or {@code null} on failure
     */
    public GitHubPrResult createPullRequest(String title, String body,
                                             String head, String base) {
        if (!isConfigured()) return null;
        try {
            String responseBody = restClient.post()
                    .uri("/repos/{owner}/{repo}/pulls", owner, repo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "title", title,
                            "body",  body,
                            "head",  head,
                            "base",  base,
                            "draft", false
                    ))
                    .retrieve()
                    .body(String.class);

            Map<String, Object> map = objectMapper.readValue(
                    responseBody, new TypeReference<>() {});

            int    prNumber = ((Number) map.get("number")).intValue();
            String prUrl    = (String)  map.get("html_url");

            log.info("[GitHubService] PR #{} created: {}", prNumber, prUrl);
            return new GitHubPrResult(prNumber, prUrl, head);

        } catch (Exception e) {
            log.error("[GitHubService] Failed to create PR (head={}): {}", head, e.getMessage());
            return null;
        }
    }

    // ─── Token resolution ─────────────────────────────────────────────────────

    /**
     * Resolves the GitHub API token using the following priority:
     * <ol>
     *   <li>Explicit {@code TARGET_REPO_TOKEN} env var / {@code auth.token} property.</li>
     *   <li>System credential store via {@code git credential fill} — works transparently
     *       with osxkeychain (macOS / IntelliJ), Windows Credential Manager, and any other
     *       git credential helper the developer has configured.</li>
     * </ol>
     *
     * @return the resolved token, or {@code null} if none is available
     */
    private static String resolveToken(TargetRepoProperties props) {
        // 1. Explicit token takes priority (CI, production, or dev with TOKEN set)
        String explicit = props.getAuth().getToken();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }

        // SSH auth doesn't translate to an API token — skip credential helper
        if ("ssh".equalsIgnoreCase(props.getAuth().getType())) {
            return null;
        }

        // 2. Ask the system git credential helper (same store IntelliJ uses)
        return resolveFromCredentialHelper("github.com");
    }

    /**
     * Runs {@code git credential fill} for the given host and extracts the
     * {@code password} field from the response.
     *
     * <p>On macOS the credential helper is {@code osxkeychain}, which holds the
     * OAuth token that IntelliJ stored when it connected to GitHub.  On Windows
     * it is the Windows Credential Manager, and on Linux it is whatever helper
     * the user has configured (e.g. {@code gnome-libsecret}, {@code pass}, etc.).
     *
     * <p>Returns {@code null} if git is not on the PATH, no credential helper is
     * configured, or the helper returns no result for the requested host.
     */
    private static String resolveFromCredentialHelper(String host) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "credential", "fill");
            // Merge stderr into stdout so we can capture any error messages cleanly
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Write the credential query to the helper's stdin
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write("protocol=https\n");
                writer.write("host=" + host + "\n");
                writer.write("\n"); // blank line signals end of input
            }

            String output = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("[GitHubService] git credential fill timed out");
                return null;
            }

            // Parse the key=value response; "password" holds the token
            for (String line : output.split("\n")) {
                if (line.startsWith("password=")) {
                    String token = line.substring("password=".length()).trim();
                    if (!token.isBlank()) {
                        log.info("[GitHubService] Token resolved from git credential helper "
                                + "(osxkeychain / IntelliJ auth)");
                        return token;
                    }
                }
            }

        } catch (Exception e) {
            log.debug("[GitHubService] git credential fill unavailable: {}", e.getMessage());
        }
        return null;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns the HEAD commit SHA for a branch, or {@code null} on failure.
     *
     * <p>Uses {@code GET /repos/{owner}/{repo}/branches/{branch}} rather than
     * the git/ref endpoint — the branch name ({@code main}, {@code develop}, etc.)
     * contains no slashes so URI template encoding is safe, and this endpoint
     * is more reliable than the low-level git/ref API.
     */
    private String getBranchSha(String branchName) {
        try {
            log.info("[GitHubService] GET https://api.github.com/repos/{}/{}/branches/{}",
                    owner, repo, branchName);
            String body = restClient.get()
                    .uri("/repos/{owner}/{repo}/branches/{branch}", owner, repo, branchName)
                    .retrieve()
                    .body(String.class);

            Map<String, Object> map = objectMapper.readValue(body, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            Map<String, Object> commit = (Map<String, Object>) map.get("commit");
            return (String) commit.get("sha");

        } catch (Exception e) {
            log.error("[GitHubService] getBranchSha '{}' failed: {}", branchName, e.getMessage());
            return null;
        }
    }

    /**
     * Human-readable description of where the token came from — used only in
     * startup logging.
     */
    private static String tokenSource(TargetRepoProperties props) {
        String explicit = props.getAuth().getToken();
        return (explicit != null && !explicit.isBlank())
                ? "TARGET_REPO_TOKEN env var"
                : "git credential helper (osxkeychain / IntelliJ)";
    }

    /**
     * Parses {@code https://github.com/owner/repo[.git]} into {@code [owner, repo]}.
     * Returns {@code [null, null]} for a blank or unparseable URL.
     */
    private static String[] parseOwnerRepo(String url) {
        if (url == null || url.isBlank()) return new String[]{null, null};
        String clean = url.trim().replaceAll("\\.git$", "").replaceAll("/$", "");
        String[] parts = clean.split("/");
        if (parts.length < 2) return new String[]{null, null};
        return new String[]{parts[parts.length - 2], parts[parts.length - 1]};
    }

    // ─── Result record ────────────────────────────────────────────────────────

    /**
     * Holds the result of a successful GitHub PR creation.
     *
     * @param prNumber GitHub PR number
     * @param url      HTML URL of the PR (e.g. https://github.com/org/repo/pull/42)
     * @param branch   Source branch name
     */
    public record GitHubPrResult(int prNumber, String url, String branch) {}
}
