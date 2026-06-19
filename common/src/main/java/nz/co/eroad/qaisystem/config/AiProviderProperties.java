package nz.co.eroad.qaisystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Unified AI-provider configuration for all QA-ISystem services.
 *
 * <p>Set {@code aiqa.ai.provider} to select the backend:
 * <ul>
 *   <li>{@code copilot-cli} (default) — gh CLI, no token management needed</li>
 *   <li>{@code openai} — any OpenAI-compatible endpoint</li>
 *   <li>{@code copilot} — GitHub Copilot API with token</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "aiqa.ai")
public class AiProviderProperties {

    /** AI backend: {@code copilot-cli} (default), {@code copilot}, or {@code openai}. */
    private String provider = "copilot-cli";

    /** GitHub Copilot CLI backend settings (recommended default). */
    private CopilotCliConfig copilotCli = new CopilotCliConfig();

    /** Copilot agent subprocess settings (two-phase pipeline). */
    private CopilotAgentConfig copilotAgent = new CopilotAgentConfig();

    /** OpenAI (or OpenAI-compatible) backend settings. */
    private OpenAiConfig openai = new OpenAiConfig();

    /** GitHub Copilot API backend settings. */
    private CopilotConfig copilot = new CopilotConfig();

    @Data
    public static class CopilotCliConfig {
        /** Path to the gh CLI executable. Default: {@code gh} (must be on PATH). */
        private String ghCliPath = "gh";
        /** Model name passed in the JSON body. Default: {@code gpt-4o}. */
        private String model = "gpt-4o";
        /** Timeout in seconds for each gh api subprocess call. Default: {@code 120}. */
        private int timeoutSeconds = 120;
    }

    @Data
    public static class CopilotAgentConfig {
        /** Path to the copilot CLI executable. Default: {@code copilot} (must be on PATH). */
        private String copilotCliPath = "copilot";
        /** Maximum simultaneous copilot subprocesses. Default: {@code 3}. */
        private int maxConcurrentAgents = 3;
        /** Hard timeout in seconds before subprocess is forcibly killed. Default: {@code 300}. */
        private int agentTimeoutSeconds = 300;
        /** Maximum characters to accumulate from agent text chunks before truncation. Default: {@code 200000}. */
        private int maxOutputChars = 200_000;
        /**
         * Working directory for the subprocess (the cloned target repo root).
         * Leave blank to use the value from {@code aiqa.target-repo.local-path}.
         */
        private String workingDir = "";
        /**
         * Maximum number of autopilot continuation messages before the subprocess exits.
         * Passed via {@code --max-autopilot-continues}. Default: {@code 5}.
         */
        private int maxAutopilotContinues = 5;
    }

    @Data
    public static class OpenAiConfig {
        /** API key. Inject via {@code OPENAI_API_KEY}. Leave blank for Ollama. */
        private String apiKey = "";
        /** Base URL. Defaults to {@code https://api.openai.com}.
         *  Override for Azure OpenAI, Ollama, or GitHub Models. */
        private String baseUrl = "https://api.openai.com";
        /** Model identifier, e.g. {@code gpt-4o}. */
        private String model = "gpt-4o";
    }

    @Data
    public static class CopilotConfig {
        /** GitHub token with Copilot access. Inject via {@code GITHUB_COPILOT_TOKEN}. */
        private String token = "";
        /** Copilot API base URL. Defaults to {@code https://api.githubcopilot.com}.
         *  Can also point to GitHub Models: {@code https://models.inference.ai.azure.com}. */
        private String baseUrl = "https://api.githubcopilot.com";
        /** Model name, e.g. {@code gpt-4o} or {@code claude-3.5-sonnet}. */
        private String model = "gpt-4o";
    }
}
