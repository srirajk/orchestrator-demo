package ai.conduit.chat.message;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/** Persistence-facing operations for conversation messages. */
@Service
public class MessageService {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    private final MessageRepository repository;

    public MessageService(MessageRepository repository) {
        this.repository = repository;
    }

    public Message append(String conversationId, String userId, String role, String content) {
        return repository.save(new Message(conversationId, userId, role, content));
    }

    public List<Message> allInOrder(String conversationId) {
        return repository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /** The last {@code limit} messages, returned in chronological (oldest-first) order. */
    public List<Message> recentChronological(String conversationId, int limit) {
        List<Message> desc = repository.findByConversationIdOrderByCreatedAtDesc(
                conversationId, PageRequest.of(0, limit));
        List<Message> chronological = new java.util.ArrayList<>(desc);
        java.util.Collections.reverse(chronological);
        return chronological;
    }

    public long count(String conversationId) {
        return repository.countByConversationId(conversationId);
    }

    public long deleteAllForConversation(String conversationId) {
        return repository.deleteByConversationId(conversationId);
    }
}
