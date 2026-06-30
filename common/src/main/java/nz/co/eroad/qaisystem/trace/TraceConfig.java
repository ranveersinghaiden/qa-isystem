package nz.co.eroad.qaisystem.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.config.TraceProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/** Wires exactly one {@link TraceSink} when aiqa.trace.enabled=true, selected by aiqa.trace.sink (file|postgres). */
@Configuration
@ConditionalOnProperty(name = "aiqa.trace.enabled", havingValue = "true")
@EnableConfigurationProperties(TraceProperties.class)
public class TraceConfig {

    @Bean
    @ConditionalOnProperty(name = "aiqa.trace.sink", havingValue = "file", matchIfMissing = true)
    TraceSink fileTraceSink(TraceProperties props, ObjectMapper objectMapper) {
        return new ContextTraceRecorder(props, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "aiqa.trace.sink", havingValue = "postgres")
    TraceSink postgresTraceSink(TraceProperties props, ObjectMapper objectMapper, ObjectProvider<DataSource> dataSource) {
        return new PostgresTraceSink(props, objectMapper, dataSource.getIfAvailable());
    }
}
