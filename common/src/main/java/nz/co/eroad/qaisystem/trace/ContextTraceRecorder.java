package nz.co.eroad.qaisystem.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nz.co.eroad.qaisystem.config.TraceProperties;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persists, per Conductor copilot-CLI invocation, three artifacts so the team can later
 * optimise context selection:
 * <ol>
 *   <li>{@code prompt.txt} — the exact prompt we send to the agent;</li>
 *   <li>{@code raw-stream.jsonl} — the full raw JSON-RPC stdout stream from the copilot
 *       subprocess (the high-value piece, normally capped and discarded by the runner);</li>
 *   <li>{@code final.txt} — the final parsed agent output.</li>
 * </ol>
 * plus a {@code meta.json} per trace and an appended summary line in
 * {@code trace-index.jsonl} at the trace root.
 *
 * <p>This is the {@code file} {@link TraceSink}, wired by {@link TraceConfig} only when
 * {@code aiqa.trace.enabled=true} and {@code aiqa.trace.sink=file} (the default); when tracing is
 * disabled there is no sink and the pipeline is unaffected.
 *
 * <p><strong>Reliability contract:</strong> every public method is best-effort. All I/O is
 * wrapped in try/catch, logged at WARN, and <em>never</em> throws — tracing must never break
 * the pipeline.
 *
 * <p><strong>Security:</strong> the prId segment can be webhook-derived, so it is sanitised
 * against path injection and the resolved directory is verified to remain under the configured
 * root. A secret-redaction denylist is applied to every artifact when {@code aiqa.trace.redact}
 * is true. See {@link TraceProperties} for the privacy posture of the trace directory.
 */
@Slf4j
@RequiredArgsConstructor
public class ContextTraceRecorder implements TraceSink {

    private static final String LOG_PREFIX = "[ContextTraceRecorder]";
    private static final String REDACTED   = "***REDACTED***";
    private static final String INDEX_FILE = "trace-index.jsonl";

    private static final DateTimeFormatter DAY_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    /** Owner-only file permissions (rw-------) — artifacts may contain repo source/diffs. */
    private static final FileAttribute<?> FILE_PERMS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    /** Owner-only directory permissions (rwx------). */
    private static final FileAttribute<?> DIR_PERMS =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

    /**
     * SECURITY denylist (applied only when {@code props.isRedact()}). Patterns are precompiled
     * and applied in order; token-specific patterns precede the generic {@code authorization:}
     * catch-all so a {@code Bearer} token is fully scrubbed before the header is collapsed.
     */
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("ghp_[A-Za-z0-9]+"),
            Pattern.compile("gho_[A-Za-z0-9]+"),
            Pattern.compile("ghu_[A-Za-z0-9]+"),
            Pattern.compile("ghs_[A-Za-z0-9]+"),
            Pattern.compile("ghr_[A-Za-z0-9]+"),
            Pattern.compile("github_pat_[A-Za-z0-9_]+"),
            Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]+"),
            Pattern.compile("(?i)authorization:\\s*\\S+"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("xox[baprs]-[A-Za-z0-9-]+"),
            Pattern.compile("(?i)(api[_-]?key|token|secret|password)\\s*[=:]\\s*\\S+")
    );

    private final TraceProperties props;
    private final ObjectMapper    objectMapper;

    /** Serialises appends to the shared {@code trace-index.jsonl} within this JVM. */
    private final Object indexLock = new Object();

    /**
     * Opens a new trace: creates the trace directory, writes {@code prompt.txt}, and (when
     * {@code captureRawStream}) opens the raw-stream writer. Always best-effort — on any failure
     * a disabled, no-op {@link TraceHandle} is returned so callers can proceed unaffected.
     *
     * @return an active handle, or {@link TraceHandle#disabled()} if tracing could not start
     */
    @Override
    public TraceHandle begin(String boundary, String taskType, String prId, String agent, String prompt) {
        try {
            String  traceId  = UUID.randomUUID().toString();
            Instant start    = Instant.now();
            String  dayStamp = DAY_FMT.format(start);

            Path root = Path.of(props.getDir()).toAbsolutePath().normalize();
            Path dir  = root.resolve(dayStamp)
                            .resolve(sanitize(prId))
                            .resolve(sanitize(traceId + "-" + taskType))
                            .normalize();

            // SECURITY: defence-in-depth against path escape even after sanitisation.
            if (!dir.startsWith(root)) {
                log.warn("{} Resolved trace dir '{}' escapes root '{}' — disabling trace for this run",
                        LOG_PREFIX, dir, root);
                return TraceHandle.disabled();
            }

            createDirsBestEffort(dir);
            writeSecure(dir.resolve("prompt.txt"), redactIfEnabled(prompt));

            BufferedWriter rawWriter = null;
            if (props.isCaptureRawStream()) {
                rawWriter = openRawWriter(dir.resolve("raw-stream.jsonl"));
            }

            int promptChars = prompt == null ? 0 : prompt.length();
            log.debug("{} Trace started traceId={} boundary={} taskType={} prId={} dir='{}'",
                    LOG_PREFIX, traceId, boundary, taskType, prId, dir);
            return TraceHandle.forFile(traceId, dir, root, start,
                    boundary, taskType, prId, agent, rawWriter, promptChars);

        } catch (Exception e) {
            log.warn("{} begin() failed (boundary={} taskType={} prId={}): {} — tracing disabled for this run",
                    LOG_PREFIX, boundary, taskType, prId, e.getMessage());
            return TraceHandle.disabled();
        }
    }

    /**
     * Appends one raw stdout line to {@code raw-stream.jsonl}, honouring the per-trace character
     * cap. No-op when the handle is disabled or raw capture is off. Best-effort — never throws.
     */
    @Override
    public void rawLine(TraceHandle h, String line) {
        if (h == null || !h.enabled || h.rawWriter == null) {
            return;
        }
        try {
            int cap = props.getMaxRawStreamChars();
            if (cap > 0 && h.rawStreamChars >= cap) {
                if (!h.truncationMarkerWritten) {
                    h.rawWriter.write("***TRACE_TRUNCATED: maxRawStreamChars=" + cap + " reached***");
                    h.rawWriter.write('\n');
                    h.truncationMarkerWritten = true;
                }
                return;
            }
            String redacted = redactIfEnabled(line);
            if (redacted == null) {
                redacted = "";
            }
            h.rawWriter.write(redacted);
            h.rawWriter.write('\n');
            h.rawStreamChars += (long) redacted.length() + 1L;
        } catch (Exception e) {
            log.warn("{} rawLine() write failed for traceId={}: {}", LOG_PREFIX, h.traceId, e.getMessage());
        }
    }

    /**
     * Closes the trace: flushes/closes the raw writer, writes {@code final.txt} and {@code meta.json},
     * and appends a compact summary line to {@code trace-index.jsonl}. No-op for a disabled handle.
     * Best-effort — never throws.
     */
    @Override
    public void finish(TraceHandle h, String finalOutput, int exitCode, boolean success) {
        if (h == null || !h.enabled) {
            return;
        }
        // Close the raw writer first so all rawLine() bytes are flushed before we summarise.
        closeQuietly(h.rawWriter);
        try {
            long latencyMs  = Duration.between(h.start, Instant.now()).toMillis();
            int  finalChars = finalOutput == null ? 0 : finalOutput.length();

            writeSecure(h.dir.resolve("final.txt"), redactIfEnabled(finalOutput));

            String relPath = h.root.relativize(h.dir).toString();
            TraceMeta meta = new TraceMeta(
                    h.traceId, h.start.toString(), h.boundary, h.taskType, h.prId, h.agent,
                    h.promptChars, h.rawStreamChars, finalChars, latencyMs, exitCode, success, relPath);

            writeSecure(h.dir.resolve("meta.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(meta));
            appendIndexLine(h.root, meta);

            log.info("{} Trace finished traceId={} taskType={} prId={} promptChars={} rawChars={} finalChars={} latencyMs={} exit={} success={}",
                    LOG_PREFIX, h.traceId, h.taskType, h.prId, h.promptChars,
                    h.rawStreamChars, finalChars, latencyMs, exitCode, success);
        } catch (Exception e) {
            log.warn("{} finish() failed for traceId={}: {}", LOG_PREFIX, h.traceId, e.getMessage());
        }
    }

    // ─── Internals ──────────────────────────────────────────────────────────────

    private String redactIfEnabled(String s) {
        if (s == null) {
            return null;
        }
        return props.isRedact() ? redact(s) : s;
    }

    private void appendIndexLine(Path root, TraceMeta meta) {
        try {
            Path index  = root.resolve(INDEX_FILE);
            String line = objectMapper.writeValueAsString(meta) + System.lineSeparator();
            synchronized (indexLock) {
                if (Files.notExists(index)) {
                    try {
                        Files.createFile(index, FILE_PERMS);
                    } catch (UnsupportedOperationException unsupported) {
                        Files.createFile(index);
                    } catch (FileAlreadyExistsException ignored) {
                        // created concurrently — fine, just append
                    }
                }
                Files.writeString(index, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            log.warn("{} Failed to append trace index line: {}", LOG_PREFIX, e.getMessage());
        }
    }

    private void createDirsBestEffort(Path dir) throws IOException {
        try {
            Files.createDirectories(dir, DIR_PERMS);
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(dir); // non-POSIX filesystem fallback
        }
    }

    /** Creates {@code file} with owner-only perms (best effort) then writes {@code content}. */
    private void writeSecure(Path file, String content) throws IOException {
        createFileBestEffort(file);
        Files.writeString(file, content == null ? "" : content,
                StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Opens an appending UTF-8 writer over a freshly created owner-only file. */
    private BufferedWriter openRawWriter(Path file) throws IOException {
        createFileBestEffort(file);
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    private void createFileBestEffort(Path file) throws IOException {
        try {
            Files.createFile(file, FILE_PERMS);
        } catch (UnsupportedOperationException e) {
            if (Files.notExists(file)) {
                Files.createFile(file);
            }
        } catch (FileAlreadyExistsException ignored) {
            // unique UUID dir — should not happen; existing file is reused/truncated by caller
        }
    }

    private void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception e) {
            log.warn("{} Failed to close trace writer: {}", LOG_PREFIX, e.getMessage());
        }
    }

    // ─── Static, unit-testable helpers ───────────────────────────────────────────

    /**
     * SECURITY: turns an arbitrary (possibly webhook-derived) segment into a safe single path
     * component. Replaces every char outside {@code [A-Za-z0-9._-]} with {@code _}, strips leading
     * dots so a segment can never become {@code .} or {@code ..}, caps length at 64, and maps
     * null/blank/empty results to {@code "unknown"}.
     */
    static String sanitize(String segment) {
        if (segment == null || segment.isBlank()) {
            return "unknown";
        }
        String cleaned = segment.replaceAll("[^A-Za-z0-9._-]", "_");
        int i = 0;
        while (i < cleaned.length() && cleaned.charAt(i) == '.') {
            i++;
        }
        cleaned = cleaned.substring(i);
        if (cleaned.length() > 64) {
            cleaned = cleaned.substring(0, 64);
        }
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    /**
     * SECURITY: scrubs known secret shapes (GitHub tokens, bearer/authorization headers, AWS keys,
     * Slack tokens, and generic key/token/secret/password assignments), replacing each match with
     * {@code ***REDACTED***}. Returns the input unchanged when null/empty.
     */
    static String redact(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String out = input;
        for (Pattern p : SECRET_PATTERNS) {
            out = p.matcher(out).replaceAll(Matcher.quoteReplacement(REDACTED));
        }
        return out;
    }

    // ─── Metadata types ──────────────────────────────────────────────────────────

    /**
     * Immutable per-trace metadata, serialised to {@code meta.json} and (compactly) to one line of
     * {@code trace-index.jsonl}.
     */
    public record TraceMeta(
            String  traceId,
            String  timestamp,
            String  boundary,
            String  taskType,
            String  prId,
            String  agent,
            int     promptChars,
            long    rawStreamChars,
            int     finalChars,
            long    latencyMs,
            int     exitCode,
            boolean success,
            String  relPath
    ) {}
}
