package nz.co.eroad.qaisystem.service;

import lombok.extern.slf4j.Slf4j;
import nz.co.eroad.qaisystem.model.ConversationHistory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fallback {@link ConversationStore} used when Redis is not configured.
 * Data is lost on pod restart — suitable for local development and testing only.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(RedisConversationStore.class)
public class InMemoryConversationStore implements ConversationStore {

    private final Map<String, ConversationHistory> store = new ConcurrentHashMap<>();

    /** Stores the conversation history in the in-memory map. */
    @Override
    public void save(String conversationId, ConversationHistory history) {
        store.put(conversationId, history);
        log.debug("[InMemoryConversationStore] Saved conversation '{}' — {} turns",
                conversationId, history.turns().size());
    }

    /** Returns the stored history or empty if not present. */
    @Override
    public Optional<ConversationHistory> load(String conversationId) {
        return Optional.ofNullable(store.get(conversationId));
    }

    /** Removes the stored history for the given key. No-op if absent. */
    @Override
    public void remove(String conversationId) {
        store.remove(conversationId);
        log.debug("[InMemoryConversationStore] Removed conversation '{}'", conversationId);
    }
}

