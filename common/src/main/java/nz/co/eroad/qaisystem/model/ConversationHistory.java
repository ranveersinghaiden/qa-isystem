package nz.co.eroad.qaisystem.model;

import java.time.Instant;
import java.util.List;

/**
 * Immutable snapshot of the AI conversation for one PR.
 * Serialised to JSON, GZIP-compressed, and stored in Redis at {@code qa:chat:{conversationId}}.
 *
 * <p>{@code prId} identifies the source PR (for logging/debugging only).
 * {@code turns} is the ordered list of user + assistant turns (system prompts are NOT stored
 * in turns — they are rebuilt fresh on every call).
 */
public record ConversationHistory(
        String            prId,
        List<ChatMessage> turns,
        int               totalTurns,
        Instant           lastUpdated
) {
    /** Convenience factory for a brand-new (empty) conversation for the given PR. */
    public static ConversationHistory empty(String prId) {
        return new ConversationHistory(prId, List.of(), 0, Instant.now());
    }
}

