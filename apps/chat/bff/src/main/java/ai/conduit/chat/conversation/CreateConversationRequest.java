package ai.conduit.chat.conversation;

/** Body for {@code POST /api/conversations}. {@code title} is optional. */
public record CreateConversationRequest(String title) {
}
