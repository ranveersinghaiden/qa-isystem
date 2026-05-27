package nz.co.eroad.qaisystem.kafka;

import nz.co.eroad.qaisystem.model.BddScenario;
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
public class TestScriptsProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${kafka.topics.test-scripts}")
    private String topic;

    public CompletableFuture<SendResult<String, String>> publishBddScenario(
            BddScenario scenario) {

        try {
            String payload = objectMapper.writeValueAsString(scenario);
            log.info("[TestScriptsProducer] Publishing BDD scenario '{}' for PR '{}'",
                    scenario.getScenarioId(), scenario.getPrId());

            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(topic, scenario.getPrId(), payload);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("[TestScriptsProducer] BDD '{}' sent → partition={}, offset={}",
                            scenario.getScenarioId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                } else {
                    log.error("[TestScriptsProducer] Failed to send BDD '{}': {}",
                            scenario.getScenarioId(), ex.getMessage(), ex);
                }
            });

            return future;

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize BddScenario", e);
        }
    }
}
