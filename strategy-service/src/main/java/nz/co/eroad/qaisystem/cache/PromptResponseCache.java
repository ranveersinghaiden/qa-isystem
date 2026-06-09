package nz.co.eroad.qaisystem.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Redis-backed cache for AI-generated prompt responses.
 * All Redis failures are caught and degraded gracefully — the cache is advisory only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptResponseCache {

    private static final String KEY_PREFIX  = "qa:strategy:prompt-cache:";
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    /**
     * Stable cache key derived from the change's distinguishing attributes.
     *
     * @param changeType    first detected change type name (e.g. "NEW_FEATURE")
     * @param componentType first impacted component type name (e.g. "SERVICE")
     * @param riskLevel     risk level name (e.g. "MEDIUM")
     * @param repoName      base package / repo identifier
     */
    public record CacheKey(String changeType, String componentType,
                           String riskLevel, String repoName) {

        /**
         * SHA-256 hash of the key fields, truncated to the first 16 hex characters.
         */
        public String toHash() {
            String raw = changeType + "|" + componentType + "|" + riskLevel + "|" + repoName;
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
                var sb = new StringBuilder();
                for (byte b : hash) sb.append(String.format("%02x", b));
                return sb.substring(0, 16);
            } catch (NoSuchAlgorithmException e) {
                // SHA-256 is guaranteed by the JVM spec — never happens
                throw new RuntimeException("[PromptResponseCache] SHA-256 unavailable", e);
            }
        }
    }

    /** Statistics snapshot for the active cache entries. */
    public record CacheStats(long activeEntries) {}

    /**
     * Returns the cached AI response for the given key, or empty if not found or Redis is down.
     */
    public Optional<String> get(CacheKey key) {
        try {
            var value = redisTemplate.opsForValue().get(KEY_PREFIX + key.toHash());
            return Optional.ofNullable(value);
        } catch (Exception e) {
            log.warn("[PromptResponseCache] Redis GET failed — degrading gracefully: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Stores the AI response with a 24-hour TTL. Silently skips on Redis failure.
     */
    public void put(CacheKey key, String response) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + key.toHash(), response, CACHE_TTL);
        } catch (Exception e) {
            log.warn("[PromptResponseCache] Redis SET failed — degrading gracefully: {}", e.getMessage());
        }
    }

    /**
     * Counts the number of active entries in the cache by scanning keys with the prefix.
     */
    public CacheStats getStats() {
        try {
            Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
            long count = keys != null ? keys.size() : 0L;
            return new CacheStats(count);
        } catch (Exception e) {
            log.warn("[PromptResponseCache] Redis KEYS scan failed — returning zero stats: {}", e.getMessage());
            return new CacheStats(0L);
        }
    }
}

