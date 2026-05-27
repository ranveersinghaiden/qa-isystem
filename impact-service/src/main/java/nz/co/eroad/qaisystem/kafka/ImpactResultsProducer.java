package nz.co.eroad.qaisystem.kafka;

import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes ImpactEnvelope to ImpactResultsQueue after impact analysis.
 * Strategy-service consumes this topic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImpactResultsProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${kafka.topics.impact-results}")
    private String topic;

    public CompletableFuture<SendResult<String, String>> publish(ImpactEnvelope envelope) {
        try {
            String payload = objectMapper.writeValueAsString(envelope);
            log.info("[ImpactResultsProducer] Publishing envelope '{}' for PR '{}' → risk={}",
                    envelope.getEnvelopeId(), envelope.getPrId(), envelope.getRiskLevel());

            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(topic, envelope.getPrId(), payload);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("[ImpactResultsProducer] Envelope '{}' sent → partition={} offset={}",
                            envelope.getEnvelopeId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                } else {
                    log.error("[ImpactResultsProducer] Failed to publish envelope '{}': {}",
                            envelope.getEnvelopeId(), ex.getMessage(), ex);
                }
            });

            return future;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize ImpactEnvelope", e);
        }
    }
}

