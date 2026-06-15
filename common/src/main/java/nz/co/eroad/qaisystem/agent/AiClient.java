package nz.co.eroad.qaisystem.agent;

import nz.co.eroad.qaisystem.model.ChatMessage;
import java.util.List;

/**
 * Abstraction over a language-model API for test generation.
 *
 * <p>Three implementations are provided, selected via the {@code aiqa.ai.provider}
 * configuration property:
 * <ul>
 *   <li>{@link CopilotCliClient} ({@code provider=copilot-cli}, the default) — calls the
 *       GitHub Copilot API via the {@code gh api} CLI subprocess. No token management
 *       required; authentication is handled by {@code gh auth login}.</li>
 *   <li>{@link OpenAiClient} ({@code provider=openai}) — calls any OpenAI-compatible
 *       endpoint (OpenAI, Azure OpenAI, local Ollama, GitHub Models).</li>
 *   <li>{@link CopilotClient} ({@code provider=copilot}) — calls the GitHub Copilot API
 *       using a GitHub token set via {@code GITHUB_COPILOT_TOKEN}.</li>
 * </ul>
 *
 * <p>When the active client is not configured {@link #isAvailable()} returns
 * {@code false} and callers fall back to enhanced template generation that still
 * embeds the product expert context as comments in the generated output.
 */
public interface AiClient {

    /**
     * Sends a chat completion request and returns the model's text response.
     *
     * @param systemPrompt background knowledge, role instructions, and conventions
     * @param userPrompt   the specific generation task
     * @return the model's response text, or {@code null} if the request failed
     *         or the client is not available
     */
    String complete(String systemPrompt, String userPrompt);

    /**
     * Sends a multi-turn chat completion request, injecting prior conversation turns
     * as context before the new user message.
     *
     * <p>The default implementation ignores {@code history} and delegates to the
     * single-turn {@link #complete} method — preserving backward compatibility for
     * BDD/codegen callers that do not need history.
     *
     * <p>Override in concrete clients (e.g. {@link CopilotCliClient}) to support
     * full multi-turn context.
     *
     * @param systemPrompt   background knowledge, role instructions, and conventions
     * @param history        prior turns in chronological order (may be empty)
     * @param newUserMessage the new user message for this turn
     * @return the model's response text, or {@code null} if the request failed
     */
    default String completeWithHistory(String systemPrompt,
                                       List<ChatMessage> history,
                                       String newUserMessage) {
        return complete(systemPrompt, newUserMessage);
    }

    /**
     * Returns {@code true} when the client is fully configured and ready to
     * make real API calls.
     */
    boolean isAvailable();
}
