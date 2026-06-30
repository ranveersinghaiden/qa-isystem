package nz.co.eroad.qaisystem.trace;

import java.io.BufferedWriter;
import java.time.Instant;
import java.nio.file.Path;

/**
 * Mutable per-invocation handle shared by every {@link TraceSink}. Holds an open
 * {@link BufferedWriter} and a running character counter (for the file sink), so it is
 * intentionally <em>not</em> a record. A disabled handle (see {@link #disabled()}) makes every
 * sink method a no-op. Fields are package-private so the sinks and same-package tests can read
 * them; external callers treat it as opaque.
 *
 * <p>For non-file sinks (e.g. Postgres) the {@link #forBuffering} factory produces an
 * identity-only handle with no {@code dir}/{@code root}/{@code rawWriter}.
 */
public final class TraceHandle {

    final boolean        enabled;
    final String         traceId;
    final Path           dir;
    final Path           root;
    final Instant        start;
    final String         boundary;
    final String         taskType;
    final String         prId;
    final String         agent;
    final BufferedWriter rawWriter;          // nullable
    final int            promptChars;

    long    rawStreamChars;                  // running count of chars written to raw stream
    boolean truncationMarkerWritten;         // ensures the cap marker is written at most once

    private TraceHandle(boolean enabled, String traceId, Path dir, Path root, Instant start,
                        String boundary, String taskType, String prId, String agent,
                        BufferedWriter rawWriter, int promptChars) {
        this.enabled     = enabled;
        this.traceId     = traceId;
        this.dir         = dir;
        this.root        = root;
        this.start       = start;
        this.boundary    = boundary;
        this.taskType    = taskType;
        this.prId        = prId;
        this.agent       = agent;
        this.rawWriter   = rawWriter;
        this.promptChars = promptChars;
    }

    /** A no-op handle: every sink method short-circuits on it. */
    static TraceHandle disabled() {
        return new TraceHandle(false, null, null, null, null, null, null, null, null, null, 0);
    }

    /** Identity-only handle for non-file sinks (e.g. Postgres): no rawWriter/dir, just trace identity. */
    static TraceHandle forBuffering(String traceId, java.time.Instant start, String boundary,
                                    String taskType, String prId, String agent, int promptChars) {
        return new TraceHandle(true, traceId, null, null, start, boundary, taskType, prId, agent, null, promptChars);
    }

    /** Full file-sink handle: carries the resolved dir/root and the open raw writer for {@link ContextTraceRecorder}. */
    static TraceHandle forFile(String traceId, Path dir, Path root, Instant start, String boundary,
                               String taskType, String prId, String agent,
                               BufferedWriter rawWriter, int promptChars) {
        return new TraceHandle(true, traceId, dir, root, start, boundary, taskType, prId, agent, rawWriter, promptChars);
    }

    /** Whether this handle represents an active trace. */
    public boolean isEnabled() {
        return enabled;
    }
}
