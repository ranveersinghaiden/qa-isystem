package nz.co.eroad.qaisystem.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * GitHub Copilot API client.
 *
 * <p>Implements {@link AiClient} using the GitHub Copilot chat-completions API
 * ({@code https://api.githubcopilot.com/chat/completions}) or the GitHub Models
 * gateway ({@code https://models.inference.ai.azure.com/chat/completions}).
 *
 * <p>Authentication: set {@code GITHUB_COPILOT_TOKEN} to a GitHub personal access
 * token, GitHub App installation token, or the {@code GITHUB_TOKEN} secret
 * available in GitHub Actions when the repository has Copilot enabled.
 *
 * <p>Not annotated with {@code @Service}; created by {@link nz.co.eroad.qaisystem.config.AiClientConfig}
 * based on {@code aiqa.ai.provider=copilot}.
 */
@Slf4j
public class CopilotClient implements AiClient {

    private final String       model;
    private final boolean      available;
    private final RestClient   restClient;
    private final ObjectMapper objectMapper;

    public CopilotClient(String token, String baseUrl, String model, ObjectMapper objectMapper) {
        this.model        = model;
        this.objectMapper = objectMapper;
        this.available    = token != null && !token.isBlank();

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                // Identifies this integration to the Copilot API
                .defaultHeader("Copilot-Integration-Id", "qa-isystem");

        if (available) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        this.restClient = builder.build();

        if (available) {
            log.info("[CopilotClient] Ready — endpoint='{}', model='{}'", baseUrl, model);
        } else {
            log.info("[CopilotClient] Not configured — set GITHUB_COPILOT_TOKEN to enable "
                    + "GitHub Copilot-powered generation. Running in enhanced-template mode.");
        }
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (!available) return null;
        try {
            Map<String, Object> body = Map.of(
                    "model",       model,
                    "temperature", 0.2,
                    "messages",    List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user",   "content", userPrompt)
                    )
            );

            String responseJson = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            Map<String, Object> resp = objectMapper.readValue(
                    responseJson, new TypeReference<>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            if (choices == null || choices.isEmpty()) {
                log.warn("[CopilotClient] Empty choices array in response");
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return message == null ? null : (String) message.get("content");

        } catch (Exception e) {
            log.error("[CopilotClient] Chat completion failed: {}", e.getMessage());
            return null;
        }
    }
}
