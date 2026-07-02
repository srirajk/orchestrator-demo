package ai.conduit.chat.memory;

import ai.conduit.chat.config.AppProperties;
import ai.conduit.chat.conversation.Conversation;
import ai.conduit.chat.message.Message;
import ai.conduit.chat.message.MessageService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the per-turn context that the client (this BFF) sends to the stateless
 * gateway. The gateway holds no memory; the client owns it.
 *
 * <p>Contract (config-driven, no magic numbers):
 * <ul>
 *   <li>Send the last {@code context.recentMessages} messages, chronologically.</li>
 *   <li>Once the transcript exceeds {@code context.summaryAfterMessages} and a
 *       facts-free summary exists, prepend it as a leading {@code system} message.</li>
 *   <li>If the threshold is exceeded but no summary exists yet, trigger fire-and-forget
 *       regeneration (it will be available on a later turn).</li>
 * </ul>
 */
@Component
public class ContextAssembler {

    static final String SUMMARY_PREFIX = "Summary of earlier conversation:\n";

    private final MessageService messageService;
    private final SummaryService summaryService;
    private final AppProperties.Context context;

    public ContextAssembler(MessageService messageService,
                            SummaryService summaryService,
                            AppProperties appProperties) {
        this.messageService = messageService;
        this.summaryService = summaryService;
        this.context = appProperties.context();
    }

    /**
     * Assembles the context to send for this turn. Call after the user's message has
     * been persisted so it is part of the recent window.
     */
    public List<ChatMessage> assemble(Conversation conversation, String userId) {
        String conversationId = conversation.getId();
        long totalCount = messageService.count(conversationId);

        List<Message> recent = messageService.recentChronological(conversationId, context.recentMessages());

        List<ChatMessage> assembled = new ArrayList<>(recent.size() + 1);

        boolean longEnoughForSummary = totalCount > context.summaryAfterMessages();
        String summary = conversation.getSummary();

        if (longEnoughForSummary && summary != null && !summary.isBlank()) {
            assembled.add(new ChatMessage("system", SUMMARY_PREFIX + summary));
        } else if (longEnoughForSummary) {
            // No summary yet — kick off regeneration for a future turn (never blocks this one).
            summaryService.requestRegeneration(conversation, userId);
        }

        for (Message m : recent) {
            assembled.add(new ChatMessage(m.getRole(), m.getContent()));
        }
        return assembled;
    }
}
