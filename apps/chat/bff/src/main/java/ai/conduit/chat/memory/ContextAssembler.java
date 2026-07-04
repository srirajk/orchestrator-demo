package ai.conduit.chat.memory;

import ai.conduit.chat.config.AppProperties;
import ai.conduit.chat.conversation.Conversation;
import ai.conduit.chat.message.Message;
import ai.conduit.chat.message.MessageService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
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

    // --- Metrics ---
    /** Summary attached (true) / not attached (false) per turn. Guardrail: if true == 0, compaction is silent-broken. */
    private final Counter summaryAttachedTrue;
    private final Counter summaryAttachedFalse;
    /** Distribution of context window size (messages) sent to the gateway per turn. */
    private final DistributionSummary contextMessages;
    /** Token distributions: assembled context vs full untrimmed transcript (tokens saved = full - context). */
    private final DistributionSummary contextTokens;
    private final DistributionSummary fullTokens;

    public ContextAssembler(MessageService messageService,
                            SummaryService summaryService,
                            TokenCounter tokenCounter,
                            AppProperties appProperties,
                            MeterRegistry meterRegistry) {
        this.messageService = messageService;
        this.summaryService = summaryService;
        this.tokenCounter = tokenCounter;
        this.context = appProperties.context();

        this.summaryAttachedTrue = Counter.builder("chat_compaction_summary_attached_total")
                .description("Turns where the rolling summary was attached to context. " +
                        "If attached=true stays 0, compaction is silently broken.")
                .tag("attached", "true")
                .register(meterRegistry);
        this.summaryAttachedFalse = Counter.builder("chat_compaction_summary_attached_total")
                .description("Turns where no rolling summary was attached to context.")
                .tag("attached", "false")
                .register(meterRegistry);
        this.contextMessages = DistributionSummary.builder("chat_compaction_context_messages")
                .description("Number of messages in the assembled context per turn.")
                .baseUnit("messages")
                .register(meterRegistry);
        this.contextTokens = DistributionSummary.builder("chat_compaction_tokens")
                .description("Estimated tokens in the assembled context window sent to the gateway.")
                .tag("kind", "context")
                .baseUnit("tokens")
                .register(meterRegistry);
        this.fullTokens = DistributionSummary.builder("chat_compaction_tokens")
                .description("Estimated tokens in the full untruncated transcript (before compaction).")
                .tag("kind", "full")
                .baseUnit("tokens")
                .register(meterRegistry);
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

        // --- GENERATION trigger (independent of attachment) ---
        // Once the transcript is large enough, (re)generate the facts-free rolling summary if we
        // have none yet, OR if the transcript has grown materially past the watermark captured at
        // the last generation. Fire-and-forget: never blocks this turn.
        String summary = conversation.getSummary();
        boolean haveSummary = summary != null && !summary.isBlank();
        if (transcriptTokens > context.summaryTriggerTokens()) {
            boolean stale = transcript.size()
                    >= conversation.getSummaryWatermark() + context.summaryRefreshMessages();
            if (!haveSummary || stale) {
                summaryService.requestRegeneration(conversation, userId);
            }
        }

        // The summary is only worth attaching if the transcript is large enough AND we actually
        // have one. Whether it gets attached is decided below, gated on real trimming.
        ChatMessage candidateSummary =
                (haveSummary && transcriptTokens > context.summaryTriggerTokens())
                        ? new ChatMessage("system", SUMMARY_PREFIX + summary)
                        : null;

        // First pass: fill newest-first WITHOUT reserving summary space, to learn whether the
        // full transcript fits in the window. The most-recent message is always included.
        FillResult full = fill(transcript, budget, 0);
        boolean trimmed = full.window().size() < transcript.size();

        // --- ATTACHMENT: attach the summary ONLY when messages were actually dropped ---
        // For transcripts that fit entirely in the window, the summary is pure overhead (context
        // would exceed the full transcript for no gain), so we skip it. When we do attach, reserve
        // its tokens and re-fill so the summary is counted against the budget.
        ChatMessage summaryMessage = null;
        List<ChatMessage> window;
        int used;
        if (trimmed && candidateSummary != null) {
            summaryMessage = candidateSummary;
            int summaryCost = tokenCounter.count(summaryMessage.content());
            FillResult withSummary = fill(transcript, budget, summaryCost);
            window = withSummary.window();
            used = summaryCost + withSummary.windowTokens();
        } else {
            window = full.window();
            used = full.windowTokens();
        }

        List<ChatMessage> assembled = new ArrayList<>(window.size() + 1);
        if (summaryMessage != null) {
            assembled.add(summaryMessage);
        }
        assembled.addAll(window);

        // --- Metrics ---
        // Guardrail: summary_attached{attached="true"} staying at 0 is the canary for silent compaction breakage.
        if (summaryMessage != null) {
            summaryAttachedTrue.increment();
        } else {
            summaryAttachedFalse.increment();
        }
        contextMessages.record(assembled.size());
        contextTokens.record(used);
        fullTokens.record(transcriptTokens);

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

    /**
     * Fills the recent window newest-first (then reverses to chronological order), stopping before
     * the first message whose token cost would push {@code reserved + windowTokens} over
     * {@code budget}. The most-recent message is always included even if it alone exceeds the
     * budget. {@code reserved} models tokens already spoken for (e.g. a leading summary message).
     */
    private FillResult fill(List<Message> transcript, int budget, int reserved) {
        List<ChatMessage> window = new ArrayList<>();
        int windowTokens = 0;
        for (int i = transcript.size() - 1; i >= 0; i--) {
            Message m = transcript.get(i);
            int cost = tokenCounter.count(m.getContent());
            if (!window.isEmpty() && reserved + windowTokens + cost > budget) {
                break;
            }
            window.add(new ChatMessage(m.getRole(), m.getContent()));
            windowTokens += cost;
        }
        Collections.reverse(window); // back to chronological order
        return new FillResult(window, windowTokens);
    }

    /** Result of a window fill: the chronological window and the tokens its messages consume. */
    private record FillResult(List<ChatMessage> window, int windowTokens) {
    }
}
