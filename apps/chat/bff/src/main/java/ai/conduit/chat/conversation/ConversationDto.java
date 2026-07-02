package ai.conduit.chat.conversation;

import java.time.Instant;

/**
 * Conversation response shape. Exposes {@code id} (string), never the Mongo
 * {@code _id}, and omits {@code userId}/{@code summary} (internal).
 */
public record ConversationDto(
        String id,
        String title,
        String projectId,
        boolean archived,
        Instant createdAt,
        Instant updatedAt
) {
    public static ConversationDto from(Conversation c) {
        return new ConversationDto(
                c.getId(),
                c.getTitle(),
                c.getProjectId(),
                c.isArchived(),
                c.getCreatedAt(),
                c.getUpdatedAt());
    }
}
