package ai.conduit.chat.memory;

import ai.conduit.chat.config.AppProperties;
import ai.conduit.chat.conversation.Conversation;
import ai.conduit.chat.message.Message;
import ai.conduit.chat.message.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the per-turn context that the client (this BFF) sends to the stateless
 * gateway. The gateway holds no memory; the client owns it.
 *
 * <p><b>Token-budget driven</b> (config, no magic numbers):
 * <ul>
 *   <li>Include the most-recent messages newest-first until adding another would exceed
 *       {@code context.max-tokens}; the latest user message is always included, even if it
 *       alone exceeds the budget.</li>
 *   <li>If the full transcript's estimated tokens exceed {@code context.summary-trigger-tokens},
 *       prepend the facts-free rolling summary as a leading {@code system} message and count
 *       it against the budget. If no summary exists yet, trigger fire-and-forget regeneration
 *       for a future turn.</li>
 * </ul>
 */
@Component
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

    static final String SUMMARY_PREFIX = "Summary of earlier conversation:\n";

    private final MessageService messageService;
    private final SummaryService summaryService;
    private final TokenCounter tokenCounter;
    private final AppProperties.Context context;

    public ContextAssembler(MessageService messageService,
                            SummaryService summaryService,
                            TokenCounter tokenCounter,
                            AppProperties appProperties) {
        this.messageService = messageService;
        this.summaryService = summaryService;
        this.tokenCounter = tokenCounter;
        this.context = appProperties.context();
    }

    /**
     * Assembles the context to send for this turn. Call after the user's message has
     * been persisted so it is part of the recent window.
     */
    public List<ChatMessage> assemble(Conversation conversation, String userId) {
        String conversationId = conversation.getId();

        // Full transcript, oldest-first. Its total token size drives the summary trigger.
        List<Message> transcript = messageService.allInOrder(conversationId);
        int transcriptTokens = 0;
        for (Message m : transcript) {
            transcriptTokens += tokenCounter.count(m.getContent());
        }

        int budget = context.maxTokens();
        int used = 0;

        // Decide the leading summary system message (if the transcript is large enough).
        ChatMessage summaryMessage = null;
        if (transcriptTokens > context.summaryTriggerTokens()) {
            String summary = conversation.getSummary();
            if (summary != null && !summary.isBlank()) {
                summaryMessage = new ChatMessage("system", SUMMARY_PREFIX + summary);
                used += tokenCounter.count(summaryMessage.content());
            } else {
                // No summary yet — kick off regeneration for a future turn (never blocks this one).
                summaryService.requestRegeneration(conversation, userId);
            }
        }

        // Fill the window newest-first until the next message would exceed the budget.
        // The most-recent message (the latest user message) is always included.
        List<ChatMessage> window = new ArrayList<>();
        for (int i = transcript.size() - 1; i >= 0; i--) {
            Message m = transcript.get(i);
            int cost = tokenCounter.count(m.getContent());
            if (!window.isEmpty() && used + cost > budget) {
                break;
            }
            window.add(new ChatMessage(m.getRole(), m.getContent()));
            used += cost;
        }
        Collections.reverse(window); // back to chronological order

        List<ChatMessage> assembled = new ArrayList<>(window.size() + 1);
        if (summaryMessage != null) {
            assembled.add(summaryMessage);
        }
        assembled.addAll(window);

        // Observability: show exactly what compacted context leaves the BFF for this turn —
        // the leading facts-free summary (if any) + the recent window, trimmed to the token
        // budget. DEBUG-only; thresholds come from config, not literals here.
        if (log.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder();
            for (ChatMessage cm : assembled) {
                String c = cm.content();
                String preview = c.length() > 140 ? c.substring(0, 140) + "…" : c;
                sb.append("\n    [").append(cm.role()).append(", ~")
                        .append(tokenCounter.count(c)).append(" tok] ")
                        .append(preview.replace('\n', ' '));
            }
            log.debug("[context] convo={} transcript={} msgs/{} tok | budget={} tok trigger={} tok "
                            + "-> assembled {} msgs (summaryAttached={}, windowMsgs={}):{}",
                    conversationId, transcript.size(), transcriptTokens,
                    budget, context.summaryTriggerTokens(),
                    assembled.size(), summaryMessage != null, window.size(), sb);
        }
        return assembled;
    }
}
