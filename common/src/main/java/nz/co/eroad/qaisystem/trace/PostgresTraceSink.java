package nz.co.eroad.qaisystem.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import nz.co.eroad.qaisystem.config.TraceProperties;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Database {@link TraceSink}: persists each copilot-CLI context trace as one row in the existing
 * {@code context_history} table (schema in {@code db/schema.sql}). In Kubernetes the pods are
 * ephemeral, so file traces die with the pod; this sink survives restarts so the team can later
 * optimise context selection.
 *
 * <p>Unlike the file recorder which streams to disk as it goes, this sink buffers the (redacted)
 * prompt and raw stream in memory per trace and writes exactly one row in {@link #finish}.
 *
 * <p><strong>Reliability contract:</strong> every method is best-effort. All work is wrapped in
 * try/catch, logged at WARN, and <em>never</em> throws — tracing must never break the pipeline.
 * When no {@link DataSource} is available the sink is a complete no-op.
 *
 * <p><strong>Security:</strong> the secret-redaction denylist (shared with
 * {@link ContextTraceRecorder#redact(String)}) is applied to the prompt, raw stream and final
 * output when {@code aiqa.trace.redact} is true. Logging is sizes-only — trace content is persisted
 * to the database but never written to the application log.
 */
@Slf4j
public class PostgresTraceSink implements TraceSink {

    private static final String LOG_PREFIX = "[PostgresTraceSink]";

    private static final String INSERT_SQL =
            "insert into context_history (pr_id, tenant_id, boundary, payload, char_count, token_count) "
          + "values (?, ?, ?, ?::jsonb, ?, ?)";

    private final TraceProperties props;
    private final ObjectMapper    objectMapper;
    private final JdbcTemplate    jdbc;   // null when no DataSource — the sink becomes a no-op

    /** In-flight trace buffers keyed by traceId; populated in {@link #begin}, drained in {@link #finish}. */
    private final ConcurrentHashMap<String, Buffer> buffers = new ConcurrentHashMap<>();

    public PostgresTraceSink(TraceProperties props, ObjectMapper objectMapper, DataSource dataSource) {
        this.props        = props;
        this.objectMapper = objectMapper;
        if (dataSource != null) {
            this.jdbc = new JdbcTemplate(dataSource);
            log.info("{} Initialised against configured datasource", LOG_PREFIX);
        } else {
            this.jdbc = null;
            log.warn("{} no DataSource — traces will be dropped", LOG_PREFIX);
        }
    }

    /**
     * Opens a new buffered trace and records the (redacted) prompt. Best-effort — returns a
     * disabled, no-op {@link TraceHandle} when there is no datasource or anything fails.
     */
    @Override
    public TraceHandle begin(String boundary, String taskType, String prId, String agent, String prompt) {
        if (jdbc == null) {
            return TraceHandle.disabled();
        }
        try {
            String traceId        = UUID.randomUUID().toString();
            String redactedPrompt = redactIfEnabled(prompt);
            if (redactedPrompt == null) {
                redactedPrompt = "";
            }
            Buffer buffer = new Buffer(redactedPrompt);
            TraceHandle handle = TraceHandle.forBuffering(
                    traceId, Instant.now(), boundary, taskType, prId, agent, redactedPrompt.length());
            buffers.put(traceId, buffer);
            log.debug("{} Trace started traceId={} boundary={} taskType={} prId={}",
                    LOG_PREFIX, traceId, boundary, taskType, prId);
            return handle;
        } catch (Exception e) {
            log.warn("{} begin() failed (boundary={} taskType={} prId={}): {} — tracing disabled for this run",
                    LOG_PREFIX, boundary, taskType, prId, e.getMessage());
            return TraceHandle.disabled();
        }
    }

    /**
     * Buffers one raw stdout line (redacted), honouring {@code captureRawStream} and the per-trace
     * character cap. No-op when the handle is disabled, capture is off, or the buffer is gone.
     * Best-effort — never throws.
     */
    @Override
    public void rawLine(TraceHandle handle, String line) {
        if (handle == null || !handle.isEnabled() || !props.isCaptureRawStream()) {
            return;
        }
        Buffer buffer = buffers.get(handle.traceId);
        if (buffer == null) {
            return;
        }
        try {
            appendCapped(buffer, redactIfEnabled(line));
        } catch (Exception e) {
            log.warn("{} rawLine() failed for traceId={}: {}", LOG_PREFIX, handle.traceId, e.getMessage());
        }
    }

    /**
     * Drains the buffer and writes exactly one {@code context_history} row. No-op for a disabled
     * handle or a missing buffer. Best-effort — logged at WARN (sizes only) and never throws.
     */
    @Override
    public void finish(TraceHandle handle, String finalOutput, int exitCode, boolean success) {
        if (handle == null || !handle.isEnabled()) {
            return;
        }
        Buffer buffer = buffers.remove(handle.traceId);
        if (buffer == null) {
            return;
        }
        try {
            String redactedFinal = redactIfEnabled(finalOutput);
            if (redactedFinal == null) {
                redactedFinal = "";
            }
            long   latencyMs   = Duration.between(handle.start, Instant.now()).toMillis();
            String payloadJson = buildPayload(handle, buffer, redactedFinal, exitCode, success, latencyMs);
            int    charCount   = clampToInt(
                    (long) buffer.prompt.length() + buffer.rawStream.length() + redactedFinal.length());

            if (jdbc == null) {   // defensive: begin() returns disabled when jdbc is null, so unreachable
                return;
            }
            jdbc.update(INSERT_SQL, handle.prId, null, handle.boundary, payloadJson, charCount, null);
            log.info("{} persisted trace pr_id='{}' boundary='{}' chars={}",
                    LOG_PREFIX, handle.prId, handle.boundary, charCount);
        } catch (Exception e) {
            log.warn("{} finish() failed for traceId={} prId='{}': {}",
                    LOG_PREFIX, handle.traceId, handle.prId, e.getMessage());
        }
    }

    // ─── Pure, unit-testable helpers (no DataSource required) ─────────────────────

    /**
     * Builds the {@code context_history.payload} JSON: the redacted {@code prompt}, {@code rawStream}
     * and {@code final} text plus a {@code meta} object of sizes/identity. Pure — no I/O.
     */
    String buildPayload(TraceHandle handle, Buffer buffer, String redactedFinal,
                        int exitCode, boolean success, long latencyMs) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("prompt",    buffer.prompt);
        root.put("rawStream", buffer.rawStream.toString());
        root.put("final",     redactedFinal);

        ObjectNode meta = root.putObject("meta");
        meta.put("traceId",        handle.traceId);
        meta.put("boundary",       handle.boundary);
        meta.put("taskType",       handle.taskType);
        meta.put("prId",           handle.prId);
        meta.put("agent",          handle.agent);
        meta.put("promptChars",    handle.promptChars);
        meta.put("rawStreamChars", buffer.rawStream.length());
        meta.put("finalChars",     redactedFinal.length());
        meta.put("latencyMs",      latencyMs);
        meta.put("exitCode",       exitCode);
        meta.put("success",        success);
        meta.put("capped",         buffer.capped);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * Appends one raw line (plus a newline) to the buffer, honouring {@code maxRawStreamChars}.
     * When the cap would be exceeded only the remaining budget is appended, {@code capped} is set,
     * and a single truncation marker is written. {@code cap <= 0} means unlimited. Pure — no I/O.
     */
    void appendCapped(Buffer buffer, String line) {
        String unit = (line == null ? "" : line) + "\n";
        int    cap  = props.getMaxRawStreamChars();

        if (cap <= 0) {                              // unlimited
            buffer.rawStream.append(unit);
            buffer.rawChars += unit.length();
            return;
        }
        if (buffer.capped) {                         // marker already written — drop further input
            return;
        }
        long remaining = (long) cap - buffer.rawChars;
        if (remaining <= 0) {                        // no budget left
            appendCapMarker(buffer, cap);
            return;
        }
        if (unit.length() <= remaining) {            // fits whole
            buffer.rawStream.append(unit);
            buffer.rawChars += unit.length();
        } else {                                     // partial fill up to the budget, then cap
            buffer.rawStream.append(unit, 0, (int) remaining);
            buffer.rawChars += remaining;
            appendCapMarker(buffer, cap);
        }
    }

    private void appendCapMarker(Buffer buffer, int cap) {
        buffer.rawStream.append("\n…[RAW STREAM CAPPED at ").append(cap).append(" chars]…\n");
        buffer.capped = true;
    }

    /** Applies the shared secret-redaction denylist when {@code aiqa.trace.redact} is true; null-safe. */
    String redactIfEnabled(String s) {
        if (s == null) {
            return null;
        }
        return props.isRedact() ? ContextTraceRecorder.redact(s) : s;
    }

    /** Saturating long→int for {@code char_count} so an absurdly large trace can never overflow. */
    private static int clampToInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    // ─── Buffer ───────────────────────────────────────────────────────────────────

    /** Per-trace accumulator: the already-redacted prompt plus the redacted raw stream and cap state. */
    static final class Buffer {
        final String        prompt;                  // already redacted
        final StringBuilder rawStream = new StringBuilder();
        long                rawChars;                 // raw-stream chars counted against the cap
        boolean             capped;                   // whether the cap was hit and the marker appended

        Buffer(String prompt) {
            this.prompt = prompt;
        }
    }
}
