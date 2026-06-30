package nz.co.eroad.qaisystem.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@link StateStore} bean is selected purely from configuration — no live database
 * required (offline-green; no Testcontainers / embedded Postgres). Asserts the mutually-exclusive
 * gating contract:
 *
 * <ul>
 *   <li>without {@code spring.datasource.url} → {@link InMemoryStateStore} (the safe default);</li>
 *   <li>with {@code spring.datasource.url} (+ a {@link DataSource}) → {@link PostgresStateStore},
 *       and {@link InMemoryStateStore} backs off, so there is always exactly one {@link StateStore}.</li>
 * </ul>
 *
 * The {@link DataSource} used here is a real, inert implementation (every method throws) — NOT a
 * mock. {@link PostgresStateStore}'s constructor only wraps it in a {@code JdbcTemplate} and never
 * opens a connection, so no method is ever called.
 */
@DisplayName("StateStore conditional gating")
class PostgresStateStoreGatingTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Stores.class);

    @Test
    @DisplayName("without spring.datasource.url → InMemoryStateStore is the only StateStore")
    void withoutUrl_inMemoryIsDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(StateStore.class);
            assertThat(ctx.getBean(StateStore.class)).isInstanceOf(InMemoryStateStore.class);
            assertThat(ctx).doesNotHaveBean(PostgresStateStore.class);
        });
    }

    @Test
    @DisplayName("with spring.datasource.url → PostgresStateStore wins and InMemory backs off")
    void withUrl_postgresSelected() {
        runner.withBean(DataSource.class, InertDataSource::new)
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5432/db")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(StateStore.class);
                    assertThat(ctx.getBean(StateStore.class)).isInstanceOf(PostgresStateStore.class);
                    assertThat(ctx).doesNotHaveBean(InMemoryStateStore.class);
                });
    }

    /** Both candidate stores; conditions pick exactly one. Import order is intentionally InMemory-first
     *  to prove the choice does NOT depend on component-scan ordering. */
    @Configuration(proxyBeanMethods = false)
    @Import({InMemoryStateStore.class, PostgresStateStore.class})
    static class Stores {
    }

    /** Real, inert {@link DataSource} (no Mockito). Never invoked — present only to satisfy the
     *  {@link PostgresStateStore} constructor dependency when the URL property is set. */
    static class InertDataSource implements DataSource {
        @Override public Connection getConnection() { throw new UnsupportedOperationException(); }
        @Override public Connection getConnection(String username, String password) { throw new UnsupportedOperationException(); }
        @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
        @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
        @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
        @Override public int getLoginTimeout() { throw new UnsupportedOperationException(); }
        @Override public Logger getParentLogger() { throw new UnsupportedOperationException(); }
        @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return false; }
    }
}
