package nz.co.eroad.qaisystem.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies exactly one {@link TraceSink} is wired purely from configuration — no live database
 * required (offline-green; no Testcontainers / embedded Postgres). Asserts the gating contract of
 * {@link TraceConfig}:
 *
 * <ul>
 *   <li>{@code aiqa.trace.enabled} unset/false → <em>no</em> {@link TraceSink} (pipeline unaffected);</li>
 *   <li>enabled, {@code aiqa.trace.sink} unset → {@link ContextTraceRecorder} (the file default);</li>
 *   <li>enabled, {@code aiqa.trace.sink=postgres} (no datasource) → {@link PostgresTraceSink},
 *       constructed with a {@code null} DataSource via {@code ObjectProvider.getIfAvailable()}.</li>
 * </ul>
 *
 * The {@link PostgresTraceSink} built here never opens a connection (its constructor only wraps the
 * — here absent — DataSource), so no Mockito and no database are involved.
 */
@DisplayName("TraceSink conditional gating")
class TraceConfigGatingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(TraceConfig.class);

    @Test
    @DisplayName("enabled unset → no TraceSink bean at all")
    void disabled_noTraceSink() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(TraceSink.class));
    }

    @Test
    @DisplayName("enabled=false → no TraceSink bean at all")
    void explicitlyDisabled_noTraceSink() {
        runner.withPropertyValues("aiqa.trace.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(TraceSink.class));
    }

    @Test
    @DisplayName("enabled=true, sink unset → single ContextTraceRecorder (file) sink")
    void enabledDefault_fileSink() {
        runner.withPropertyValues("aiqa.trace.enabled=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(TraceSink.class);
            assertThat(ctx.getBean(TraceSink.class)).isInstanceOf(ContextTraceRecorder.class);
        });
    }

    @Test
    @DisplayName("enabled=true, sink=file → single ContextTraceRecorder (file) sink")
    void enabledFileExplicit_fileSink() {
        runner.withPropertyValues("aiqa.trace.enabled=true", "aiqa.trace.sink=file").run(ctx -> {
            assertThat(ctx).hasSingleBean(TraceSink.class);
            assertThat(ctx.getBean(TraceSink.class)).isInstanceOf(ContextTraceRecorder.class);
        });
    }

    @Test
    @DisplayName("enabled=true, sink=postgres (no datasource) → single PostgresTraceSink")
    void enabledPostgres_postgresSink() {
        runner.withPropertyValues("aiqa.trace.enabled=true", "aiqa.trace.sink=postgres").run(ctx -> {
            assertThat(ctx).hasSingleBean(TraceSink.class);
            assertThat(ctx.getBean(TraceSink.class)).isInstanceOf(PostgresTraceSink.class);
        });
    }
}
