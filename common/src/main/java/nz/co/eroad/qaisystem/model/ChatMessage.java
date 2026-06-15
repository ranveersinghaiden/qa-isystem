package nz.co.eroad.qaisystem.model;

/**
 * Represents a single turn in a multi-turn AI conversation.
 * Roles follow the OpenAI/GitHub Models convention: "system", "user", "assistant".
 */
public record ChatMessage(String role, String content) {

    /** Factory for a system-role message. */
    public static ChatMessage system(String content)    { return new ChatMessage("system",    content); }

    /** Factory for a user-role message. */
    public static ChatMessage user(String content)      { return new ChatMessage("user",      content); }

    /** Factory for an assistant-role message. */
    public static ChatMessage assistant(String content) { return new ChatMessage("assistant", content); }
}

