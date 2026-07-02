package ai.conduit.chat.message;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A single chat message belonging to a conversation. {@code role} is
 * {@code "user"} or {@code "assistant"}. Serializes {@code id} (not {@code _id}).
 */
@Document(collection = "messages")
public class Message {

    @Id
    private String id;

    @Indexed
    private String conversationId;

    @Indexed
    private String userId;

    private String role;

    private String content;

    @CreatedDate
    private Instant createdAt;

    public Message() {
    }

    public Message(String conversationId, String userId, String role, String content) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.role = role;
        this.content = content;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
