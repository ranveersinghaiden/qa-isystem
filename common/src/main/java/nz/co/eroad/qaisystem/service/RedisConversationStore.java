package nz.co.eroad.qaisystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import nz.co.eroad.qaisystem.model.ConversationHistory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Redis-backed {@link ConversationStore} that GZIP-compresses and Base64-encodes
 * conversation history before storing it.  Activated only when
 * {@code spring.data.redis.host} is configured.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.data.redis.host")
public class RedisConversationStore implements ConversationStore {

    private static final String KEY_PREFIX             = "qa:chat:";
    /** Security cap on decompressed payload — NOT configurable (OOM guard). */
    private static final int    MAX_DECOMPRESSED_BYTES = 1_048_576; // 1 MB

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;
    private final int                 ttlDays;
    private final int                 maxHistoryChars;
    private final int                 maxCompressedBytes;

    /** Manual constructor required because of {@code @Value}-injected parameters. */
    public RedisConversationStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper        objectMapper,
            @Value("${aiqa.conversation.ttl-days:30}")                int ttlDays,
            @Value("${aiqa.conversation.max-history-chars:65536}")    int maxHistoryChars,
            @Value("${aiqa.conversation.max-compressed-bytes:32768}") int maxCompressedBytes
    ) {
        this.redisTemplate      = redisTemplate;
        this.objectMapper       = objectMapper;
        this.ttlDays            = ttlDays;
        this.maxHistoryChars    = maxHistoryChars;
        this.maxCompressedBytes = maxCompressedBytes;
        log.info("[RedisConversationStore] Initialised — ttlDays={} maxHistoryChars={} maxCompressedBytes={}",
                ttlDays, maxHistoryChars, maxCompressedBytes);
    }

    /** Persists the conversation history for the given key, applying size management. */
    @Override
    public void save(String conversationId, ConversationHistory history) {
        try {
            String encoded = buildEncodedValue(history);
            String key = KEY_PREFIX + conversationId;
            if (ttlDays > 0) {
                redisTemplate.opsForValue().set(key, encoded, ttlDays, TimeUnit.DAYS);
            } else {
                redisTemplate.opsForValue().set(key, encoded);
            }
            log.debug("[RedisConversationStore] Saved conversation '{}' — {} turns, {} encoded chars",
                    conversationId, history.turns().size(), encoded.length());
        } catch (Exception e) {
            log.error("[RedisConversationStore] Failed to save conversation '{}': {}",
                    conversationId, e.getMessage(), e);
        }
    }

    /** Loads and decompresses the stored history, or returns empty on miss/error. */
    @Override
    public Optional<ConversationHistory> load(String conversationId) {
        try {
            String encoded = redisTemplate.opsForValue().get(KEY_PREFIX + conversationId);
            if (encoded == null) return Optional.empty();
            byte[] compressed   = Base64.getDecoder().decode(encoded);
            byte[] decompressed = gunzip(compressed);
            ConversationHistory history = objectMapper.readValue(decompressed, ConversationHistory.class);
            log.debug("[RedisConversationStore] Loaded conversation '{}' — {} turns",
                    conversationId, history.turns().size());
            return Optional.of(history);
        } catch (Exception e) {
            log.error("[RedisConversationStore] Failed to load conversation '{}': {}",
                    conversationId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /** Removes the stored history for the given key. No-op if absent. */
    @Override
    public void remove(String conversationId) {
        redisTemplate.delete(KEY_PREFIX + conversationId);
        log.debug("[RedisConversationStore] Removed conversation '{}'", conversationId);
    }

    // ─── Size management + compression ───────────────────────────────────────

    /**
     * Applies the three-step size management algorithm and returns a Base64-encoded
     * GZIP-compressed JSON string ready to store in Redis.
     */
    private String buildEncodedValue(ConversationHistory history) throws Exception {
        String json = objectMapper.writeValueAsString(history);

        if (json.length() > maxHistoryChars) {
            // Step 1: try compressing the full history first
            byte[] compressed = gzip(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (compressed.length <= maxCompressedBytes) {
                log.debug("[RedisConversationStore] History exceeds {} chars but compresses to {} bytes — keeping all turns",
                        maxHistoryChars, compressed.length);
                return Base64.getEncoder().encodeToString(compressed);
            }

            // Step 2: compression insufficient — drop oldest turns until under uncompressed cap
            log.warn("[RedisConversationStore] History too large ({} chars, compressed {} bytes) — dropping oldest turns",
                    json.length(), compressed.length);
            var mutableTurns = new ArrayList<>(history.turns());
            int dropped = 0;
            while (json.length() > maxHistoryChars && mutableTurns.size() > 1) {
                mutableTurns.remove(0);
                dropped++;
                history = new ConversationHistory(
                        history.prId(), List.copyOf(mutableTurns),
                        history.totalTurns(), history.lastUpdated());
                json = objectMapper.writeValueAsString(history);
            }
            log.warn("[RedisConversationStore] Dropped {} old turns for conversation prId='{}'",
                    dropped, history.prId());
        }

        // Normal path: compress and encode
        byte[] compressed = gzip(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(compressed);
    }

    private static byte[] gzip(byte[] data) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var gos = new GZIPOutputStream(baos)) {
            gos.write(data);
        }
        return baos.toByteArray();
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        var bais = new ByteArrayInputStream(compressed);
        try (var gis = new GZIPInputStream(bais)) {
            var baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = gis.read(buf)) != -1) {
                total += n;
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw new IllegalStateException(
                            "[RedisConversationStore] Decompressed size exceeds " +
                            MAX_DECOMPRESSED_BYTES + " bytes — aborting to prevent OOM");
                }
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }
}

