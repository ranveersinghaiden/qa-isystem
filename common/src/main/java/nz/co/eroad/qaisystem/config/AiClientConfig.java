package nz.co.eroad.qaisystem.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.agent.AiClient;
import nz.co.eroad.qaisystem.agent.CopilotCliClient;
import nz.co.eroad.qaisystem.agent.CopilotClient;
import nz.co.eroad.qaisystem.agent.OpenAiClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the active {@link AiClient} bean based on {@code aiqa.ai.provider}.
 *
 * <ul>
 *   <li>{@code aiqa.ai.provider=copilot-cli} (default) — creates {@link CopilotCliClient}</li>
 *   <li>{@code aiqa.ai.provider=openai} — creates {@link OpenAiClient}</li>
 *   <li>{@code aiqa.ai.provider=copilot} — creates {@link CopilotClient}</li>
 * </ul>
 *
 * <p>Only activated when {@code aiqa.github.enabled=true} — the same flag that
 * controls {@link GitHubService} and {@link RepoContextService}.  Services that do
 * not perform AI generation (pr-service, impact-service) leave this unset so no
 * AI beans are instantiated.
 *
 * All services inject {@link AiClient} and are unaware of which provider is active.
 */
@Configuration
@ConditionalOnProperty(name = "aiqa.github.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(AiProviderProperties.class)
public class AiClientConfig {

    /**
     * Creates a {@link CopilotCliClient} when {@code aiqa.ai.provider=copilot-cli}
     * OR when the property is absent (copilot-cli is the new default).
     * Uses gh CLI authentication — no token env var required.
     */
    @Bean
    @ConditionalOnProperty(name = "aiqa.ai.provider", havingValue = "copilot-cli", matchIfMissing = true)
    public AiClient copilotCliAiClient(AiProviderProperties props, ObjectMapper objectMapper) {
        AiProviderProperties.CopilotCliConfig cfg = props.getCopilotCli();
        return new CopilotCliClient(cfg.getGhCliPath(), cfg.getModel(),
                                    cfg.getTimeoutSeconds(), objectMapper);
    }

    /**
     * Creates an {@link OpenAiClient} when {@code aiqa.ai.provider=openai}.
     */
    @Bean
    @ConditionalOnProperty(name = "aiqa.ai.provider", havingValue = "openai")
    public AiClient openAiClient(AiProviderProperties props, ObjectMapper objectMapper) {
        AiProviderProperties.OpenAiConfig cfg = props.getOpenai();
        return new OpenAiClient(cfg.getApiKey(), cfg.getBaseUrl(), cfg.getModel(), objectMapper);
    }

    /**
     * Creates a {@link CopilotClient} when {@code aiqa.ai.provider=copilot}.
     */
    @Bean
    @ConditionalOnProperty(name = "aiqa.ai.provider", havingValue = "copilot")
    public AiClient copilotAiClient(AiProviderProperties props, ObjectMapper objectMapper) {
        AiProviderProperties.CopilotConfig cfg = props.getCopilot();
        return new CopilotClient(cfg.getToken(), cfg.getBaseUrl(), cfg.getModel(), objectMapper);
    }
}
