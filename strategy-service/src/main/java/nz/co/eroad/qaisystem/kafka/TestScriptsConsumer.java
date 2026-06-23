package nz.co.eroad.qaisystem.kafka;

import nz.co.eroad.qaisystem.execution.CodegenService;
import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScriptRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Listens to TestScriptsQueue only when aiqa.codegen.enabled=true (legacy monolith mode).
 * In the standard deployment, codegen-service handles this queue instead.
 * Set AIQA_CODEGEN_ENABLED=true in strategy-service to run as a monolith.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "aiqa.codegen.enabled", havingValue = "true", matchIfMissing = false)
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

            log.info("[TestScriptsConsumer] Routing scenario '{}' (PR '{}') to Codegen Service",
                    req.getScenarioId(), req.getPrId());

            TestResult result = codegenService.generateAndExecuteOne(req);

            log.info("[TestScriptsConsumer] Scenario '{}' complete for PR '{}' — passed={}",
                    req.getScenarioId(), req.getPrId(), result != null && result.isPassed());

            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("[TestScriptsConsumer] Error processing scenario record key='{}': {}",
                    record.key(), e.getMessage(), e);
            acknowledgment.acknowledge();
        }
    }
}