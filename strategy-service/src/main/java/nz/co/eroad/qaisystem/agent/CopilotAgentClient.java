package nz.co.eroad.qaisystem.agent;

import lombok.extern.slf4j.Slf4j;
import nz.co.eroad.qaisystem.config.AiProviderProperties;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Launches a {@code copilot} subprocess and captures its response.
 *
 * <p>Command format: {@code copilot -p "$prompt" --agent="$agentName" --silent --yolo}
 *
 * <h3>Concurrency guard</h3>
 * A {@link Semaphore} with capacity {@code aiqa.ai.copilot-agent.max-concurrent-agents}
 * (default 3) limits simultaneous subprocess launches. Kafka is the upstream queue;
 * messages block here rather than spawning unbounded Node.js processes.
 *
 * <h3>Timeout</h3>
 * Each subprocess is given {@code aiqa.ai.copilot-agent.agent-timeout-seconds}
 * (default 300 s). On breach the process is forcibly destroyed and an
 * {@link IllegalStateException} is thrown.
 */
@Slf4j
@Service
public class CopilotAgentClient {

    private static final String LOG_PREFIX = "[CopilotAgentClient]";

    private final String    copilotCliPath;
    private final int       agentTimeoutSeconds;
    private final int       maxOutputChars;
    private final Semaphore semaphore;

    public CopilotAgentClient(AiProviderProperties props) {
        var cfg = props.getCopilotAgent();
        this.copilotCliPath      = cfg.getCopilotCliPath();
        this.agentTimeoutSeconds = cfg.getAgentTimeoutSeconds();
        this.maxOutputChars      = cfg.getMaxOutputChars();
        this.semaphore           = new Semaphore(cfg.getMaxConcurrentAgents(), true);
        log.info("{} Initialised — copilotCliPath='{}' maxConcurrent={} timeoutSec={}",
                LOG_PREFIX, copilotCliPath, cfg.getMaxConcurrentAgents(), agentTimeoutSeconds);
    }

    /**
     * Runs a copilot agent subprocess and returns the captured stdout.
     *
     * @param agentName  the agent to select (e.g. {@code "Conductor"}, {@code "TestPlanner"})
     * @param prompt     the prompt to pass via {@code -p}
     * @param workingDir the working directory for the subprocess (cloned target repo root)
     * @return trimmed stdout from the agent
     * @throws InterruptedException if the calling thread is interrupted while waiting for
     *                              a semaphore slot or for the process to finish
     * @throws IllegalStateException if the subprocess times out or exits with a non-zero code
     */
    public String runAgent(String agentName, String prompt, Path workingDir)
            throws InterruptedException {
        int available = semaphore.availablePermits();
        log.info("{} Waiting for semaphore slot (available={}) agent='{}'",
                LOG_PREFIX, available, agentName);
        semaphore.acquire();
        log.info("{} Semaphore acquired (remaining={}) — launching agent='{}'  workingDir='{}'",
                LOG_PREFIX, semaphore.availablePermits(), agentName, workingDir);

        var command = List.of(copilotCliPath, "-p", prompt,
                "--agent=" + agentName, "--silent", "--yolo");

        var pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            semaphore.release();
            throw new IllegalStateException(
                    LOG_PREFIX + " Failed to start copilot subprocess for agent='" + agentName
                    + "': " + e.getMessage(), e);
        }

        var stdoutBuf = new StringBuilder();
        var stderrBuf = new StringBuilder();

        // Read stdout on virtual thread — cap at maxOutputChars to guard against run-away output
        var stdoutThread = Thread.ofVirtual().start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (stdoutBuf.length() < maxOutputChars) {
                        stdoutBuf.append(line).append('\n');
                    }
                }
            } catch (IOException e) {
                log.debug("{} stdout reader interrupted: {}", LOG_PREFIX, e.getMessage());
            }
        });

        // Read stderr on virtual thread — always drain to prevent buffer deadlock
        var stderrThread = Thread.ofVirtual().start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderrBuf.append(line).append('\n');
                }
            } catch (IOException e) {
                log.debug("{} stderr reader interrupted: {}", LOG_PREFIX, e.getMessage());
            }
        });

        try {
            boolean finished = process.waitFor(agentTimeoutSeconds, TimeUnit.SECONDS);
            stdoutThread.join(5_000);
            stderrThread.join(5_000);

            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        LOG_PREFIX + " Subprocess timed out after " + agentTimeoutSeconds
                        + "s for agent='" + agentName + "'");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                var stderrSnippet = stderrBuf.length() > 2_000
                        ? stderrBuf.substring(0, 2_000) + "…" : stderrBuf.toString();
                log.error("{} Subprocess exited with code={} agent='{}' stderr={}",
                        LOG_PREFIX, exitCode, agentName, stderrSnippet);
                throw new IllegalStateException(
                        LOG_PREFIX + " Subprocess exited with code=" + exitCode
                        + " for agent='" + agentName + "'. stderr: " + stderrSnippet);
            }

            log.debug("{} stderr for agent='{}': {}", LOG_PREFIX, agentName, stderrBuf);
            log.info("{} agent='{}' completed — stdout={} chars",
                    LOG_PREFIX, agentName, stdoutBuf.length());
            return stdoutBuf.toString().trim();

        } finally {
            semaphore.release();
            log.info("{} Semaphore released (available={}) agent='{}'",
                    LOG_PREFIX, semaphore.availablePermits(), agentName);
        }
    }

    /** Returns the number of available concurrency slots (for health/monitoring). */
    public int availableSlots() {
        return semaphore.availablePermits();
    }
}


