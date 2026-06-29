package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.model.ScenarioClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed {@link RejectionLedger}. Counts rejections per
 * {@code qa:fb:reject:<capability>} hash (field = scenario class). A class crossing
 * the recurrence threshold is treated as durably risky and forced back into the
 * planner's gap matrix. Activated only when {@code spring.data.redis.host} is set.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.data.redis.host")
public class RedisRejectionLedger implements RejectionLedger {

    private static final String KEY_PREFIX = "qa:fb:reject:";

    private final StringRedisTemplate redisTemplate;
    private final int threshold;
    private final int ttlDays;

    public RedisRejectionLedger(StringRedisTemplate redisTemplate,
                                @Value("${aiqa.feedback.recurrence-threshold:2}") int threshold,
                                @Value("${aiqa.feedback.ledger-ttl-days:90}") int ttlDays) {
        this.redisTemplate = redisTemplate;
        this.threshold = threshold;
        this.ttlDays = ttlDays;
    }

    @Override
    public void recordRejection(String capability, ScenarioClass scenarioClass) {
        if (capability == null || scenarioClass == null) return;
        String key = KEY_PREFIX + capability;
        long count = redisTemplate.opsForHash().increment(key, scenarioClass.name(), 1);
        redisTemplate.expire(key, ttlDays, TimeUnit.DAYS);
        log.info("[RedisRejectionLedger] capability='{}' class={} count={}", capability, scenarioClass, count);
    }

    @Override
    public Set<ScenarioClass> recurringClasses(String capability) {
        if (capability == null) return Set.of();
        Set<ScenarioClass> recurring = EnumSet.noneOf(ScenarioClass.class);
        for (Map.Entry<Object, Object> e : redisTemplate.opsForHash().entries(KEY_PREFIX + capability).entrySet()) {
            try {
                if (Long.parseLong(String.valueOf(e.getValue())) >= threshold) {
                    recurring.add(ScenarioClass.valueOf(String.valueOf(e.getKey())));
                }
            } catch (IllegalArgumentException ex) {
                log.warn("[RedisRejectionLedger] skipping bad ledger entry {}={}", e.getKey(), e.getValue());
            }
        }
        return recurring;
    }
}
