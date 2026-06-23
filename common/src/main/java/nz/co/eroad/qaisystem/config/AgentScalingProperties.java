package nz.co.eroad.qaisystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Horizontal- and vertical-scaling knobs for the copilot agent layer.
 *
 * <p>Bound from {@code aiqa.agent.*}. These values size the per-instance agent
 * concurrency, the isolated workspace pool, and the Kafka listener concurrency so that
 * one instance never launches more {@code copilot} subprocesses than the host can sustain.
 *
 * <h3>Capacity guidance</h3>
 * Each {@code copilot} subprocess is a Node.js process (~300–500&nbsp;MB RSS), long-lived
 * (≈1.5–3.5&nbsp;min) and network/LLM-bound. A safe per-instance ceiling is roughly:
 * <pre>
 *   maxConcurrent ≈ min( floor(RAM_GB / 0.5), cores × 1.5, Copilot_API_rate_budget )
 * </pre>
 * Horizontal scale is achieved by running multiple instances in the same Kafka consumer
 * group (see the KEDA manifests under {@code k8s/keda/}); total throughput =
 * {@code instances × maxConcurrent / agentLatency}.
 */
@Data
@Component
@ConfigurationProperties(prefix = "aiqa.agent")
public class AgentScalingProperties {

    /**
     * Maximum simultaneous {@code copilot} subprocesses on this instance. Also used as the
     * Kafka listener concurrency for the agent-bound consumers (one in-flight agent per
     * consumer thread). Set to {@code 0} for an automatic value derived from CPU cores.
     * Default {@code 3}.
     */
    private int maxConcurrent = 3;

    /**
     * Number of isolated git worktrees kept in the {@code WorkspacePool}. {@code 0} (default)
     * means "match {@link #maxConcurrent}" so every concurrent agent gets its own working
     * directory. Lower values force agents to share dirs (not recommended).
     */
    private int workspacePoolSize = 0;

    /**
     * When {@code true} (default) each agent run executes in its own pooled git worktree,
     * eliminating shared-working-directory races. When {@code false} all agents share the
     * single base clone directory (legacy behaviour — only safe at concurrency 1).
     */
    private boolean isolatedWorkspaces = true;

    /** Seconds a borrowed workspace may be held before the pool logs a leak warning. */
    private long workspaceLeaseTimeoutSeconds = 600;

    /** Resolves the effective concurrency, expanding {@code 0} to a CPU-derived value. */
    public int effectiveMaxConcurrent() {
        if (maxConcurrent > 0) return maxConcurrent;
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(cores, 8));
    }

    /** Resolves the effective workspace pool size, defaulting to the effective concurrency. */
    public int effectiveWorkspacePoolSize() {
        return workspacePoolSize > 0 ? workspacePoolSize : effectiveMaxConcurrent();
    }
}

