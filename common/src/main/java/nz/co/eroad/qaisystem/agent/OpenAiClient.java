package nz.co.eroad.qaisystem.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible chat completions client.
 *
 * <h3>Supported backends</h3>
 * <ul>
 *   <li><b>OpenAI API</b>      — set {@code OPENAI_API_KEY} via {@code aiqa.ai.openai.api-key}</li>
 *   <li><b>Azure OpenAI</b>    — set {@code aiqa.ai.openai.base-url} to your Azure endpoint
 *                                 + {@code aiqa.ai.openai.api-key}</li>
 *   <li><b>Local Ollama</b>    — set {@code aiqa.ai.openai.base-url=http://localhost:11434}
 *                                 (no key needed)</li>
 *   <li><b>GitHub Models</b>   — set {@code aiqa.ai.openai.base-url=https://models.inference.ai.azure.com}
 *                                 + {@code aiqa.ai.openai.api-key} to a GitHub token</li>
 * </ul>
 *
 * <p>If no API key is set and the base URL is the default OpenAI endpoint,
 * {@link #isAvailable()} returns {@code false}.  All generators then fall back to
 * enhanced template mode — product expert context is still embedded as comments.
 *
 * <p>Not annotated with {@code @Service}; instantiated by
 * {@link nz.co.eroad.qaisystem.config.AiClientConfig} when
 * {@code aiqa.ai.provider=openai} (the default).
 */
@Slf4j
public class OpenAiClient implements AiClient {

    private final String       model;
    private final boolean      available;
    private final RestClient   restClient;
    private final ObjectMapper objectMapper;

    public OpenAiClient(
            String apiKey,
            String baseUrl,
            String model,
            ObjectMapper objectMapper) {

        this.model        = model;
        this.objectMapper = objectMapper;

        boolean hasKey       = apiKey != null && !apiKey.isBlank();
        boolean isDefaultUrl = baseUrl.contains("api.openai.com");

        // Available when: (a) key present (any URL), or (b) custom URL without key (local models)
        this.available = hasKey || !isDefaultUrl;

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json");

        if (hasKey) {
            builder.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        this.restClient = builder.build();
    }

    @PostConstruct
    void logState() {
        if (available) {
            log.info("[OpenAiClient] Ready — model='{}'", model);
        } else {
            log.info("[OpenAiClient] Not configured — set OPENAI_API_KEY (and optionally " +
                    "OPENAI_BASE_URL / OPENAI_MODEL) to enable AI-powered generation. " +
                    "Running in enhanced-template mode: product expert context will be " +
                    "embedded as comments in all generated files.");
        }
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    /**
     * Calls the {@code /v1/chat/completions} endpoint with the given prompts.
     *
     * @return the model response text, or {@code null} on failure
     */
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
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            Map<String, Object> resp = objectMapper.readValue(
                    responseJson, new TypeReference<>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            if (choices == null || choices.isEmpty()) {
                log.warn("[OpenAiClient] Empty choices array in response");
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return message == null ? null : (String) message.get("content");

        } catch (Exception e) {
            log.error("[OpenAiClient] Chat completion failed: {}", e.getMessage());
            return null;
        }
    }
}

