package nz.co.eroad.qaisystem.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed {@link CodegenProgressTracker} — safe across horizontally-scaled codegen
 * instances. Active when {@code spring.data.redis.host} is set.
 *
 * <p>Keys:
 * <ul>
 *   <li>{@code qa:codegen:claim:{scenarioId}} — SETNX dedup marker (7-day TTL)</li>
 *   <li>{@code qa:codegen:done:{prId}} — completed-scenario counter (7-day TTL)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.data.redis.host")
public class RedisCodegenProgressTracker implements CodegenProgressTracker {

    private static final String CLAIM_PREFIX = "qa:codegen:claim:";
    private static final String DONE_PREFIX  = "qa:codegen:done:";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    @Override
    public boolean claimScenario(String scenarioId) {
        Boolean firstClaim = redis.opsForValue()
                .setIfAbsent(CLAIM_PREFIX + scenarioId, "1", TTL);
        return Boolean.TRUE.equals(firstClaim);
    }

    @Override
    public int completeAndRemaining(String prId, int total) {
        String key = DONE_PREFIX + prId;
        Long completed = redis.opsForValue().increment(key);
        redis.expire(key, TTL);
        long done = completed == null ? 0 : completed;
        int remaining = (int) Math.max(0, total - done);
        log.debug("[RedisCodegenProgressTracker] PR '{}' completed={} of {} (remaining={})",
                prId, done, total, remaining);
        return remaining;
    }
}

