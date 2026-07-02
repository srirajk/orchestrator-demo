package ai.conduit.chat.conversation;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link Conversation}. Every query is scoped by {@code userId}
 * to enforce per-user isolation.
 */
public interface ConversationRepository extends MongoRepository<Conversation, String> {

    List<Conversation> findByUserIdAndArchivedFalseOrderByUpdatedAtDesc(String userId);

    Optional<Conversation> findByIdAndUserId(String id, String userId);

    long deleteByIdAndUserId(String id, String userId);
}
