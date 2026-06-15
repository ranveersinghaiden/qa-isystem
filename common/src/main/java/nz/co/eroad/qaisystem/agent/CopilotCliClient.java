package nz.co.eroad.qaisystem.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import nz.co.eroad.qaisystem.model.ChatMessage;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AiClient implementation that calls the GitHub Models API using the token
 * managed by the GitHub CLI ({@code gh auth token}).
 *
 * <p><b>Endpoint:</b> {@code https://models.inference.ai.azure.com/chat/completions}
 * — the OpenAI-compatible endpoint for GitHub Models (supports Claude, GPT-4o, etc.).
 *
 * <p><b>Note on {@code gh api} vs direct curl:</b> {@code gh api} only injects the
 * {@code Authorization} header for GitHub's own domains ({@code api.github.com}).
 * For {@code models.inference.ai.azure.com} the token must be retrieved explicitly
 * via {@code gh auth token} and passed as a {@code Bearer} header in a {@code curl}
 * subprocess.
 *
 * <p><b>Prerequisites:</b>
 * <ol>
 *   <li>GitHub CLI installed: {@code brew install gh} (macOS) or equivalent</li>
 *   <li>Authenticated: {@code gh auth login}</li>
 *   <li>Token must have the {@code models} permission (fine-grained PAT) or
 *       use a classic PAT — add the permission at
 *       <a href="https://github.com/settings/personal-access-tokens">
 *       github.com/settings/personal-access-tokens</a></li>
 *   <li>Copilot subscription on the authenticated account</li>
 * </ol>
 */
@Slf4j
public class CopilotCliClient implements AiClient {

    private static final String MODELS_API_URL =
            "https://models.inference.ai.azure.com/chat/completions";

    private final String       ghCliPath;
    private final String       model;
    private final int          timeoutSeconds;
    private final boolean      available;
    private final ObjectMapper objectMapper;

    /** Constructs the client and eagerly checks whether {@code gh auth status} passes. */
    public CopilotCliClient(String ghCliPath, String model,
                            int timeoutSeconds, ObjectMapper objectMapper) {
        this.ghCliPath      = ghCliPath;
        this.model          = model;
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper   = objectMapper;
        this.available      = checkCliAvailable();
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    /** Sends a single-turn chat completion request and returns the model's text response. */
    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (!available) return null;
        List<Map<String, Object>> messages = List.of(
                Map.<String, Object>of("role", "system", "content", systemPrompt),
                Map.<String, Object>of("role", "user",   "content", userPrompt)
        );
        return callModelsApi(messages);
    }

    /** Sends a multi-turn chat completion request with prior conversation history. */
    @Override
    public String completeWithHistory(String systemPrompt,
                                      List<ChatMessage> history,
                                      String newUserMessage) {
        if (!available) return null;
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (ChatMessage turn : history) {
            messages.add(Map.of("role", turn.role(), "content", turn.content()));
        }
        messages.add(Map.of("role", "user", "content", newUserMessage));
        log.debug("[CopilotCliClient] completeWithHistory — {} history turns + new user message",
                history.size());
        return callModelsApi(List.copyOf(messages));
    }

    /**
     * Builds the JSON request body from the given messages list, writes temp files,
     * invokes curl against the GitHub Models API, and returns the content string.
     */
    private String callModelsApi(List<Map<String, Object>> messages) {
        Path bodyFile   = null;
        Path configFile = null;
        try {
            // ── 1. Fetch the GitHub token from gh credential store ────────────────
            String token = fetchGhToken();
            if (token == null || token.isBlank()) {
                log.error("[CopilotCliClient] Could not retrieve token from gh CLI. " +
                          "Run 'gh auth login' and ensure the token has the 'models' permission.");
                return null;
            }

            // ── 2. Build the chat completions request body ────────────────────────
            Map<String, Object> body = Map.of(
                    "model",       model,
                    "temperature", 0.2,
                    "messages",    messages
            );
            String bodyJson = objectMapper.writeValueAsString(body);

            // Write body to a temp file — owner-only (600): prompt may contain diffs.
            bodyFile = Files.createTempFile("copilot-body-", ".json",
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
            Files.writeString(bodyFile, bodyJson);

            // Write a curl config file — owner-only (600) so the token is never
            // visible in process arguments (ps aux / procfs).
            configFile = Files.createTempFile("copilot-cfg-", ".curl",
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
            Files.writeString(configFile,
                    "header = \"Authorization: Bearer " + token + "\"\n" +
                    "header = \"Content-Type: application/json\"\n");

            // ── 3. Call GitHub Models API via curl ────────────────────────────────
            List<String> command = List.of(
                    "curl", "--silent", "--show-error",
                    "--request", "POST",
                    "--url",     MODELS_API_URL,
                    "-K",        configFile.toString(),
                    "--data",    "@" + bodyFile.toString()
            );

            log.debug("[CopilotCliClient] Calling GitHub Models API — model='{}' ({} messages)",
                      model, messages.size());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);

            Process process = pb.start();

            var stdout = new StringBuilder();
            var stderr = new StringBuilder();

            var outThread = Thread.ofVirtual().start(() -> {
                try (var r = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) stdout.append(line).append("\n");
                } catch (IOException e) {
                    log.warn("[CopilotCliClient] stdout read error: {}", e.getMessage());
                }
            });
            var errThread = Thread.ofVirtual().start(() -> {
                try (var r = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) stderr.append(line).append("\n");
                } catch (IOException e) {
                    log.warn("[CopilotCliClient] stderr read error: {}", e.getMessage());
                }
            });

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            outThread.join(5_000);
            errThread.join(5_000);

            if (!finished) {
                process.destroyForcibly();
                log.error("[CopilotCliClient] GitHub Models API call timed out after {}s",
                          timeoutSeconds);
                return null;
            }

            if (process.exitValue() != 0) {
                log.error("[CopilotCliClient] curl exited with code {} — stderr: {}",
                          process.exitValue(), stderr.toString().trim());
                return null;
            }

            // ── 4. Parse response (OpenAI-compatible schema) ──────────────────────
            String responseJson = stdout.toString().trim();

            if (responseJson.contains("\"error\"")) {
                Map<String, Object> errResp = objectMapper.readValue(
                        responseJson, new TypeReference<>() {});
                Object errObj = errResp.get("error");
                log.error("[CopilotCliClient] GitHub Models API error: {}. " +
                          "Ensure your GitHub token has the 'models' permission. " +
                          "Fine-grained PAT: add 'models' at " +
                          "https://github.com/settings/personal-access-tokens. " +
                          "Classic PAT: no extra scope needed.", errObj);
                return null;
            }

            Map<String, Object> resp = objectMapper.readValue(
                    responseJson, new TypeReference<>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices =
                    (List<Map<String, Object>>) resp.get("choices");
            if (choices == null || choices.isEmpty()) {
                log.warn("[CopilotCliClient] Empty choices array in API response");
                return null;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> message =
                    (Map<String, Object>) choices.get(0).get("message");
            String content = message == null ? null : (String) message.get("content");

            log.info("[CopilotCliClient] Success — {} chars returned by model='{}'",
                     content != null ? content.length() : 0, model);
            return content;

        } catch (Exception e) {
            log.error("[CopilotCliClient] API call failed: {}", e.getMessage(), e);
            return null;
        } finally {
            if (bodyFile   != null) {
                try { Files.deleteIfExists(bodyFile);   } catch (IOException ignored) {}
            }
            if (configFile != null) {
                try { Files.deleteIfExists(configFile); } catch (IOException ignored) {}
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Retrieves the active GitHub token from the gh credential store.
     * Returns {@code null} when gh is not installed or not authenticated.
     */
    private String fetchGhToken() {
        try {
            var pb = new ProcessBuilder(ghCliPath, "auth", "token");
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.redirectErrorStream(false);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(10, TimeUnit.SECONDS);
            return out.isBlank() ? null : out;
        } catch (Exception e) {
            log.warn("[CopilotCliClient] Could not get token from gh: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Runs {@code gh auth status} to verify that the CLI is installed and authenticated.
     */
    private boolean checkCliAvailable() {
        try {
            var pb = new ProcessBuilder(ghCliPath, "auth", "status");
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); }
            boolean authed = done && p.exitValue() == 0;
            if (authed) {
                log.info("[CopilotCliClient] Ready — gh CLI authenticated, model='{}' " +
                         "endpoint='{}'", model, MODELS_API_URL);
            } else {
                log.warn("[CopilotCliClient] Not available — run 'gh auth login' to enable " +
                         "Copilot CLI mode.");
            }
            return authed;
        } catch (Exception e) {
            log.warn("[CopilotCliClient] gh CLI not found at '{}' — " +
                     "install with 'brew install gh'.", ghCliPath);
            return false;
        }
    }
}
