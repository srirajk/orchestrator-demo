package ai.conduit.chat.conversation;

/** Body for {@code PATCH /api/conversations/{id}}. All fields optional (partial update). */
public record UpdateConversationRequest(String title, String projectId, Boolean archived) {
}
