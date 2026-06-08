package nz.co.eroad.qaisystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Unified AI-provider configuration for all QA-ISystem services.
 *
 * <p>Set {@code aiqa.ai.provider} to select the backend:
 * <ul>
 *   <li>{@code openai} (default) — any OpenAI-compatible endpoint</li>
 *   <li>{@code copilot} — GitHub Copilot API</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "aiqa.ai")
public class AiProviderProperties {

    /** AI backend: {@code openai} (default) or {@code copilot}. */
    private String provider = "openai";

    /** OpenAI (or OpenAI-compatible) backend settings. */
    private OpenAiConfig openai = new OpenAiConfig();

    /** GitHub Copilot API backend settings. */
    private CopilotConfig copilot = new CopilotConfig();

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
