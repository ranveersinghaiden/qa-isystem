package nz.co.eroad.qaisystem.agent;

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
     * Returns {@code true} when the client is fully configured and ready to
     * make real API calls.
     */
    boolean isAvailable();
}
