package nz.co.eroad.qaisystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the AI-powered context compression step in pr-service.
 * Bound from the {@code aiqa.ai.compression} prefix.
 */
@Data
@ConfigurationProperties(prefix = "aiqa.ai.compression")
public class CompressionProperties {
    /** Enable AI context compression. Default false so pr-service starts without gh CLI. */
    private boolean enabled = false;
    /** Path to the gh CLI executable. */
    private String ghCliPath = "gh";
    /** Model passed to gh api. Use gpt-5 or another Copilot-supported model. */
    private String model = "gpt-5";
    /** Timeout in seconds for each compression call. */
    private int timeoutSeconds = 60;
}

