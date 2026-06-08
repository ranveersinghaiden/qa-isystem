package nz.co.eroad.qaisystem.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.model.FeedbackEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link FeedbackEvent} to {@code FeedbackQueue} when a QA PR is rejected.
 * Consumed by feedback-service which runs the re-generation pipeline.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedbackEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${kafka.topics.feedback:FeedbackQueue}")
    private String feedbackTopic;

    public void publishFeedbackEvent(FeedbackEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(feedbackTopic, event.getBranchName(), json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("[FeedbackEventProducer] Failed to publish FeedbackEvent for branch '{}': {}",
                                    event.getBranchName(), ex.getMessage());
                        } else {
                            log.info("[FeedbackEventProducer] FeedbackEvent published branch='{}' type={} → partition={} offset={}",
                                    event.getBranchName(), event.getPrType(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception e) {
            log.error("[FeedbackEventProducer] Error serializing FeedbackEvent: {}", e.getMessage(), e);
        }
    }
}

