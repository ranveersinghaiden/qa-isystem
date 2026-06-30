package nz.co.eroad.qaisystem.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.config.TraceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Zero-mock unit tests for {@link ContextTraceRecorder}. Uses real {@link TraceProperties},
 * a real {@link ObjectMapper}, and JUnit's {@link TempDir} for an isolated on-disk trace root.
 */
class ContextTraceRecorderTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private TraceProperties props(Path dir) {
        TraceProperties props = new TraceProperties();
        props.setEnabled(true);
        props.setDir(dir.toString());
        props.setRedact(true);
        props.setCaptureRawStream(true);
        return props;
    }

    private ContextTraceRecorder recorder(TraceProperties props) {
        return new ContextTraceRecorder(props, objectMapper);
    }

    // ─── Happy path ───────────────────────────────────────────────────────────

    @Test
    void happyPath_writesAllArtifactsAndOneIndexLine() throws Exception {
        ContextTraceRecorder rec = recorder(props(tempDir));

        TraceHandle h = rec.begin("copilot-cli", "BDD", "PR-123", "Conductor", "the exact prompt we send");
        assertThat(h.isEnabled()).isTrue();

        rec.rawLine(h, "{\"jsonrpc\":\"2.0\",\"method\":\"agent_message_chunk\",\"seq\":1}");
        rec.rawLine(h, "{\"jsonrpc\":\"2.0\",\"method\":\"agent_message_chunk\",\"seq\":2}");
        rec.finish(h, "final parsed output", 0, true);

        Path dir = h.dir;
        assertThat(dir).isDirectory();

        // prompt.txt
        assertThat(dir.resolve("prompt.txt")).exists();
        assertThat(Files.readString(dir.resolve("prompt.txt"))).isEqualTo("the exact prompt we send");

        // raw-stream.jsonl — both lines captured
        assertThat(dir.resolve("raw-stream.jsonl")).exists();
        String raw = Files.readString(dir.resolve("raw-stream.jsonl"));
        assertThat(raw).contains("\"seq\":1").contains("\"seq\":2");
        assertThat(Files.readAllLines(dir.resolve("raw-stream.jsonl"))).hasSize(2);

        // final.txt
        assertThat(dir.resolve("final.txt")).exists();
        assertThat(Files.readString(dir.resolve("final.txt"))).isEqualTo("final parsed output");

        // meta.json — parse and verify fields
        assertThat(dir.resolve("meta.json")).exists();
        JsonNode meta = objectMapper.readTree(dir.resolve("meta.json").toFile());
        assertThat(meta.get("traceId").asText()).isEqualTo(h.traceId);
        assertThat(meta.get("boundary").asText()).isEqualTo("copilot-cli");
        assertThat(meta.get("taskType").asText()).isEqualTo("BDD");
        assertThat(meta.get("prId").asText()).isEqualTo("PR-123");
        assertThat(meta.get("agent").asText()).isEqualTo("Conductor");
        assertThat(meta.get("exitCode").asInt()).isEqualTo(0);
        assertThat(meta.get("success").asBoolean()).isTrue();
        assertThat(meta.get("promptChars").asInt()).isEqualTo("the exact prompt we send".length());
        assertThat(meta.get("finalChars").asInt()).isEqualTo("final parsed output".length());
        assertThat(meta.get("latencyMs").asLong()).isGreaterThanOrEqualTo(0L);

        // trace-index.jsonl at the trace root — exactly one line
        Path index = h.root.resolve("trace-index.jsonl");
        assertThat(index).exists();
        List<String> indexLines = Files.readAllLines(index);
        assertThat(indexLines).hasSize(1);
        JsonNode indexEntry = objectMapper.readTree(indexLines.get(0));
        assertThat(indexEntry.get("traceId").asText()).isEqualTo(h.traceId);
        assertThat(indexEntry.get("taskType").asText()).isEqualTo("BDD");
        assertThat(indexEntry.get("relPath").asText()).isNotBlank();
    }

    @Test
    void twoTraces_appendTwoIndexLines() {
        ContextTraceRecorder rec = recorder(props(tempDir));

        TraceHandle h1 = rec.begin("copilot-cli", "BDD", "PR-1", "Conductor", "p1");
        rec.finish(h1, "out1", 0, true);
        TraceHandle h2 = rec.begin("copilot-cli", "CODEGEN", "PR-2", "Conductor", "p2");
        rec.finish(h2, "out2", 0, true);

        assertThatCode(() -> {
            List<String> lines = Files.readAllLines(h1.root.resolve("trace-index.jsonl"));
            assertThat(lines).hasSize(2);
        }).doesNotThrowAnyException();
    }

    // ─── Redaction ────────────────────────────────────────────────────────────

    @Test
    void redaction_scrubsSecretsFromAllArtifacts() throws Exception {
        ContextTraceRecorder rec = recorder(props(tempDir));

        String prompt = "use token ghp_ABC123DEF456 and header Authorization: Bearer abc.def now";
        TraceHandle h = rec.begin("copilot-cli", "BDD", "PR-9", "Conductor", prompt);
        rec.rawLine(h, "raw line with ghp_ABC123DEF456 secret");
        rec.rawLine(h, "Authorization: Bearer abc.def");
        rec.finish(h, "final output contains ghp_ABC123DEF456 too", 0, true);

        String promptTxt = Files.readString(h.dir.resolve("prompt.txt"));
        assertThat(promptTxt).doesNotContain("ghp_ABC123DEF456");
        assertThat(promptTxt).doesNotContain("Bearer abc.def");
        assertThat(promptTxt).contains("***REDACTED***");

        String raw = Files.readString(h.dir.resolve("raw-stream.jsonl"));
        assertThat(raw).doesNotContain("ghp_ABC123DEF456");
        assertThat(raw).doesNotContain("Bearer abc.def");
        assertThat(raw).contains("***REDACTED***");

        String finalTxt = Files.readString(h.dir.resolve("final.txt"));
        assertThat(finalTxt).doesNotContain("ghp_ABC123DEF456");
        assertThat(finalTxt).contains("***REDACTED***");
    }

    @Test
    void redactionDisabled_keepsRawContent() throws Exception {
        TraceProperties props = props(tempDir);
        props.setRedact(false);
        ContextTraceRecorder rec = recorder(props);

        TraceHandle h = rec.begin("copilot-cli", "BDD", "PR-10", "Conductor", "token ghp_KEEPME0001");
        rec.finish(h, "done", 0, true);

        assertThat(Files.readString(h.dir.resolve("prompt.txt"))).contains("ghp_KEEPME0001");
    }

    // ─── Path sanitisation / traversal defence ──────────────────────────────────

    @Test
    void pathTraversalPrId_isSanitisedAndStaysUnderRoot() {
        ContextTraceRecorder rec = recorder(props(tempDir));

        TraceHandle h = rec.begin("copilot-cli", "BDD", "../../etc/passwd", "Conductor", "p");
        assertThat(h.isEnabled()).isTrue();

        Path root = tempDir.toAbsolutePath().normalize();
        assertThat(h.dir.normalize().startsWith(root)).isTrue();
        assertThat(h.dir).isDirectory();

        // No path element may be a traversal token, and the literal "etc/passwd" must not survive.
        for (Path segment : h.dir) {
            assertThat(segment.toString()).isNotEqualTo("..").isNotEqualTo(".");
        }
        assertThat(h.dir.toString()).doesNotContain("etc/passwd");
    }

    // ─── Raw-stream cap ─────────────────────────────────────────────────────────

    @Test
    void rawStreamCap_boundsFileSize() throws Exception {
        TraceProperties props = props(tempDir);
        props.setMaxRawStreamChars(50);
        ContextTraceRecorder rec = recorder(props);

        TraceHandle h = rec.begin("copilot-cli", "BDD", "PR-11", "Conductor", "p");
        for (int i = 0; i < 100; i++) {
            rec.rawLine(h, "0123456789"); // 10 chars + newline each → unbounded would be ~1100 bytes
        }
        rec.finish(h, "done", 0, true);

        Path rawFile = h.dir.resolve("raw-stream.jsonl");
        long size = Files.size(rawFile);
        assertThat(size).isLessThan(200L); // far below the unbounded ~1100 bytes
        assertThat(Files.readString(rawFile)).contains("TRACE_TRUNCATED");
    }

    @Test
    void unlimitedCap_writesEverything() throws Exception {
        TraceProperties props = props(tempDir);
        props.setMaxRawStreamChars(0); // 0 = unlimited
        ContextTraceRecorder rec = recorder(props);

        TraceHandle h = rec.begin("copilot-cli", "BDD", "PR-12", "Conductor", "p");
        for (int i = 0; i < 100; i++) {
            rec.rawLine(h, "0123456789");
        }
        rec.finish(h, "done", 0, true);

        assertThat(Files.readAllLines(h.dir.resolve("raw-stream.jsonl"))).hasSize(100);
    }

    // ─── Best-effort / never-throws ─────────────────────────────────────────────

    @Test
    void disabledHandle_isNoOpAndNeverThrows() {
        ContextTraceRecorder rec = recorder(props(tempDir));
        TraceHandle disabled = TraceHandle.disabled();

        assertThat(disabled.isEnabled()).isFalse();
        assertThatCode(() -> {
            rec.rawLine(disabled, "anything");
            rec.finish(disabled, "anything", 0, true);
        }).doesNotThrowAnyException();
    }

    @Test
    void begin_whenDirIsARegularFile_returnsDisabledHandleAndNeverThrows() throws Exception {
        Path regularFile = tempDir.resolve("not-a-directory.txt");
        Files.writeString(regularFile, "x");

        TraceProperties props = props(regularFile); // points the trace root at a file
        ContextTraceRecorder rec = recorder(props);

        TraceHandle[] holder = new TraceHandle[1];
        assertThatCode(() -> holder[0] = rec.begin("copilot-cli", "BDD", "PR-13", "Conductor", "p"))
                .doesNotThrowAnyException();
        assertThat(holder[0].isEnabled()).isFalse();

        // Subsequent calls on the disabled handle are also safe no-ops.
        assertThatCode(() -> {
            rec.rawLine(holder[0], "line");
            rec.finish(holder[0], "final", -1, false);
        }).doesNotThrowAnyException();
    }

    // ─── Direct unit tests for static helpers ───────────────────────────────────

    @Test
    void sanitize_replacesUnsafeCharsStripsDotsAndCapsLength() {
        assertThat(ContextTraceRecorder.sanitize("../../etc/passwd")).isEqualTo("_.._etc_passwd");
        assertThat(ContextTraceRecorder.sanitize("PR-123_ok.feature")).isEqualTo("PR-123_ok.feature");
        assertThat(ContextTraceRecorder.sanitize(null)).isEqualTo("unknown");
        assertThat(ContextTraceRecorder.sanitize("   ")).isEqualTo("unknown");
        assertThat(ContextTraceRecorder.sanitize("....")).isEqualTo("unknown"); // all-dots → blank → unknown
        assertThat(ContextTraceRecorder.sanitize("a b/c:d")).isEqualTo("a_b_c_d");
        assertThat(ContextTraceRecorder.sanitize("x".repeat(100))).hasSize(64);
    }

    @Test
    void redact_scrubsKnownSecretShapes() {
        assertThat(ContextTraceRecorder.redact("x ghp_ABC123DEF456 y"))
                .contains("***REDACTED***").doesNotContain("ghp_ABC123DEF456");
        assertThat(ContextTraceRecorder.redact("github_pat_11ABCDEFG_xyz123"))
                .contains("***REDACTED***").doesNotContain("github_pat_11ABCDEFG_xyz123");
        assertThat(ContextTraceRecorder.redact("Authorization: Bearer abc.def"))
                .contains("***REDACTED***").doesNotContain("Bearer abc.def");
        assertThat(ContextTraceRecorder.redact("api_key = supersecretvalue"))
                .contains("***REDACTED***").doesNotContain("supersecretvalue");
        assertThat(ContextTraceRecorder.redact("password: hunter2plus"))
                .contains("***REDACTED***").doesNotContain("hunter2plus");
        assertThat(ContextTraceRecorder.redact("AKIAABCDEFGHIJKLMNOP")).contains("***REDACTED***");
        assertThat(ContextTraceRecorder.redact("xoxb-123-456-abcDEF")).contains("***REDACTED***");
        assertThat(ContextTraceRecorder.redact("nothing secret here")).isEqualTo("nothing secret here");
        assertThat(ContextTraceRecorder.redact(null)).isNull();
        assertThat(ContextTraceRecorder.redact("")).isEqualTo("");
    }
}
