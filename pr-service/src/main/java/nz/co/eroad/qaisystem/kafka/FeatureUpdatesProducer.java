package nz.co.eroad.qaisystem.kafka;

import nz.co.eroad.qaisystem.model.PullRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureUpdatesProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${kafka.topics.feature-updates}")
    private String topic;

    /**
     * Publishes a PullRequest event to FeatureUpdatesQueue.
     * Key = prId so partitioning is deterministic per PR.
     */
    public CompletableFuture<SendResult<String, String>> publishPullRequest(
            PullRequest pullRequest) {

        try {
            String payload = objectMapper.writeValueAsString(pullRequest);
            log.info("[FeatureUpdatesProducer] Publishing PR '{}' to topic '{}'",
                    pullRequest.getPrId(), topic);

            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(topic, pullRequest.getPrId(), payload);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("[FeatureUpdatesProducer] PR '{}' sent → partition={}, offset={}",
                            pullRequest.getPrId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                } else {
                    log.error("[FeatureUpdatesProducer] Failed to send PR '{}': {}",
                            pullRequest.getPrId(), ex.getMessage(), ex);
                }
            });

            return future;

        } catch (JsonProcessingException e) {
            log.error("[FeatureUpdatesProducer] Serialization error for PR '{}': {}",
                    pullRequest.getPrId(), e.getMessage(), e);
            throw new RuntimeException("Failed to serialize PullRequest", e);
        }
    }
}
