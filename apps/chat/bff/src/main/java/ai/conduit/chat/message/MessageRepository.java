package ai.conduit.chat.message;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/** Persistence for {@link Message}, scoped by conversation. */
public interface MessageRepository extends MongoRepository<Message, String> {

    /** All messages in a conversation, oldest first. */
    List<Message> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    /** Most-recent messages first; use with a {@link Pageable} limit for the context window. */
    List<Message> findByConversationIdOrderByCreatedAtDesc(String conversationId, Pageable pageable);

    long countByConversationId(String conversationId);

    long deleteByConversationId(String conversationId);
}
