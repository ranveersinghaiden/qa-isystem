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
 * <p>Example YAML:
 * <pre>
 * aiqa:
 *   ai:
 *     enabled: true
 *     api-key: ${AIQA_AI_API_KEY:}
 *     model: gpt-4o-mini
 *     confidence-lower-bound: 0.30
 *     confidence-upper-bound: 0.75
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
     * API key for the LLM provider.
     * Inject via environment variable {@code AIQA_AI_API_KEY} — never hard-code.
     */
    private String apiKey = "";

    /**
     * LLM model to use.  Any OpenAI chat-completions-compatible model name.
     * Default: {@code gpt-4o-mini} (fast, cheap, good at structured JSON output).
     */
    private String model = "gpt-4o-mini";

    /**
     * Base URL of the OpenAI-compatible API endpoint.
     * Override for Azure OpenAI, local Ollama, or any other compatible provider.
     */
    private String baseUrl = "https://api.openai.com/v1";

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

    /** @return {@code true} if AI is enabled and an API key is configured. */
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /** @return {@code true} if {@code score} falls in the ambiguous gray zone. */
    public boolean isInGrayZone(double score) {
        return score >= confidenceLowerBound && score <= confidenceUpperBound;
    }
}

