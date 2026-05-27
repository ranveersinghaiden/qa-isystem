package nz.co.eroad.qaisystem.kafka;

import nz.co.eroad.qaisystem.execution.CodegenService;
import nz.co.eroad.qaisystem.model.BddScenario;
import nz.co.eroad.qaisystem.model.TestResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TestScriptsConsumer {

    private final ObjectMapper objectMapper;
    private final CodegenService codegenService;

    @KafkaListener(
            topics      = "${kafka.topics.test-scripts}",
            groupId     = "${spring.kafka.consumer.group-id}",
            concurrency = "3"
    )
    public void consume(ConsumerRecord<String, String> record,
                        Acknowledgment acknowledgment) {

        log.info("[TestScriptsConsumer] Received BDD record key='{}' partition={} offset={}",
                record.key(), record.partition(), record.offset());

        try {
            BddScenario scenario =
                    objectMapper.readValue(record.value(), BddScenario.class);

            log.info("[TestScriptsConsumer] Routing BDD scenario '{}' to Codegen Service",
                    scenario.getScenarioId());

            // Route to Execution Layer
            TestResult result = codegenService.generateAndExecute(scenario);

            log.info("[TestScriptsConsumer] Execution complete for '{}' — passed={}",
                    scenario.getScenarioId(), result.isPassed());

            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("[TestScriptsConsumer] Error processing BDD record key='{}': {}",
                    record.key(), e.getMessage(), e);
            acknowledgment.acknowledge();
        }
    }
}