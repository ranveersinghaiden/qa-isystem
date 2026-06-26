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

        // Contract check: the target repo must own its Conductor agent definition.
        // QA-ISystem is a pure orchestrator and never adds files to the target repo.
        // Accept any file matching Conductor*.md (e.g. Conductor.md, Conductor.agent.md).
        Path conductorMd = findConductorAgentFile(basePath.resolve(".github").resolve("agents"));
        if (conductorMd == null) {
            throw new IllegalStateException(
                    LOG_PREFIX + " TARGET REPO CONTRACT VIOLATION: " +
                    "base clone '" + basePath + "' has no .github/agents/Conductor*.md. " +
                    "QA-ISystem cannot start without a Conductor agent in the target repository. " +
                    "Add .github/agents/Conductor.md (or Conductor.agent.md) to the target repo and re-deploy.");
        }
        log.info("{} Conductor agent confirmed in target repo at '{}'", LOG_PREFIX, conductorMd);

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
     *
     * <p>Before returning a worktree, resets it to {@code origin/{branch}} so that any committed
     * files added to the target repo since the worktrees were first created (e.g. a
     * {@code .github/agents/Conductor*.md} file) are present in the working directory. The
     * worktrees share the same {@code .git} object store as the base clone, so remote refs
     * updated by a {@code git fetch}/{@code git pull} on the base clone are immediately visible
     * here without a separate network call.
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
        syncWorktreeToBase(ws);
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

    // ─── Agent file helpers ────────────────────────────────────────────────────

    /**
     * Resets a worktree to {@code origin/{branch}} so it mirrors the base clone's remote state.
     * Called in {@link #lease()} before every agent run, ensuring committed files (e.g. the
     * Conductor agent definition) are present even when worktrees were created from an older HEAD.
     * Non-fatal: logs and continues if the reset fails.
     */
    private void syncWorktreeToBase(Path ws) {
        String branch = props.getBranch();
        if (branch == null || branch.isBlank()) {
            log.debug("{} Branch name unavailable — skipping worktree sync for '{}'", LOG_PREFIX, ws);
            return;
        }
        String ref = "origin/" + branch;
        runGitQuiet(ws, "git", "reset", "--hard", ref);
        log.debug("{} Worktree '{}' reset to {}", LOG_PREFIX, ws, ref);
    }

    /**
     * Finds the first file matching {@code Conductor*.md} inside {@code agentsDir}.
     * Accepts {@code Conductor.md}, {@code Conductor.agent.md}, or any other variant
     * the target repo chooses to use.
     *
     * @return the matched {@link Path}, or {@code null} if none found
     */
    private Path findConductorAgentFile(Path agentsDir) {
        if (!Files.isDirectory(agentsDir)) {
            return null;
        }
        try (var entries = Files.newDirectoryStream(agentsDir, "Conductor*.md")) {
            for (Path p : entries) {
                return p;
            }
        } catch (IOException e) {
            log.debug("{} Could not scan '{}' for Conductor*.md: {}", LOG_PREFIX, agentsDir, e.getMessage());
        }
        return null;
    }
}

