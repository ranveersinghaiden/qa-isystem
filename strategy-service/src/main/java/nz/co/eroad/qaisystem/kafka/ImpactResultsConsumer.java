package nz.co.eroad.qaisystem.kafka;

import nz.co.eroad.qaisystem.agent.StrategyAgent;
import nz.co.eroad.qaisystem.github.PrTracker;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import nz.co.eroad.qaisystem.model.PrRecord;
import nz.co.eroad.qaisystem.model.PrType;
import nz.co.eroad.qaisystem.service.RepoContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Phase 2 entry point: consumes ImpactEnvelope from ImpactResultsQueue
 * and triggers the Strategy Agent decision.
 *
 * <p>When {@code aiqa.strategy.refresh-on-analysis=true} (the default), the service
 * pulls the latest commits from the target test repo before every coverage check.
 * This ensures that tests merged by a previous pipeline run are reflected in the
 * coverage index — preventing duplicate scenario generation when the same PR (or a
 * similar one) is submitted again.
 *
 * <p>Disable for high-throughput scenarios where the extra git pull latency is
 * unacceptable: {@code aiqa.strategy.refresh-on-analysis=false}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImpactResultsConsumer {

    private final ObjectMapper        objectMapper;
    private final StrategyAgent       strategyAgent;
    private final RepoContextService  repoContextService;
    private final PrTracker           prTracker;

    /** Pull latest test repo before every coverage analysis (default: true). */
    @Value("${aiqa.strategy.refresh-on-analysis:true}")
    private boolean refreshOnAnalysis;

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

            // Deduplication guard: skip if a BDD PR for this prId is already pending review.
            // This prevents duplicate BDD generation when the same payload is resubmitted
            // (same repo + sourceBranch + title = same deterministic prId).
            boolean alreadyPending = prTracker.findAll().stream()
                    .filter(r -> r.getType() == PrType.BDD)
                    .anyMatch(r -> r.getBddScenario() != null
                            && envelope.getPrId().equals(r.getBddScenario().getPrId()));
            if (alreadyPending) {
                log.warn("[ImpactResultsConsumer] PR '{}' already has a pending BDD review — " +
                         "skipping duplicate pipeline run.", envelope.getPrId());
                ack.acknowledge();
                return;
            }

            if (refreshOnAnalysis) {
                log.info("[ImpactResultsConsumer] Refreshing repo context before analysis " +
                         "(aiqa.strategy.refresh-on-analysis=true) for PR '{}'", envelope.getPrId());
                repoContextService.refresh();
            }

            strategyAgent.decide(envelope);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("[ImpactResultsConsumer] Error for key='{}': {}", record.key(), e.getMessage(), e);
            ack.acknowledge();
        }
    }
}
