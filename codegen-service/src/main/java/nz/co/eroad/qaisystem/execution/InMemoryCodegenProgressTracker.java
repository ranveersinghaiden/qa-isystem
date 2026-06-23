package nz.co.eroad.qaisystem.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link CodegenProgressTracker} fallback when Redis is not configured. Single-instance
 * only — dedup and completion tracking do not span multiple codegen instances. Used in tests and
 * minimal local runs.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(RedisCodegenProgressTracker.class)
public class InMemoryCodegenProgressTracker implements CodegenProgressTracker {

    private final Map<String, Boolean>       claimed   = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> completed = new ConcurrentHashMap<>();

    @Override
    public boolean claimScenario(String scenarioId) {
        return claimed.putIfAbsent(scenarioId, Boolean.TRUE) == null;
    }

    @Override
    public int completeAndRemaining(String prId, int total) {
        int done = completed.computeIfAbsent(prId, k -> new AtomicInteger()).incrementAndGet();
        int remaining = Math.max(0, total - done);
        log.debug("[InMemoryCodegenProgressTracker] PR '{}' completed={} of {} (remaining={})",
                prId, done, total, remaining);
        return remaining;
    }
}

