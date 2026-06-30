package nz.co.eroad.qaisystem.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.config.TraceProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Zero-mock unit tests for {@link PostgresTraceSink}. No DataSource, no real database, no H2: the
 * single {@code jdbc.update(...)} is the only un-exercised line (mirroring {@link PostgresTraceSink}'s
 * sibling {@code PostgresStateStore}, whose JDBC is likewise only gating-tested). The pure helpers
 * ({@code buildPayload}, {@code appendCapped}, {@code redactIfEnabled}) are package-private and
 * verified directly on an instance built with a {@code null} DataSource.
 */
class PostgresTraceSinkTest {

    private static final String TOKEN = "ghp_DEADBEEF1234567890";

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private TraceProperties props(boolean redact) {
        TraceProperties p = new TraceProperties();
        p.setEnabled(true);
        p.setSink("postgres");
        p.setRedact(redact);
        p.setCaptureRawStream(true);
        return p;
    }

    private PostgresTraceSink sink(TraceProperties props) {
        return new PostgresTraceSink(props, objectMapper, null); // null DataSource → jdbc is null
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // ─── Null-DataSource safety (best-effort, never throws) ─────────────────────

    @Test
    void nullDataSource_isCompleteNoOpAndNeverThrows() {
        PostgresTraceSink sink = sink(props(true));

        TraceHandle handle = sink.begin("copilot-cli", "BDD", "PR-1", "Conductor", "prompt " + TOKEN);
        assertThat(handle.isEnabled()).isFalse(); // no datasource → disabled handle

        assertThatCode(() -> {
            sink.rawLine(handle, "raw line " + TOKEN);
            sink.finish(handle, "final " + TOKEN, 0, true);
            // a literally-disabled handle is also safe
            sink.rawLine(TraceHandle.disabled(), "x");
            sink.finish(TraceHandle.disabled(), "x", -1, false);
        }).doesNotThrowAnyException();
    }

    // ─── buildPayload: redaction + all sections + meta ──────────────────────────

    @Test
    void buildPayload_redactsSecretsAndContainsAllSections() throws Exception {
        PostgresTraceSink sink = sink(props(true));

        String redactedPrompt = sink.redactIfEnabled("use token " + TOKEN + " now");
        PostgresTraceSink.Buffer buffer = new PostgresTraceSink.Buffer(redactedPrompt);
        sink.appendCapped(buffer, sink.redactIfEnabled("raw line with " + TOKEN + " secret"));

        TraceHandle handle = TraceHandle.forBuffering(
                "trace-1", Instant.now(), "copilot-cli", "BDD", "PR-7", "Conductor", redactedPrompt.length());
        String redactedFinal = sink.redactIfEnabled("final out " + TOKEN);

        String json = sink.buildPayload(handle, buffer, redactedFinal, 0, true, 42L);

        // (a) redacted — the token never survives anywhere in the payload
        assertThat(json).contains("***REDACTED***").doesNotContain(TOKEN);

        JsonNode node = objectMapper.readTree(json);

        // (b) prompt / rawStream / final sections all present and redacted
        assertThat(node.hasNonNull("prompt")).isTrue();
        assertThat(node.hasNonNull("rawStream")).isTrue();
        assertThat(node.hasNonNull("final")).isTrue();
        assertThat(node.get("prompt").asText()).contains("***REDACTED***").doesNotContain(TOKEN);
        assertThat(node.get("rawStream").asText()).contains("***REDACTED***").doesNotContain(TOKEN);
        assertThat(node.get("final").asText()).contains("***REDACTED***").doesNotContain(TOKEN);

        // (c) meta identity + flags correct
        JsonNode meta = node.get("meta");
        assertThat(meta.get("traceId").asText()).isEqualTo("trace-1");
        assertThat(meta.get("boundary").asText()).isEqualTo("copilot-cli");
        assertThat(meta.get("taskType").asText()).isEqualTo("BDD");
        assertThat(meta.get("prId").asText()).isEqualTo("PR-7");
        assertThat(meta.get("agent").asText()).isEqualTo("Conductor");
        assertThat(meta.get("exitCode").asInt()).isEqualTo(0);
        assertThat(meta.get("success").asBoolean()).isTrue();
        assertThat(meta.get("latencyMs").asLong()).isEqualTo(42L);
        assertThat(meta.get("promptChars").asInt()).isEqualTo(redactedPrompt.length());
        assertThat(meta.get("finalChars").asInt()).isEqualTo(redactedFinal.length());
        assertThat(meta.get("rawStreamChars").asInt()).isEqualTo(buffer.rawStream.length());
        assertThat(meta.get("capped").asBoolean()).isFalse();
    }

    @Test
    void buildPayload_metaCarriesFailureExitAndSuccessFalse() throws Exception {
        PostgresTraceSink sink = sink(props(true));
        PostgresTraceSink.Buffer buffer = new PostgresTraceSink.Buffer("p");
        TraceHandle handle = TraceHandle.forBuffering(
                "t2", Instant.now(), "copilot-cli", "CODEGEN", "PR-8", "Conductor", 1);

        String json = sink.buildPayload(handle, buffer, "done", -1, false, 7L);
        JsonNode meta = objectMapper.readTree(json).get("meta");

        assertThat(meta.get("exitCode").asInt()).isEqualTo(-1);
        assertThat(meta.get("success").asBoolean()).isFalse();
    }

    // ─── Raw-stream cap ─────────────────────────────────────────────────────────

    @Test
    void appendCapped_boundsRawStreamAndWritesMarkerExactlyOnce() {
        TraceProperties props = props(true);
        props.setMaxRawStreamChars(30);
        PostgresTraceSink sink = sink(props);

        PostgresTraceSink.Buffer buffer = new PostgresTraceSink.Buffer("p");
        for (int i = 0; i < 20; i++) {
            sink.appendCapped(buffer, "0123456789"); // 11 chars/line incl newline → ~220 unbounded
        }

        assertThat(buffer.capped).isTrue();
        assertThat(buffer.rawChars).isLessThanOrEqualTo(30L);
        String raw = buffer.rawStream.toString();
        assertThat(raw).contains("RAW STREAM CAPPED");
        // content (≤ cap) + one short truncation marker → well under cap + 64
        assertThat(raw.length()).isLessThanOrEqualTo(30 + 64);
        assertThat(countOccurrences(raw, "RAW STREAM CAPPED")).isEqualTo(1);
    }

    @Test
    void appendCapped_unlimitedWhenCapIsZero() {
        TraceProperties props = props(true);
        props.setMaxRawStreamChars(0); // 0 = unlimited
        PostgresTraceSink sink = sink(props);

        PostgresTraceSink.Buffer buffer = new PostgresTraceSink.Buffer("p");
        for (int i = 0; i < 100; i++) {
            sink.appendCapped(buffer, "0123456789");
        }

        assertThat(buffer.capped).isFalse();
        assertThat(buffer.rawStream.toString()).doesNotContain("RAW STREAM CAPPED");
        assertThat(buffer.rawStream.length()).isEqualTo(100 * 11); // 10 chars + newline each
    }

    // ─── redactIfEnabled ────────────────────────────────────────────────────────

    @Test
    void redactIfEnabled_scrubsWhenRedactEnabled() {
        PostgresTraceSink sink = sink(props(true));
        assertThat(sink.redactIfEnabled("token " + TOKEN))
                .contains("***REDACTED***").doesNotContain(TOKEN);
    }

    @Test
    void redactIfEnabled_returnsInputUnchangedWhenRedactDisabled() {
        PostgresTraceSink sink = sink(props(false));
        assertThat(sink.redactIfEnabled("token " + TOKEN)).isEqualTo("token " + TOKEN);
        assertThat(sink.redactIfEnabled(null)).isNull();
    }
}
