package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.model.ScenarioClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * No-op {@link RejectionLedger} fallback used when Redis is not configured, so the
 * planner and feedback service keep working with cross-PR learning disabled.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(RedisRejectionLedger.class)
public class NoOpRejectionLedger implements RejectionLedger {

    @Override
    public void recordRejection(String capability, ScenarioClass scenarioClass) {
        log.debug("[NoOpRejectionLedger] Redis disabled — dropping rejection {}:{}", capability, scenarioClass);
    }

    @Override
    public Set<ScenarioClass> recurringClasses(String capability) {
        return Set.of();
    }
}
