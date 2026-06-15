package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.model.ConversationHistory;

import java.util.Optional;

/**
 * Stores and retrieves the AI conversation history per PR.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link RedisConversationStore} — durable, GZIP-compressed, TTL-expiring (default)</li>
 *   <li>{@link InMemoryConversationStore} — in-memory fallback when Redis is not configured</li>
 * </ul>
 *
 * @see RedisConversationStore
 * @see InMemoryConversationStore
 */
public interface ConversationStore {

    /**
     * Persists the conversation history for the given conversation ID.
     *
     * @param conversationId unique key (e.g. {@code prId + ":bdd"} or {@code prId + ":test"})
     * @param history        the conversation snapshot to persist
     */
    void save(String conversationId, ConversationHistory history);

    /**
     * Loads the conversation history for the given conversation ID.
     *
     * @param conversationId the key used in {@link #save}
     * @return the stored history, or {@link Optional#empty()} if not found / on error
     */
    Optional<ConversationHistory> load(String conversationId);

    /**
     * Removes the conversation history for the given conversation ID.
     * No-op if the key does not exist.
     */
    void remove(String conversationId);
}

