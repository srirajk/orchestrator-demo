package ai.conduit.chat.conversation;

import ai.conduit.chat.message.MessageService;
import ai.conduit.chat.web.NotFoundException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Conversation lifecycle. Every operation is scoped by {@code userId}: a
 * conversation that is not owned by the caller is indistinguishable from one that
 * does not exist (→ 404), matching the reference Node BFF.
 */
@Service
public class ConversationService {

    private static final String DEFAULT_TITLE = "New Conversation";
    private static final int AUTO_TITLE_MAX_CHARS = 60;

    private final ConversationRepository conversationRepository;
    private final MessageService messageService;
    private final MongoTemplate mongoTemplate;

    public ConversationService(ConversationRepository conversationRepository,
                               MessageService messageService,
                               MongoTemplate mongoTemplate) {
        this.conversationRepository = conversationRepository;
        this.messageService = messageService;
        this.mongoTemplate = mongoTemplate;
    }

    public List<Conversation> listActive(String userId) {
        return conversationRepository.findByUserIdAndArchivedFalseOrderByUpdatedAtDesc(userId);
    }

    public Conversation create(String userId, String title) {
        String resolved = (title == null || title.isBlank()) ? DEFAULT_TITLE : title;
        return conversationRepository.save(new Conversation(userId, resolved));
    }

    /** @throws NotFoundException if the conversation does not exist or is not owned by {@code userId}. */
    public Conversation getOwnedOrThrow(String id, String userId) {
        return conversationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Conversation not found"));
    }

    public Conversation update(String id, String userId, UpdateConversationRequest patch) {
        Conversation conversation = getOwnedOrThrow(id, userId);
        if (patch.title() != null) {
            conversation.setTitle(patch.title());
        }
        if (patch.projectId() != null) {
            conversation.setProjectId(patch.projectId());
        }
        if (patch.archived() != null) {
            conversation.setArchived(patch.archived());
        }
        return conversationRepository.save(conversation);
    }

    /** Deletes the conversation (verifying ownership) and cascade-deletes its messages. */
    public void delete(String id, String userId) {
        long removed = conversationRepository.deleteByIdAndUserId(id, userId);
        if (removed == 0) {
            throw new NotFoundException("Conversation not found");
        }
        messageService.deleteAllForConversation(id);
    }

    public Conversation save(Conversation conversation) {
        return conversationRepository.save(conversation);
    }

    /**
     * Bumps {@code updatedAt} and, if the conversation is still untitled, derives a title
     * from the first user message.
     *
     * <p><b>Field-scoped update (not a full-document save).</b> This runs on a background
     * thread <em>after</em> streaming, concurrently with the fire-and-forget rolling-summary
     * writer ({@code LlmSummaryService}). The {@code conversation} argument was loaded at the
     * start of the turn, before the summary writer ran, so its in-memory {@code summary} is
     * stale (usually null). Persisting the whole entity here would clobber the summary the
     * summarizer just wrote — a lost update that silently defeats context compaction. We
     * therefore {@code $set} only {@code title}/{@code updatedAt} and never touch
     * {@code summary}, so the two writers can no longer race on that field.
     */
    public void touchAndMaybeTitle(Conversation conversation, String firstUserMessage) {
        Update update = new Update().set("updatedAt", Instant.now());
        String title = conversation.getTitle();
        if (title == null || title.isBlank() || DEFAULT_TITLE.equals(title)) {
            String derived = firstUserMessage.strip();
            if (derived.length() > AUTO_TITLE_MAX_CHARS) {
                derived = derived.substring(0, AUTO_TITLE_MAX_CHARS);
            }
            update.set("title", derived);
        }
        Query query = new Query(Criteria.where("_id").is(conversation.getId())
                .and("userId").is(conversation.getUserId()));
        mongoTemplate.updateFirst(query, update, Conversation.class);
    }
}
