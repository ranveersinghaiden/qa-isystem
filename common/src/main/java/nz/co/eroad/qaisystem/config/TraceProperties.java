package nz.co.eroad.qaisystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the off-by-default "Context Trace" capture feature.
 *
 * <p>Bound from {@code aiqa.trace.*}. When {@link #enabled} is {@code false} (the
 * default) no {@code ContextTraceRecorder} bean is created and the
 * {@code copilot}-CLI boundary behaves exactly as before — zero behaviour change.
 *
 * <p>When enabled, each Conductor copilot-CLI invocation persists three artifacts
 * (the exact prompt we send, the full raw JSON-RPC stdout stream, and the final
 * parsed output) so the team can later optimise context selection.
 *
 * <p><strong>Security &amp; privacy:</strong> trace artifacts may contain target-repo
 * source code, diffs, prompts and agent output. Treat {@link #dir} as
 * <em>sensitive</em>: the default location lives under {@code ./logs/} which is
 * {@code .gitignored}, and traces must <em>never</em> be committed to version control
 * nor synced to any cloud store. A secret-redaction denylist is applied by default
 * ({@link #redact}); it is best-effort and not a substitute for treating the directory
 * as confidential.
 */
@Data
@ConfigurationProperties(prefix = "aiqa.trace")
public class TraceProperties {

    /**
     * Master switch. When {@code false} (default) the recorder bean is absent and the
     * pipeline is completely unaffected.
     */
    private boolean enabled = false;

    /** Root directory for trace artifacts. Default {@code ./logs/context-traces} (gitignored). */
    private String dir = "./logs/context-traces";

    /**
     * Capture the full raw JSON-RPC stdout stream to {@code raw-stream.jsonl}. This is the
     * high-value artifact: today the runner caps stdout at {@code maxOutputChars} and discards
     * the remainder.
     */
    private boolean captureRawStream = true;

    /**
     * SECURITY: hard cap on characters written to the raw stream per trace, to prevent disk
     * exhaustion from a runaway subprocess. {@code 0} = unlimited (discouraged). Default 5,000,000.
     */
    private int maxRawStreamChars = 5_000_000;

    /** Apply the secret-redaction denylist to all written artifacts. Default {@code true}. */
    private boolean redact = true;
}
