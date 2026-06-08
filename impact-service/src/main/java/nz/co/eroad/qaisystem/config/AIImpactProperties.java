package nz.co.eroad.qaisystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the AI last-resort impact evaluator.
 *
 * <p>Bound from the {@code aiqa.ai} YAML block.  All properties have safe
 * defaults so the service starts without any configuration — AI evaluation
 * is <em>opt-in</em> via {@code aiqa.ai.enabled: true}.
 *
 * <p>The provider is selected with {@code aiqa.ai.provider}:
 * <ul>
 *   <li>{@code openai} (default) — requires {@code AIQA_AI_API_KEY}</li>
 *   <li>{@code copilot} — requires {@code GITHUB_COPILOT_TOKEN}</li>
 * </ul>
 *
 * <p>Example YAML:
 * <pre>
 * aiqa:
 *   ai:
 *     enabled:  true
 *     provider: openai              # or "copilot"
 *     api-key:  ${AIQA_AI_API_KEY:} # used when provider=openai
 *     copilot-token: ${GITHUB_COPILOT_TOKEN:} # used when provider=copilot
 *     model:    gpt-4o-mini
 *     base-url: https://api.openai.com/v1     # override for Azure / Ollama
 *     copilot-base-url: https://api.githubcopilot.com
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "aiqa.ai")
public class AIImpactProperties {

    /**
     * Master switch.  Must be explicitly set to {@code true} to enable AI calls.
     * Default: {@code false} — fully deterministic behaviour, no external calls.
     */
    private boolean enabled = false;

    /**
     * AI provider to use: {@code openai} (default) or {@code copilot}.
     */
    private String provider = "openai";

    /**
     * API key for the OpenAI provider.
     * Inject via environment variable {@code AIQA_AI_API_KEY} — never hard-code.
     * Ignored when {@code provider=copilot}.
     */
    private String apiKey = "";

    /**
     * GitHub token for the Copilot provider.
     * Inject via environment variable {@code GITHUB_COPILOT_TOKEN} — never hard-code.
     * Used only when {@code provider=copilot}.
     */
    private String copilotToken = "";

    /**
     * LLM model to use.  Any model name supported by the selected provider.
     * Default: {@code gpt-4o-mini} (fast, cheap, good at structured JSON output).
     */
    private String model = "gpt-4o-mini";

    /**
     * Base URL for the OpenAI provider endpoint.
     * Override for Azure OpenAI, local Ollama, or any other compatible provider.
     * Ignored when {@code provider=copilot}.
     */
    private String baseUrl = "https://api.openai.com/v1";

    /**
     * Base URL for the GitHub Copilot provider endpoint.
     * Can also point to GitHub Models: {@code https://models.inference.ai.azure.com}.
     * Used only when {@code provider=copilot}.
     */
    private String copilotBaseUrl = "https://api.githubcopilot.com";

    /**
     * Risk scores <em>strictly below</em> this bound are already confidently LOW.
     * AI evaluation is skipped because the deterministic result is reliable.
     * Default: {@code 0.30}.
     */
    private double confidenceLowerBound = 0.30;

    /**
     * Risk scores <em>strictly above</em> this bound are already confidently HIGH or CRITICAL.
     * AI evaluation is skipped because there is enough signal to act without AI.
     * Default: {@code 0.75}.
     */
    private double confidenceUpperBound = 0.75;

    /**
     * Maximum diff characters to include in the prompt.
     * Large diffs are truncated to stay within context window limits and reduce cost.
     * Default: {@code 3000} characters.
     */
    private int maxDiffChars = 3000;

    /**
     * HTTP request timeout for the AI API call in seconds.
     * If the LLM does not respond in time the service falls back to the deterministic result.
     * Default: {@code 15} seconds.
     */
    private int timeoutSeconds = 15;

    /**
     * Maximum allowed risk score adjustment (up or down) from the AI.
     * Prevents the LLM from overriding a well-reasoned deterministic score completely.
     * Default: {@code 0.15}.
     */
    private double maxScoreAdjustment = 0.15;

    /**
     * Returns the effective API key for the configured provider.
     * For {@code openai}: returns {@code apiKey}.
     * For {@code copilot}: returns {@code copilotToken}.
     */
    public String getEffectiveApiKey() {
        return "copilot".equalsIgnoreCase(provider) ? copilotToken : apiKey;
    }

    /**
     * Returns the effective base URL for the configured provider, including the
     * {@code /chat/completions} path segment is <em>not</em> included — append it
     * at call time.
     * For {@code openai}: returns {@code baseUrl} (e.g. {@code https://api.openai.com/v1}).
     * For {@code copilot}: returns {@code copilotBaseUrl} (e.g. {@code https://api.githubcopilot.com}).
     */
    public String getEffectiveBaseUrl() {
        return "copilot".equalsIgnoreCase(provider) ? copilotBaseUrl : baseUrl;
    }

    /** @return {@code true} if AI is enabled and the selected provider is configured. */
    public boolean isConfigured() {
        String key = getEffectiveApiKey();
        return enabled && key != null && !key.isBlank();
    }

    /** @return {@code true} if {@code score} falls in the ambiguous gray zone. */
    public boolean isInGrayZone(double score) {
        return score >= confidenceLowerBound && score <= confidenceUpperBound;
    }
}


