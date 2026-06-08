package nz.co.eroad.qaisystem.feedback.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.agent.PrFeedbackService;
import nz.co.eroad.qaisystem.model.FeedbackEvent;
import nz.co.eroad.qaisystem.model.PrRecord;
import nz.co.eroad.qaisystem.model.PrType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes FeedbackQueue messages published by strategy-service when a QA PR is rejected.
 * Delegates to PrFeedbackService for re-generation and product expert updates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedbackEventConsumer {

    private final ObjectMapper       objectMapper;
    private final PrFeedbackService  feedbackService;

    @KafkaListener(
            topics      = "${kafka.topics.feedback:FeedbackQueue}",
            groupId     = "${spring.kafka.consumer.group-id}",
            concurrency = "2"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("[FeedbackEventConsumer] Received feedback event key='{}' partition={} offset={}",
                record.key(), record.partition(), record.offset());
        try {
            FeedbackEvent event = objectMapper.readValue(record.value(), FeedbackEvent.class);
            PrRecord prRecord = PrRecord.builder()
                    .branchName(event.getBranchName())
                    .prNumber(event.getPrNumber())
                    .type(event.getPrType())
                    .bddScenario(event.getBddScenario())
                    .testScript(event.getTestScript())
                    .build();

            log.info("[FeedbackEventConsumer] Processing {} PR rejection for branch='{}'",
                    event.getPrType(), event.getBranchName());

            if (event.getPrType() == PrType.BDD) {
                feedbackService.handleBddRejection(prRecord, event.getPrNumber());
            } else {
                feedbackService.handleTestRejection(prRecord, event.getPrNumber());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[FeedbackEventConsumer] Error processing feedback event: {}", e.getMessage(), e);
            ack.acknowledge();
        }
    }
}
