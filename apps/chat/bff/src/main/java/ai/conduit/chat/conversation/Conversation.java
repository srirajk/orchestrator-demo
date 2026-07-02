package ai.conduit.chat.conversation;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A conversation owned by a single user. The string {@code id} serializes as
 * {@code id} (not {@code _id}) in JSON responses, matching the web client.
 *
 * <p>The {@code summary} is a facts-free rolling topical summary owned entirely by
 * the client — never values, entities-as-truth, or entitlement conclusions.
 */
@Document(collection = "conversations")
public class Conversation {

    @Id
    private String id;

    @Indexed
    private String userId;

    private String title;

    @Indexed
    private String projectId;

    /** Facts-free rolling topical summary (topics/intent only). May be null. */
    private String summary;

    private boolean archived;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public Conversation() {
    }

    public Conversation(String userId, String title) {
        this.userId = userId;
        this.title = title;
        this.archived = false;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
