package ai.conduit.chat.conversation;

import ai.conduit.chat.message.MessageService;
import ai.conduit.chat.web.NotFoundException;
import org.springframework.stereotype.Service;

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

    public ConversationService(ConversationRepository conversationRepository, MessageService messageService) {
        this.conversationRepository = conversationRepository;
        this.messageService = messageService;
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
     * Bumps {@code updatedAt} (via auditing on save) and, if the conversation is still
     * untitled, derives a title from the first user message.
     */
    public void touchAndMaybeTitle(Conversation conversation, String firstUserMessage) {
        String title = conversation.getTitle();
        if (title == null || title.isBlank() || DEFAULT_TITLE.equals(title)) {
            String derived = firstUserMessage.strip();
            if (derived.length() > AUTO_TITLE_MAX_CHARS) {
                derived = derived.substring(0, AUTO_TITLE_MAX_CHARS);
            }
            conversation.setTitle(derived);
        }
        conversationRepository.save(conversation);
    }
}
