package nz.co.eroad.qaisystem.github;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import nz.co.eroad.qaisystem.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.Optional;
/** Redis-backed PrTracker — durable across pod restarts. */
@Slf4j @Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.data.redis.host")
public class RedisPrTracker implements PrTracker {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private static final String PREFIX = "qa:pr:";
    @Override public void trackBdd(String b, int n, BddScenario s) {
        save(b, PrRecord.builder().branchName(b).prNumber(n).type(PrType.BDD).bddScenario(s).build());
    }
    @Override public void trackTest(String b, int n, TestScript s) {
        save(b, PrRecord.builder().branchName(b).prNumber(n).type(PrType.TEST).testScript(s).build());
    }
    @Override public Optional<PrRecord> findByBranch(String branch) {
        String json = redisTemplate.opsForValue().get(PREFIX + branch);
        if (json == null) return Optional.empty();
        try { return Optional.of(objectMapper.readValue(json, PrRecord.class)); }
        catch (JsonProcessingException e) { log.error("Redis deserialize error: {}", e.getMessage()); return Optional.empty(); }
    }
    @Override public void remove(String b) { redisTemplate.delete(PREFIX + b); }
    @Override public int size() {
        var keys = redisTemplate.keys(PREFIX + "*");
        return keys != null ? keys.size() : 0;
    }
    private void save(String branch, PrRecord record) {
        try { redisTemplate.opsForValue().set(PREFIX + branch, objectMapper.writeValueAsString(record)); }
        catch (JsonProcessingException e) { log.error("Redis serialize error: {}", e.getMessage()); }
    }
}
