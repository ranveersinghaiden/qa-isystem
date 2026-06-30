package nz.co.eroad.qaisystem.state;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Supplies the {@link DataSource} for {@link PostgresStateStore}, but ONLY when
 * {@code spring.datasource.url} is present.
 *
 * <p>Spring Boot's {@code DataSourceAutoConfiguration} is excluded per-service (see each service's
 * {@code application.yaml} {@code spring.autoconfigure.exclude}) because the {@code spring-boot-
 * starter-jdbc} dependency is now on every service's classpath; without that exclusion a no-DB boot
 * would eagerly try to build a Hikari pool and fail with "Failed to determine a suitable driver
 * class". Building the {@code DataSource} here — gated on the URL being set — keeps the default
 * (no-profile, no-DB) boot completely unaffected while enabling Postgres only when configured.
 *
 * <p>The URL is supplied via the {@code SPRING_DATASOURCE_URL} environment variable (Spring relaxed
 * binding) or, under the {@code oneshot} profile, mapped from {@code QA_DB_URL} in yaml. An ABSENT
 * property (not an empty string) is what keeps {@link InMemoryStateStore} as the default — empty
 * values are deliberately never written to {@code spring.datasource.url}.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "spring.datasource.url")
public class StateStoreDataSourceConfig {

    /**
     * Builds the Postgres {@link DataSource} from {@code spring.datasource.*} properties.
     */
    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    public DataSource stateStoreDataSource(StateStoreDataSourceProperties props) {
        log.info("[StateStoreDataSourceConfig] Configuring Postgres DataSource (url host only) — driver='{}'",
                props.driverClassName());
        return DataSourceBuilder.create()
                .url(props.url())
                .username(props.username())
                .password(props.password())
                .driverClassName(props.driverClassName())
                .build();
    }

    /** Binds {@code spring.datasource.*} without pulling in the excluded auto-configuration. */
    @Bean
    public StateStoreDataSourceProperties stateStoreDataSourceProperties(
            org.springframework.core.env.Environment env) {
        return new StateStoreDataSourceProperties(
                env.getProperty("spring.datasource.url"),
                env.getProperty("spring.datasource.username", ""),
                env.getProperty("spring.datasource.password", ""),
                env.getProperty("spring.datasource.driver-class-name", "org.postgresql.Driver"));
    }

    /** Plain carrier for the resolved datasource settings. */
    public record StateStoreDataSourceProperties(String url, String username, String password,
                                                 String driverClassName) {
    }
}
