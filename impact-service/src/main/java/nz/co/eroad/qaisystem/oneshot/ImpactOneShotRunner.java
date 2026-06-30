package nz.co.eroad.qaisystem.oneshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nz.co.eroad.qaisystem.engine.ImpactEngine;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import nz.co.eroad.qaisystem.model.PullRequest;
import nz.co.eroad.qaisystem.state.PrHistory;
import nz.co.eroad.qaisystem.state.StateStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * One-shot entry point for the impact stage (scale-to-zero K8s runner). Active only under the
 * {@code oneshot} profile; the always-on {@code FeatureUpdatesConsumer} Kafka path is unaffected.
 *
 * <p>{@code --mode oneshot --pr-id X}: load the stored {@code payload} for the PR, deserialize a
 * {@link PullRequest}, run {@link ImpactEngine#analyze(PullRequest)} (the SAME bean the Kafka
 * consumer calls), then persist the envelope JSON and risk level, and exit.
 *
 * <p>Exit codes: {@code 0} success, {@code 2} missing/blank input (no pr-id, no PR row, or no
 * payload), {@code 1} unexpected failure.
 */
@Slf4j
@Component
@Profile("oneshot")
@RequiredArgsConstructor
public class ImpactOneShotRunner implements CommandLineRunner {

    private final ImpactEngine impactEngine;
    private final StateStore stateStore;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) {
        System.exit(execute(args));
    }

    /** Testable core: returns the process exit code without calling {@link System#exit(int)}. */
    int execute(String[] args) {
        OneShotArgs parsed = OneShotArgs.parse(args);
        String mode = parsed.mode();
        Optional<String> prIdOpt = parsed.prId();

        if (prIdOpt.isEmpty()) {
            log.error("[ImpactOneShotRunner] Missing required --pr-id");
            return 2;
        }
        String prId = prIdOpt.get();

        if (!"oneshot".equals(mode)) {
            log.error("[ImpactOneShotRunner] Unsupported --mode '{}' (expected 'oneshot')", mode);
            return 2;
        }

        try {
            Optional<PrHistory> prOpt = stateStore.findPr(prId);
            if (prOpt.isEmpty()) {
                log.error("[ImpactOneShotRunner] No pr_history row for prId='{}'", prId);
                return 2;
            }
            String payload = prOpt.get().getPayload();
            if (payload == null || payload.isBlank()) {
                log.error("[ImpactOneShotRunner] No payload stored for prId='{}'", prId);
                return 2;
            }

            PullRequest pr = objectMapper.readValue(payload, PullRequest.class);
            log.info("[ImpactOneShotRunner] Analyzing PR '{}' ({} byte payload)", prId, payload.length());

            ImpactEnvelope envelope = impactEngine.analyze(pr);

            String envelopeJson = objectMapper.writeValueAsString(envelope);
            stateStore.saveImpactEnvelope(prId, envelopeJson);
            String risk = envelope.getRiskLevel() == null ? null : envelope.getRiskLevel().name();
            stateStore.updateRisk(prId, risk);

            log.info("[ImpactOneShotRunner] Done prId='{}' risk='{}' envelope={} bytes",
                    prId, risk, envelopeJson.length());
            return 0;
        } catch (Exception e) {
            log.error("[ImpactOneShotRunner] Failed for prId='{}': {}", prId, e.getMessage(), e);
            return 1;
        }
    }
}
