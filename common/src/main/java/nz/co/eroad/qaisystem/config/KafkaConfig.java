package nz.co.eroad.qaisystem.config;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties.AckMode;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka configuration shared across all services.
 *
 * Spring Boot 4.0 removed Kafka listener-container auto-configuration, so
 * {@code kafkaListenerContainerFactory} and {@code ConsumerFactory} must be
 * declared explicitly. All tuneable values are bound from application.yaml via
 * {@code @Value} so the YAML remains the single source of truth.
 */
@EnableKafka
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:default-group}")
    private String groupId;

    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @Value("${spring.kafka.listener.concurrency:3}")
    private int concurrency;

    @Value("${kafka.topics.feature-updates}")
    private String featureUpdatesTopic;

    @Value("${kafka.topics.impact-results}")
    private String impactResultsTopic;

    @Value("${kafka.topics.test-scripts}")
    private String testScriptsTopic;

    @Value("${kafka.topics.test-results}")
    private String testResultsTopic;

    // ── Consumer ─────────────────────────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,  bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG,           groupId);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,  autoOffsetReset);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
            kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    // ── Producer ─────────────────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,      bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG,                   "all");
        config.put(ProducerConfig.RETRIES_CONFIG,                3);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,     true);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return new KafkaAdmin(config);
    }

    // ── Topic declarations ────────────────────────────────────────────────────

    @Bean public NewTopic featureUpdatesTopic() {
        return TopicBuilder.name(featureUpdatesTopic).partitions(3).replicas(1).build();
    }

    @Bean public NewTopic impactResultsTopic() {
        return TopicBuilder.name(impactResultsTopic).partitions(3).replicas(1).build();
    }

    @Bean public NewTopic testScriptsTopic() {
        return TopicBuilder.name(testScriptsTopic).partitions(3).replicas(1).build();
    }

    @Bean public NewTopic testResultsTopic() {
        return TopicBuilder.name(testResultsTopic).partitions(3).replicas(1).build();
    }
}
