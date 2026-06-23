package nz.co.eroad.qaisystem.kafka;

import nz.co.eroad.qaisystem.execution.CodegenService;
import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScriptRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes one fanned-out {@link TestScriptRequest} (a single scenario) per message and routes it
 * to {@link CodegenService}. Listener concurrency tracks {@code aiqa.agent.max-concurrent} so each
 * consumer thread drives at most one in-flight Conductor agent; horizontal scale-out is achieved
 * by adding instances to the {@code codegen-service-group} consumer group.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TestScriptsConsumer {

    private final ObjectMapper objectMapper;
    private final CodegenService codegenService;

    @KafkaListener(
            topics      = "${kafka.topics.test-scripts}",
            groupId     = "${spring.kafka.consumer.group-id}",
            concurrency = "${aiqa.agent.max-concurrent:3}"
    )
    public void consume(ConsumerRecord<String, String> record,
                        Acknowledgment acknowledgment) {

        log.info("[TestScriptsConsumer] Received scenario request key='{}' partition={} offset={}",
                record.key(), record.partition(), record.offset());

        try {
            TestScriptRequest req =
                    objectMapper.readValue(record.value(), TestScriptRequest.class);

            log.info("[TestScriptsConsumer] Routing scenario '{}' (PR '{}', {}/{}) to Codegen Service",
                    req.getScenarioId(), req.getPrId(),
                    req.getScenarioIndex() + 1, req.getScenarioCount());

            TestResult result = codegenService.generateAndExecuteOne(req);

            if (result != null) {
                log.info("[TestScriptsConsumer] Scenario '{}' complete for PR '{}' — passed={}",
                        req.getScenarioId(), req.getPrId(), result.isPassed());
            }

            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("[TestScriptsConsumer] Error processing scenario record key='{}': {}",
                    record.key(), e.getMessage(), e);
            acknowledgment.acknowledge();
        }
    }
}

