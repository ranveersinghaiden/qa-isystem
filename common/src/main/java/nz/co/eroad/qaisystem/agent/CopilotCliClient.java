package nz.co.eroad.qaisystem.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AiClient implementation that calls the GitHub Copilot chat-completions API via the
 * {@code gh api} command from the GitHub CLI.
 *
 * <p><b>Key advantage over {@link CopilotClient}:</b> no token management — the {@code gh}
 * CLI uses the credential store populated by {@code gh auth login}, the same one IntelliJ
 * and the terminal use. Billing is flat-rate (Copilot seat licence) rather than per-token.
 *
 * <p><b>Prerequisites:</b>
 * <ol>
 *   <li>GitHub CLI installed: {@code brew install gh} (macOS) or equivalent</li>
 *   <li>Authenticated: {@code gh auth login}</li>
 *   <li>Copilot access on the authenticated account</li>
 * </ol>
 *
 * <p>Not annotated with {@code @Service}; created by
 * {@link nz.co.eroad.qaisystem.config.AiClientConfig} when
 * {@code aiqa.ai.provider=copilot-cli} (the recommended default).
 *
 * <p>{@link #isAvailable()} returns {@code false} when {@code gh auth status} fails —
 * the system falls back to enhanced template generation without throwing.
 */
@Slf4j
public class CopilotCliClient implements AiClient {

    private static final String COPILOT_API_URL =
            "https://api.githubcopilot.com/chat/completions";
    private static final String INTEGRATION_HEADER =
            "Copilot-Integration-Id: qa-isystem";

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

    /** Sends a chat completion request through {@code gh api} and returns the model's text. */
    @Override
    public String complete(String systemPrompt, String userPrompt) {
        if (!available) return null;

        Path bodyFile = null;
        try {
            // Build the chat completions request body as JSON
            Map<String, Object> body = Map.of(
                    "model",       model,
                    "temperature", 0.2,
                    "messages",    List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user",   "content", userPrompt)
                    )
            );
            String bodyJson = objectMapper.writeValueAsString(body);

            // Write to a temp file to avoid shell escaping and arg-length issues.
            // Owner-only read/write (600) — file contains the full AI prompt which may include
            // code diffs; prevent other OS users from reading it.
            bodyFile = Files.createTempFile("copilot-body-", ".json",
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
            Files.writeString(bodyFile, bodyJson);

            List<String> command = List.of(
                    ghCliPath, "api",
                    COPILOT_API_URL,
                    "--method", "POST",
                    "--header", "Content-Type: application/json",
                    "--header", INTEGRATION_HEADER,
                    "--input",  bodyFile.toString()
            );

            log.debug("[CopilotCliClient] Invoking gh api for model='{}' " +
                      "(systemPrompt={} chars, userPrompt={} chars)",
                      model, systemPrompt.length(), userPrompt.length());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.redirectErrorStream(false);

            Process process = pb.start();

            var stdout = new StringBuilder();
            var stderr = new StringBuilder();

            // Read stdout and stderr concurrently on virtual threads
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
                log.error("[CopilotCliClient] gh api timed out after {}s", timeoutSeconds);
                return null;
            }

            if (process.exitValue() != 0) {
                log.error("[CopilotCliClient] gh api exited with code {} — stderr: {}",
                          process.exitValue(), stderr.toString().trim());
                return null;
            }

            // Parse the Copilot API JSON response (same schema as OpenAI)
            String responseJson = stdout.toString().trim();
            Map<String, Object> resp = objectMapper.readValue(
                    responseJson, new TypeReference<>() {});

            @SuppressWarnings("unchecked")   // Response schema is fixed by Copilot API contract
            List<Map<String, Object>> choices =
                    (List<Map<String, Object>>) resp.get("choices");
            if (choices == null || choices.isEmpty()) {
                log.warn("[CopilotCliClient] Empty choices array in gh api response");
                return null;
            }

            @SuppressWarnings("unchecked")   // Message object has a well-known schema
            Map<String, Object> message =
                    (Map<String, Object>) choices.get(0).get("message");
            String content = message == null ? null : (String) message.get("content");

            log.info("[CopilotCliClient] Success — {} chars returned",
                     content != null ? content.length() : 0);
            return content;

        } catch (Exception e) {
            log.error("[CopilotCliClient] gh api call failed: {}", e.getMessage(), e);
            return null;
        } finally {
            if (bodyFile != null) {
                try { Files.deleteIfExists(bodyFile); } catch (IOException ignored) {}
            }
        }
    }

    // ─── Availability check ───────────────────────────────────────────────────

    /**
     * Runs {@code gh auth status} to verify that the CLI is installed and authenticated.
     * Returns {@code false} silently when gh is not installed or not logged in —
     * the system will fall back to enhanced template generation.
     */
    private boolean checkCliAvailable() {
        try {
            var pb = new ProcessBuilder(ghCliPath, "auth", "status");
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Drain output so the process doesn't block
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); }
            boolean authed = done && p.exitValue() == 0;
            if (authed) {
                log.info("[CopilotCliClient] Ready — gh CLI authenticated, model='{}'", model);
            } else {
                log.info("[CopilotCliClient] Not available — run 'gh auth login' to enable " +
                         "Copilot CLI mode. Falling back to enhanced-template generation.");
            }
            return authed;
        } catch (Exception e) {
            log.info("[CopilotCliClient] gh CLI not found at '{}' — " +
                     "install with 'brew install gh'. Falling back to template mode.", ghCliPath);
            return false;
        }
    }
}

