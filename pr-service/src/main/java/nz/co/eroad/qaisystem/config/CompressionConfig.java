package nz.co.eroad.qaisystem.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.agent.CopilotCliClient;
import nz.co.eroad.qaisystem.service.ContextCompressionService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the {@link ContextCompressionService} bean for pr-service.
 *
 * <p>Uses its own {@link CopilotCliClient} instance — intentionally independent of
 * the shared {@code AiClientConfig} (which requires {@code aiqa.github.enabled=true}
 * and must not be activated in pr-service).
 *
 * <p>When {@code aiqa.ai.compression.enabled=false} (the default) a no-op
 * compression service is created so {@link nz.co.eroad.qaisystem.service.PRService}
 * can always inject it without conditional wiring.
 */
@Configuration
@EnableConfigurationProperties(CompressionProperties.class)
public class CompressionConfig {

    /** Provides a {@link ContextCompressionService} — no-op when compression is disabled. */
    @Bean
    public ContextCompressionService contextCompressionService(
            CompressionProperties props, ObjectMapper objectMapper) {
        if (props.isEnabled()) {
            var client = new CopilotCliClient(
                    props.getGhCliPath(), props.getModel(),
                    props.getTimeoutSeconds(), objectMapper);
            return new ContextCompressionService(client);
        }
        // No-op: compression disabled — PRService will pass through unchanged
        return new ContextCompressionService(null);
    }
}

