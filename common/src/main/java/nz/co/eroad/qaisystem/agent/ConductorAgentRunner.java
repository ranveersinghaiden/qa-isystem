package nz.co.eroad.qaisystem.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import nz.co.eroad.qaisystem.config.AiProviderProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Launches a {@code copilot} subprocess that always delegates the task to the
 * repository's <b>Conductor</b> agent, monitored from this Java process.
 *
 * <p>The Conductor agent is the single entry point for all BDD scenario and test
 * code generation. No other agent is ever invoked directly from Java — the
 * Conductor orchestrates any internal sub-delegation itself based on the target
 * repository's own {@code .github/agents/} instructions.
 *
 * <h3>Command format</h3>
 * <pre>
 * copilot --allow-all --autopilot --silent
 *         --max-autopilot-continues &lt;n&gt;
 *         --agent=Conductor
 *         -p &lt;prompt&gt;
 * </pre>
 *
 * <p>{@code --autopilot} runs the CLI without interactive confirmation prompts.
 * {@code --silent} suppresses UI chrome and emits only the agent response on stdout.
 * Stdout is accumulated in {@code rawStdout} and returned as the final result; the
 * {@code agent_message_chunk} JSON-RPC path is parsed for live streaming when present.
 *
 * <h3>Concurrency guard</h3>
 * A {@link Semaphore} with capacity {@code aiqa.ai.copilot-agent.max-concurrent-agents}
 * (default 3) limits simultaneous subprocess launches. Kafka is the upstream queue;
 * messages block here rather than spawning unbounded Node.js processes.
 *
 * <h3>Timeout and cleanup</h3>
 * Each subprocess is given {@code aiqa.ai.copilot-agent.agent-timeout-seconds} (default 300 s).
 * On breach the process is forcibly destroyed. {@code destroyForcibly()} is also called in the
 * {@code finally} block as defensive cleanup — a no-op for already-terminated processes.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "aiqa.github.enabled", havingValue = "true")
public class ConductorAgentRunner {

    private static final String LOG_PREFIX = "[ConductorAgentRunner]";

    /** The only agent this runner ever invokes. */
    private static final String CONDUCTOR_AGENT = "Conductor";

    /** JSON-RPC method name emitted by the copilot CLI for streaming agent text. */
    private static final String CHUNK_METHOD = "agent_message_chunk";

    private final String       copilotCliPath;
    private final int          agentTimeoutSeconds;
    private final int          maxOutputChars;
    private final int          maxAutopilotContinues;
    private final Semaphore    semaphore;
    private final ObjectMapper objectMapper;

    public ConductorAgentRunner(AiProviderProperties props, ObjectMapper objectMapper) {
        var cfg = props.getCopilotAgent();
        this.copilotCliPath         = cfg.getCopilotCliPath();
        this.agentTimeoutSeconds    = cfg.getAgentTimeoutSeconds();
        this.maxOutputChars         = cfg.getMaxOutputChars();
        this.maxAutopilotContinues  = cfg.getMaxAutopilotContinues();
        this.semaphore              = new Semaphore(cfg.getMaxConcurrentAgents(), true);
        this.objectMapper           = objectMapper;
        log.info("{} Initialised — copilotCliPath='{}' maxConcurrent={} timeoutSec={} maxContinues={}",
                LOG_PREFIX, copilotCliPath, cfg.getMaxConcurrentAgents(),
                agentTimeoutSeconds, maxAutopilotContinues);
    }

    /**
     * Delegates a task to the Conductor agent by launching a monitored copilot subprocess.
     *
     * <p>Blocks until the subprocess exits, the timeout fires, or the calling thread is interrupted.
     * Stdout is consumed line-by-line on a virtual thread; each {@code agent_message_chunk} JSON-RPC
     * packet is logged at INFO immediately so pipeline progress is visible in real time.
     *
     * @param prompt     the prompt to execute via {@code -p}
     * @param workingDir working directory (the cloned target repo root — the Conductor agent reads
     *                   its own {@code .github/agents/} instructions from here)
     * @return assembled text from all {@code agent_message_chunk} events,
     *         or raw stdout if the CLI does not emit structured packets
     * @throws InterruptedException  if interrupted while waiting for a semaphore slot or process
     * @throws IllegalStateException if the subprocess times out or exits with a non-zero code
     */
    public String delegateToConductor(String prompt, Path workingDir)
            throws InterruptedException {

        log.info("{} Waiting for semaphore slot (available={}) agent='{}'",
                LOG_PREFIX, semaphore.availablePermits(), CONDUCTOR_AGENT);
        semaphore.acquire();
        log.info("{} Semaphore acquired (remaining={}) — launching agent='{}' workingDir='{}'",
                LOG_PREFIX, semaphore.availablePermits(), CONDUCTOR_AGENT, workingDir);

        var command = List.of(
                copilotCliPath,
                "--allow-all",
                "--autopilot",
                "--silent",
                "--max-autopilot-continues", String.valueOf(maxAutopilotContinues),
                "--agent=" + CONDUCTOR_AGENT,
                "-p", prompt
        );

        var pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
            log.info("{} Subprocess started (pid={}) agent='{}'",
                    LOG_PREFIX, process.pid(), CONDUCTOR_AGENT);
        } catch (IOException e) {
            semaphore.release();
            throw new IllegalStateException(
                    LOG_PREFIX + " Failed to start copilot subprocess for agent='"
                    + CONDUCTOR_AGENT + "': " + e.getMessage(), e);
        }

        // Assembled text from agent_message_chunk JSON-RPC events (primary response)
        var assembledText = new StringBuilder();
        // Raw stdout accumulated as fallback when no structured packets are detected
        var rawStdout     = new StringBuilder();
        var stderrBuf     = new StringBuilder();

        // ── Stdout reader: parse JSON-RPC JSONL, log live agent chunks ──────────────
        var stdoutThread = Thread.ofVirtual().name("copilot-stdout-Conductor").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (rawStdout.length() < maxOutputChars) {
                        rawStdout.append(line).append('\n');
                    }
                    extractChunkText(line, assembledText);
                }
            } catch (IOException e) {
                log.debug("{} stdout reader ended for agent='{}': {}", LOG_PREFIX, CONDUCTOR_AGENT, e.getMessage());
            }
        });

        // ── Stderr reader: always drain to prevent OS pipe-buffer deadlock ──────────
        var stderrThread = Thread.ofVirtual().name("copilot-stderr-Conductor").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderrBuf.append(line).append('\n');
                    log.debug("{} stderr agent='{}': {}", LOG_PREFIX, CONDUCTOR_AGENT, line);
                }
            } catch (IOException e) {
                log.debug("{} stderr reader ended for agent='{}': {}", LOG_PREFIX, CONDUCTOR_AGENT, e.getMessage());
            }
        });

        try {
            boolean finished = process.waitFor(agentTimeoutSeconds, TimeUnit.SECONDS);

            // Allow stream readers a short grace period to drain any buffered output
            stdoutThread.join(5_000);
            stderrThread.join(5_000);

            if (!finished) {
                log.error("{} Subprocess timed out after {}s — destroying pid={} agent='{}'",
                        LOG_PREFIX, agentTimeoutSeconds, process.pid(), CONDUCTOR_AGENT);
                process.destroyForcibly();
                throw new IllegalStateException(
                        LOG_PREFIX + " Subprocess timed out after " + agentTimeoutSeconds
                        + "s for agent='" + CONDUCTOR_AGENT + "'");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                var stderrSnippet = stderrBuf.length() > 2_000
                        ? stderrBuf.substring(0, 2_000) + "…" : stderrBuf.toString();
                log.error("{} Subprocess exited with code={} agent='{}' pid={} stderr={}",
                        LOG_PREFIX, exitCode, CONDUCTOR_AGENT, process.pid(), stderrSnippet);
                throw new IllegalStateException(
                        LOG_PREFIX + " Subprocess exited with code=" + exitCode
                        + " for agent='" + CONDUCTOR_AGENT + "'. stderr: " + stderrSnippet);
            }

            // Prefer structured chunks; fall back to raw stdout
            String result = assembledText.length() > 0
                    ? assembledText.toString().trim()
                    : rawStdout.toString().trim();

            log.info("{} agent='{}' pid={} completed — chunks={} chars rawStdout={} chars",
                    LOG_PREFIX, CONDUCTOR_AGENT, process.pid(),
                    assembledText.length(), rawStdout.length());
            return result;

        } finally {
            // Defensive cleanup: destroyForcibly() is a no-op for already-terminated processes.
            process.destroyForcibly();
            semaphore.release();
            log.info("{} Cleanup — pid={} destroyed(if alive), semaphore released (available={}) agent='{}'",
                    LOG_PREFIX, process.pid(), semaphore.availablePermits(), CONDUCTOR_AGENT);
        }
    }

    /**
     * Parses a single stdout line as a JSON-RPC packet, handling both the JSON-RPC 2.0
     * ({@code {"method":"agent_message_chunk","params":{"text":"..."}}}) and flat
     * ({@code {"type":"agent_message_chunk","text":"..."}}) formats. Found text is appended
     * to {@code target} and logged at INFO. Non-JSON lines are silently skipped.
     */
    private void extractChunkText(String line, StringBuilder target) {
        if (line == null || line.isBlank()) return;
        try {
            JsonNode node = objectMapper.readTree(line);

            String method = node.path("method").asText("");
            if (CHUNK_METHOD.equals(method)) {
                appendChunk(node.path("params").path("text").asText(""), target);
                return;
            }

            String type = node.path("type").asText("");
            if (CHUNK_METHOD.equals(type)) {
                appendChunk(node.path("text").asText(""), target);
            }

        } catch (Exception e) {
            log.debug("{} Non-JSON stdout line from agent='{}': {}", LOG_PREFIX, CONDUCTOR_AGENT, line);
        }
    }

    private void appendChunk(String text, StringBuilder target) {
        if (!text.isBlank() && target.length() < maxOutputChars) {
            target.append(text);
            log.info("{} [{}] {}", LOG_PREFIX, CONDUCTOR_AGENT, text.stripTrailing());
        }
    }

    /** Returns the number of available concurrency slots (for health/monitoring). */
    public int availableSlots() {
        return semaphore.availablePermits();
    }
}

