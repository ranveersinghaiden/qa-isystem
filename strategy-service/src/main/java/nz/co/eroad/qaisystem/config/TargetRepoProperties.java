package nz.co.eroad.qaisystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the target test monorepo that codegen reads as context.
 * Set aiqa.target-repo.url in application.yaml to enable context-aware generation.
 * Leave url blank to fall back to built-in templates (no repo access needed).
 */
@Data
@Component
@ConfigurationProperties(prefix = "aiqa.target-repo")
public class TargetRepoProperties {

    /** Git remote URL. Supports HTTPS (with optional token auth) and SSH. */
    private String url = "";

    /** Branch or tag to check out. */
    private String branch = "main";

    /** Local directory where the repo will be cloned / kept up to date. */
    private String localPath = "/tmp/qa-context-repo";

    /**
     * Optional local directory to use as a context source when the remote clone
     * fails (network unavailable, credentials missing, etc.).
     *
     * <p>Use cases:
     * <ul>
     *   <li>IntelliJ already has the repo checked out locally — point here to avoid
     *       needing a separate clone at all.</li>
     *   <li>Air-gapped environment — point to a manually synced copy.</li>
     *   <li>Dev laptop without internet access to the test repo — use last known good copy.</li>
     * </ul>
     *
     * <p>If this path contains a {@code .git} directory AND {@code fallback-pull=true},
     * the service will attempt a {@code git pull} on it before scanning.  Failures are
     * logged and ignored — the cached version is used as-is.
     *
     * <p>Leave blank (default) to disable the fallback entirely.
     */
    private String fallbackLocalPath = "";

    /**
     * When {@code true}, attempt {@code git pull} on the fallback path if it is a git
     * repository. Useful when the fallback is your IntelliJ workspace clone.
     * Default: {@code false} — use the fallback as a static/read-only directory.
     */
    private boolean fallbackPull = false;

    /**
     * Relative paths inside the monorepo for each test module.
     * Adjust these to match your repository layout.
     */
    private Modules modules = new Modules();

    /** Authentication settings for private repositories. */
    private Auth auth = new Auth();

    @Data
    public static class Modules {
        /** Path to the API / REST-Assured test module. */
        private String api = "tests/api";

        /** Path to the UI / Selenium test module. */
        private String ui = "tests/ui";

        /** Path to the Mobile / Appium test module. */
        private String mobile = "tests/mobile";
    }

    @Data
    public static class Auth {
        /**
         * Auth type: none | token | ssh
         *   none  — public repo, no credentials needed
         *   token — inject a PAT/token into the HTTPS clone URL
         *   ssh   — use the system SSH agent (key must be in ~/.ssh)
         */
        private String type = "none";

        /** Personal Access Token (used when type=token). Use env var: TARGET_REPO_TOKEN */
        private String token = "";

        /** Git username (used when type=token). Use env var: TARGET_REPO_USERNAME */
        private String username = "";
    }

    /** Returns true only when a non-empty URL is configured. */
    public boolean isConfigured() {
        return url != null && !url.isBlank();
    }

    /** Returns true when a fallback local path is configured. */
    public boolean hasFallback() {
        return fallbackLocalPath != null && !fallbackLocalPath.isBlank();
    }

    /** Resolves the module path for a given test type (API / UI / MOBILE). */
    public String modulePathFor(String testType) {
        return switch (testType.toUpperCase()) {
            case "UI"     -> modules.getUi();
            case "MOBILE" -> modules.getMobile();
            default       -> modules.getApi();
        };
    }
}
