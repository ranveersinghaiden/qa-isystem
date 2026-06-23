package nz.co.eroad.qaisystem.execution;

import nz.co.eroad.qaisystem.config.AgentScalingProperties;
import nz.co.eroad.qaisystem.config.TargetRepoProperties;
import nz.co.eroad.qaisystem.service.RepoContextService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Pool of isolated {@code git worktree} working directories, one per concurrent agent run.
 *
 * <p>Each {@code copilot} agent must run in its own working directory; sharing a single clone
 * across concurrent agents causes file races and {@code git pull}-mid-run corruption. This pool
 * creates {@code aiqa.agent.workspace-pool-size} detached worktrees off the base clone (which
 * shares the base object store, so worktrees are cheap) and hands them out via {@link #lease()}
 * / {@link #release(Path)}.
 *
 * <h3>Graceful degradation</h3>
 * If worktrees cannot be created (git too old, base clone missing, isolation disabled), the pool
 * enters <em>degraded</em> mode and every lease returns the shared base clone path. This keeps the
 * pipeline working (at the cost of safe concurrency) rather than failing hard.
 *
 * <p>Only created when {@code aiqa.github.enabled=true}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "aiqa.github.enabled", havingValue = "true")
public class WorkspacePool {

    private static final String LOG_PREFIX = "[WorkspacePool]";

    private final TargetRepoProperties    props;
    private final AgentScalingProperties  scaling;
    private final RepoContextService      repoContextService;

    private final BlockingQueue<Path> available = new LinkedBlockingQueue<>();
    private final List<Path> allWorktrees = new ArrayList<>();
    private volatile boolean degraded = false;
    private Path basePath;

    @PostConstruct
    void init() {
        basePath = repoContextService.getLocalRepoPath();

        if (!scaling.isIsolatedWorkspaces()) {
            log.warn("{} isolatedWorkspaces=false — running degraded (shared base dir '{}'). " +
                    "Safe only at concurrency 1.", LOG_PREFIX, basePath);
            degraded = true;
            return;
        }
        if (!Files.exists(basePath.resolve(".git"))) {
            log.warn("{} Base clone '{}' has no .git — running degraded (shared base dir). " +
                    "Configure aiqa.target-repo.url so the repo is cloned.", LOG_PREFIX, basePath);
            degraded = true;
            return;
        }

        int size = scaling.effectiveWorkspacePoolSize();
        String baseName = basePath.getFileName().toString();
        Path parent = basePath.getParent();

        // Best-effort cleanup of stale worktrees from a previous run
        runGitQuiet(basePath, "git", "worktree", "prune");

        for (int i = 0; i < size; i++) {
            Path ws = parent.resolve("qa-ws-" + baseName + "-" + i);
            try {
                if (Files.exists(ws)) {
                    runGitQuiet(basePath, "git", "worktree", "remove", "--force", ws.toString());
                }
                runGit(basePath, "git", "worktree", "add", "--detach", ws.toString());
                available.offer(ws);
                allWorktrees.add(ws);
            } catch (Exception e) {
                log.warn("{} Could not create worktree '{}': {} — continuing with fewer slots",
                        LOG_PREFIX, ws, e.getMessage());
            }
        }

        if (available.isEmpty()) {
            log.warn("{} No worktrees could be created — running degraded (shared base dir '{}')",
                    LOG_PREFIX, basePath);
            degraded = true;
        } else {
            log.info("{} Initialised {} isolated worktree(s) off base '{}'",
                    LOG_PREFIX, available.size(), basePath);
        }
    }

    /**
     * Borrows an isolated working directory. Blocks until one is free (bounded in practice by the
     * agent concurrency semaphore). In degraded mode returns the shared base clone path immediately.
     */
    public Path lease() throws InterruptedException {
        if (degraded) return basePath;
        Path ws = available.poll(scaling.getWorkspaceLeaseTimeoutSeconds(), TimeUnit.SECONDS);
        if (ws == null) {
            log.warn("{} No free worktree after {}s — falling back to shared base dir '{}' " +
                    "(possible workspace leak)", LOG_PREFIX,
                    scaling.getWorkspaceLeaseTimeoutSeconds(), basePath);
            return basePath;
        }
        return ws;
    }

    /** Returns a borrowed working directory to the pool. No-op for the shared base path. */
    public void release(Path ws) {
        if (degraded || ws == null || ws.equals(basePath)) return;
        available.offer(ws);
    }

    /** Number of currently free worktrees (for metrics / health). */
    public int availableCount() {
        return degraded ? 0 : available.size();
    }

    /** Total worktrees managed by the pool. */
    public int totalCount() {
        return degraded ? 0 : allWorktrees.size();
    }

    public boolean isDegraded() {
        return degraded;
    }

    @PreDestroy
    void cleanup() {
        for (Path ws : allWorktrees) {
            runGitQuiet(basePath, "git", "worktree", "remove", "--force", ws.toString());
        }
        runGitQuiet(basePath, "git", "worktree", "prune");
    }

    // ─── git helpers ───────────────────────────────────────────────────────────

    private void runGit(Path workDir, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        boolean done = p.waitFor(60, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new IOException("git command timed out: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) {
            throw new IOException("git command failed (exit=" + p.exitValue() + "): "
                    + String.join(" ", cmd) + " — " + output.strip());
        }
    }

    private void runGitQuiet(Path workDir, String... cmd) {
        try {
            runGit(workDir, cmd);
        } catch (Exception e) {
            log.debug("{} git command non-fatal failure '{}': {}",
                    LOG_PREFIX, String.join(" ", cmd), e.getMessage());
        }
    }
}

