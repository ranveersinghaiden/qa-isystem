package nz.co.eroad.qaisystem.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.agent.AiClient;
import nz.co.eroad.qaisystem.agent.CopilotCliClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the {@link AiClient} bean — always a {@link CopilotCliClient}.
 *
 * <p>The only supported AI provider is the locally installed GitHub Copilot CLI
 * ({@code gh} command). OpenAI and Copilot REST API providers have been removed.
 * Credentials are managed entirely by {@code gh auth login} — no token env vars
 * are required or accepted.
 *
 * <p>Only activated when {@code aiqa.github.enabled=true} — the same flag that
 * controls {@code GitHubService} and {@code RepoContextService}. Services that do
 * not perform AI generation (pr-service, impact-service) leave this unset so no
 * AI beans are instantiated.
 */
@Configuration
@ConditionalOnProperty(name = "aiqa.github.enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(AiProviderProperties.class)
public class AiClientConfig {

    /**
     * Creates a {@link CopilotCliClient} backed by the locally installed {@code gh} CLI.
     * Prerequisites: {@code brew install gh && gh auth login}.
     */
    @Bean
    public AiClient copilotCliAiClient(AiProviderProperties props, ObjectMapper objectMapper) {
        AiProviderProperties.CopilotCliConfig cfg = props.getCopilotCli();
        return new CopilotCliClient(cfg.getGhCliPath(), cfg.getModel(),
                                    cfg.getTimeoutSeconds(), objectMapper);
    }
}
