package nz.co.eroad.qaisystem.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.agent.AiClient;
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
 *   <li>{@code aiqa.ai.provider=openai} (default) — creates {@link OpenAiClient}</li>
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
     * Creates an {@link OpenAiClient} when {@code aiqa.ai.provider=openai}
     * or when the property is absent (openai is the default).
     */
    @Bean
    @ConditionalOnProperty(name = "aiqa.ai.provider", havingValue = "openai", matchIfMissing = true)
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
