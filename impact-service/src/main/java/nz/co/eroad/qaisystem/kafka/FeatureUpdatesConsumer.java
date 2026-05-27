package nz.co.eroad.qaisystem.kafka;

import nz.co.eroad.qaisystem.engine.ImpactEngine;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import nz.co.eroad.qaisystem.model.PullRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Phase 1 consumer: receives PR events → runs deterministic impact analysis
 * → publishes ImpactEnvelope to ImpactResultsQueue.
 * No AI in this service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureUpdatesConsumer {

    private final ObjectMapper          objectMapper;
    private final ImpactEngine          impactEngine;
    private final ImpactResultsProducer impactResultsProducer;

    @KafkaListener(
            topics      = "${kafka.topics.feature-updates}",
            groupId     = "${spring.kafka.consumer.group-id}",
            concurrency = "3"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("[FeatureUpdatesConsumer] key='{}' partition={} offset={}",
                record.key(), record.partition(), record.offset());
        try {
            PullRequest pr = objectMapper.readValue(record.value(), PullRequest.class);
            log.info("[FeatureUpdatesConsumer] Analyzing PR '{}' — '{}'", pr.getPrId(), pr.getTitle());

            ImpactEnvelope envelope = impactEngine.analyze(pr);

            impactResultsProducer.publish(envelope);

            ack.acknowledge();
        } catch (Exception e) {
            log.error("[FeatureUpdatesConsumer] Error for key='{}': {}", record.key(), e.getMessage(), e);
            ack.acknowledge(); // ack to avoid poison-pill; DLQ recommended in prod
        }
    }
}

