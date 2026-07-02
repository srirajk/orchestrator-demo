package ai.conduit.chat.message;

import java.time.Instant;

/** Message response shape. Exposes {@code id} (string), never the Mongo {@code _id}. */
public record MessageDto(String id, String role, String content, Instant createdAt) {
    public static MessageDto from(Message m) {
        return new MessageDto(m.getId(), m.getRole(), m.getContent(), m.getCreatedAt());
    }
}
