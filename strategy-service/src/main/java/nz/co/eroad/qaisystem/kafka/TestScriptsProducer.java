package nz.co.eroad.qaisystem.kafka;

import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.TestScriptRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Fans a BDD feature out into one {@link TestScriptRequest} per scenario, each published to
 * {@code TestScriptsQueue} keyed by {@code scenarioId}.
 *
 * <p>Replaces the previous "one message = whole feature" approach so codegen-service can process
 * scenarios in parallel across consumer threads and horizontally-scaled instances, instead of
 * looping them serially on a single thread.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TestScriptsProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${kafka.topics.test-scripts}")
    private String topic;

    /**
     * Publishes one {@link TestScriptRequest} per scenario in {@code bdd}.
     *
     * @return the number of scenario requests published
     */
    public int publishScenarioRequests(BddScenario bdd) {
        List<BddScenario.Scenario> scenarios = bdd.getScenarios();
        if (scenarios == null || scenarios.isEmpty()) {
            log.warn("[TestScriptsProducer] No scenarios to publish for PR '{}'", bdd.getPrId());
            return 0;
        }

        int count = scenarios.size();
        log.info("[TestScriptsProducer] Fanning out {} scenario request(s) for PR '{}'",
                count, bdd.getPrId());

        for (int i = 0; i < count; i++) {
            BddScenario.Scenario s = scenarios.get(i);
            String scenarioId = (s.getScenarioId() != null && !s.getScenarioId().isBlank())
                    ? s.getScenarioId() : UUID.randomUUID().toString();

            TestScriptRequest req = TestScriptRequest.builder()
                    .prId(bdd.getPrId())
                    .prTitle(bdd.getPrTitle())
                    .strategyId(bdd.getStrategyId())
                    .bddScenarioId(bdd.getScenarioId())
                    .scenarioId(scenarioId)
                    .scenarioIndex(i)
                    .scenarioCount(count)
                    .scenario(s)
                    .prContext(bdd.getPrContext())
                    .build();

            try {
                String payload = objectMapper.writeValueAsString(req);
                kafkaTemplate.send(topic, scenarioId, payload).whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("[TestScriptsProducer] Scenario '{}' (PR '{}') sent → partition={}, offset={}",
                                scenarioId, bdd.getPrId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        log.error("[TestScriptsProducer] Failed to send scenario '{}' (PR '{}'): {}",
                                scenarioId, bdd.getPrId(), ex.getMessage(), ex);
                    }
                });
            } catch (JsonProcessingException e) {
                log.error("[TestScriptsProducer] Failed to serialize TestScriptRequest for scenario '{}' (PR '{}'): {}",
                        scenarioId, bdd.getPrId(), e.getMessage(), e);
            }
        }

        return count;
    }
}
