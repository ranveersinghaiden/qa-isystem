package nz.co.eroad.qaisystem.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import nz.co.eroad.qaisystem.config.AgentScalingProperties;
import nz.co.eroad.qaisystem.config.AiProviderProperties;
import nz.co.eroad.qaisystem.execution.WorkspacePool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Launches a {@code copilot} subprocess that always delegates the task to the
 * target repository's own Conductor agent, monitored from this Java process.
 *
 * <p>QA-ISystem is a pure orchestrator. It never modifies the target repo's agent
 * definitions and never injects files into it. The target repo <strong>must</strong>
 * contain {@code .github/agents/Conductor.md} — if it does not, every delegation
 * attempt will fail fast with a clear error message telling the operator what to add.
 *
 * <p>The Conductor agent is the single entry point for all BDD scenario and test
 * code generation. No other agent is ever invoked directly from Java.
 *
 * <h3>Scaling</h3>
 * A {@link Semaphore} sized by {@code aiqa.agent.max-concurrent} bounds simultaneous
 * subprocess launches per instance, and a {@link WorkspacePool} hands each run an isolated
 * git worktree so concurrent agents never share a working directory. Horizontal scale is
 * achieved by running multiple instances in the same Kafka consumer group. Metrics are
 * published to Micrometer ({@code aiqa.agent.*}) to drive autoscaling.
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

    private final String         copilotCliPath;
    private final int            agentTimeoutSeconds;
    private final int            maxOutputChars;
    private final int            maxAutopilotContinues;
    private final boolean        headroomEnabled;
    private final String         headroomProviderBaseUrl;  // null when disabled or username blank
    private final Semaphore      semaphore;
    private final ObjectMapper   objectMapper;
    private final WorkspacePool  workspacePool;
    private final Timer          latencyTimer;   // null when no MeterRegistry is present

    public ConductorAgentRunner(AiProviderProperties props,
                                AgentScalingProperties scaling,
                                WorkspacePool workspacePool,
                                ObjectMapper objectMapper,
                                ObjectProvider<MeterRegistry> meterRegistryProvider) {
        var cfg = props.getCopilotAgent();
        this.copilotCliPath        = cfg.getCopilotCliPath();
        this.agentTimeoutSeconds   = cfg.getAgentTimeoutSeconds();
        this.maxOutputChars        = cfg.getMaxOutputChars();
        this.maxAutopilotContinues = cfg.getMaxAutopilotContinues();
        this.headroomEnabled          = props.getHeadroom().isEnabled();
        this.headroomProviderBaseUrl  = props.getHeadroom().providerBaseUrl();
        this.workspacePool         = workspacePool;
        this.objectMapper          = objectMapper;

        final int permits = scaling.effectiveMaxConcurrent();
        this.semaphore = new Semaphore(permits, true);

        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry != null) {
            Gauge.builder("aiqa.agent.slots.available", semaphore, Semaphore::availablePermits)
                    .description("Free copilot agent concurrency slots on this instance")
                    .register(registry);
            Gauge.builder("aiqa.agent.slots.max", this, r -> permits)
                    .description("Configured max concurrent copilot agents on this instance")
                    .register(registry);
            Gauge.builder("aiqa.agent.workspace.available", workspacePool, WorkspacePool::availableCount)
                    .description("Free isolated worktrees in the workspace pool")
                    .register(registry);
            this.latencyTimer = Timer.builder("aiqa.agent.latency")
                    .description("Wall-clock duration of a Conductor agent subprocess run")
                    .publishPercentileHistogram()
                    .register(registry);
        } else {
            this.latencyTimer = null;
        }

        log.info("{} Initialised - copilotCliPath='{}' maxConcurrent={} timeoutSec={} maxContinues={} headroom={} metrics={}",
                LOG_PREFIX, copilotCliPath, permits, agentTimeoutSeconds, maxAutopilotContinues,
                headroomEnabled && headroomProviderBaseUrl != null
                        ? "enabled(providerBaseUrl=" + headroomProviderBaseUrl + ")"
                        : headroomEnabled ? "enabled(WARNING: github-username not set)" : "disabled",
                registry != null);
    }

    /**
     * Delegates a task to the Conductor agent: acquires a concurrency slot, borrows an isolated
     * workspace from the {@link WorkspacePool}, runs the monitored subprocess, then releases both.
     *
     * @param prompt the prompt to execute via {@code -p}
     * @return assembled agent text (or raw stdout if no structured packets)
     * @throws InterruptedException  if interrupted while waiting for a slot/workspace/process
     * @throws IllegalStateException if the subprocess times out or exits non-zero
     */
    public String delegateToConductor(String prompt) throws InterruptedException {
        log.info("{} Waiting for slot (available={}) agent='{}'",
                LOG_PREFIX, semaphore.availablePermits(), CONDUCTOR_AGENT);
        semaphore.acquire();

        Path workspace;
        try {
            workspace = workspacePool.lease();
        } catch (InterruptedException e) {
            semaphore.release();
            throw e;
        }

        validateConductorAgent(workspace);

        log.info("{} Slot acquired (remaining={}) workspace='{}' - launching agent='{}'",
                LOG_PREFIX, semaphore.availablePermits(), workspace, CONDUCTOR_AGENT);
        try {
            return timed(prompt, workspace);
        } finally {
            workspacePool.release(workspace);
            semaphore.release();
            log.info("{} Released slot+workspace (available={}) agent='{}'",
                    LOG_PREFIX, semaphore.availablePermits(), CONDUCTOR_AGENT);
        }
    }

    private String timed(String prompt, Path workingDir) throws InterruptedException {
        long start = System.nanoTime();
        try {
            return runConductorProcess(prompt, workingDir);
        } finally {
            if (latencyTimer != null) {
                latencyTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
        }
    }

    /** Launches and monitors the copilot subprocess in {@code workingDir}. Caller owns the slot. */
    private String runConductorProcess(String prompt, Path workingDir) throws InterruptedException {
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

        // Route copilot's outbound LLM calls through the headroom proxy for context
        // compression.  The Copilot CLI reads COPILOT_PROVIDER_* env vars to override
        // its default GitHub Copilot API endpoint.
        //
        // COPILOT_PROVIDER_BASE_URL is user-specific: http://127.0.0.1:{port}/p/{username}/v1
        // Headroom registers this route when `headroom device add copilot` is run once on
        // the host; credentials are persisted in ~/.headroom (mounted into the container).
        if (headroomEnabled && headroomProviderBaseUrl != null) {
            pb.environment().put("COPILOT_PROVIDER_TYPE",     "openai");
            pb.environment().put("COPILOT_PROVIDER_BASE_URL", headroomProviderBaseUrl);
            pb.environment().put("COPILOT_PROVIDER_WIRE_API", "completions");
            pb.environment().put("COPILOT_AUTH_MODE",         "github-oauth");
            log.debug("{} Headroom compression active — COPILOT_PROVIDER_BASE_URL='{}'",
                    LOG_PREFIX, headroomProviderBaseUrl);
        } else if (headroomEnabled) {
            log.warn("{} Headroom enabled but aiqa.ai.headroom.github-username is blank — " +
                     "copilot will call the LLM API directly. Set HEADROOM_GITHUB_USERNAME.",
                    LOG_PREFIX);
        }

        Process process;
        try {
            process = pb.start();
            log.info("{} Subprocess started (pid={}) agent='{}' workingDir='{}'",
                    LOG_PREFIX, process.pid(), CONDUCTOR_AGENT, workingDir);
        } catch (IOException e) {
            throw new IllegalStateException(
                    LOG_PREFIX + " Failed to start copilot subprocess for agent='"
                    + CONDUCTOR_AGENT + "': " + e.getMessage(), e);
        }

        var assembledText = new StringBuilder();
        var rawStdout     = new StringBuilder();
        var stderrBuf     = new StringBuilder();

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
            stdoutThread.join(5_000);
            stderrThread.join(5_000);

            if (!finished) {
                log.error("{} Subprocess timed out after {}s - destroying pid={} agent='{}'",
                        LOG_PREFIX, agentTimeoutSeconds, process.pid(), CONDUCTOR_AGENT);
                process.destroyForcibly();
                throw new IllegalStateException(
                        LOG_PREFIX + " Subprocess timed out after " + agentTimeoutSeconds
                        + "s for agent='" + CONDUCTOR_AGENT + "'");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                var stderrSnippet = stderrBuf.length() > 2_000
                        ? stderrBuf.substring(0, 2_000) + "..." : stderrBuf.toString();
                log.error("{} Subprocess exited with code={} agent='{}' pid={} stderr={}",
                        LOG_PREFIX, exitCode, CONDUCTOR_AGENT, process.pid(), stderrSnippet);
                throw new IllegalStateException(
                        LOG_PREFIX + " Subprocess exited with code=" + exitCode
                        + " for agent='" + CONDUCTOR_AGENT + "'. stderr: " + stderrSnippet);
            }

            String result = assembledText.length() > 0
                    ? assembledText.toString().trim()
                    : rawStdout.toString().trim();

            log.info("{} agent='{}' pid={} completed - chunks={} chars rawStdout={} chars",
                    LOG_PREFIX, CONDUCTOR_AGENT, process.pid(),
                    assembledText.length(), rawStdout.length());
            return result;

        } finally {
            process.destroyForcibly();
        }
    }

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

    // ─── Target repo contract validation ────────────────────────────────────────

    /**
     * Asserts that the target repo workspace contains a Conductor agent definition at
     * {@code .github/agents/Conductor.md}.
     *
     * <p>QA-ISystem is a pure orchestrator and <em>never</em> modifies or adds files to
     * the target repo. The Conductor agent is the target repo's own responsibility. If it
     * is missing, the operator must add it before QA-ISystem can generate BDD or test code
     * for that repository.
     *
     * @throws IllegalStateException if the agent definition file is absent
     */
    private void validateConductorAgent(Path workspace) {
        Path agentMd = workspace.resolve(".github").resolve("agents")
                                .resolve(CONDUCTOR_AGENT + ".md");
        if (!Files.exists(agentMd)) {
            throw new IllegalStateException(
                    LOG_PREFIX + " Target repo workspace '" + workspace
                    + "' is missing .github/agents/" + CONDUCTOR_AGENT + ".md. "
                    + "QA-ISystem never adds agents to target repos — add a Conductor agent "
                    + "to the target repository and re-deploy.");
        }
        log.debug("{} Conductor agent confirmed at '{}'", LOG_PREFIX, agentMd);
    }
}
