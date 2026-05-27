package nz.co.eroad.qaisystem.kafka;

import nz.co.eroad.qaisystem.agent.StrategyAgent;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Phase 2 entry point: consumes ImpactEnvelope from ImpactResultsQueue
 * and triggers the Strategy Agent decision.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImpactResultsConsumer {

    private final ObjectMapper  objectMapper;
    private final StrategyAgent strategyAgent;

    @KafkaListener(
            topics      = "${kafka.topics.impact-results}",
            groupId     = "${spring.kafka.consumer.group-id}",
            concurrency = "3"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("[ImpactResultsConsumer] key='{}' partition={} offset={}",
                record.key(), record.partition(), record.offset());
        try {
            ImpactEnvelope envelope = objectMapper.readValue(record.value(), ImpactEnvelope.class);
            log.info("[ImpactResultsConsumer] Strategy decision for PR '{}' risk={}",
                    envelope.getPrId(), envelope.getRiskLevel());

            strategyAgent.decide(envelope);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[ImpactResultsConsumer] Error for key='{}': {}", record.key(), e.getMessage(), e);
            ack.acknowledge();
        }
    }
}

