package ai.conduit.chat.memory;

import ai.conduit.chat.config.AppProperties;
import ai.conduit.chat.conversation.Conversation;
import ai.conduit.chat.message.Message;
import ai.conduit.chat.message.MessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * LLM-backed {@link SummaryService}. Regenerates a conversation's facts-free rolling
 * topical summary on a background virtual thread and persists it.
 *
 * <p>Safety properties:
 * <ul>
 *   <li><b>Never blocks or fails the request</b> — work is submitted to a background
 *       executor and all errors are caught and logged.</li>
 *   <li><b>Safe fallback</b> — if the summary LLM is not configured or is unreachable,
 *       the existing summary is left unchanged (effective no-op).</li>
 *   <li><b>Facts-free</b> — the prompt forbids values, entities-as-truth, and
 *       entitlement conclusions; only topics/intent are retained.</li>
 * </ul>
 */
@Service
public class LlmSummaryService implements SummaryService {

    private static final Logger log = LoggerFactory.getLogger(LlmSummaryService.class);

    private static final String FACTS_FREE_SYSTEM_PROMPT = """
            You maintain a rolling, FACTS-FREE topical summary of a conversation between a \
            user and an enterprise banking assistant. Its ONLY purpose is continuity: to \
            remind the assistant what topics and intents have been discussed.

            STRICT RULES — the summary MUST NOT contain any of the following:
            - Concrete values or figures (balances, amounts, dates, percentages, counts).
            - Specific entity identities treated as fact (client names, account/relationship/\
            policy/fund identifiers, IDs).
            - Any entitlement or authorization conclusion (who is or isn't allowed to see what).
            - Any answer content, results, or data returned by the assistant.

            Capture ONLY: the subjects the user has asked about and their intent/goal, in \
            neutral, generic terms (e.g. "the user asked about portfolio performance and \
            then about fees"). Prefer categories over specifics. Be concise.

            Output ONLY the summary prose, no preamble.""";

    private final AppProperties.Summary config;
    private final ExecutorService backgroundExecutor;
    private final MessageService messageService;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** Prevents stacking multiple concurrent regenerations for the same conversation. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    // --- Metrics ---
    /** Latency of the LLM summarisation call (fire-and-forget background, but still timed). */
    private final Timer summaryTimer;
    /** Count of failed summarisation attempts (LLM non-2xx or exception). */
    private final Counter summaryErrors;

    public LlmSummaryService(AppProperties appProperties,
                             ExecutorService backgroundExecutor,
                             MessageService messageService,
                             MongoTemplate mongoTemplate,
                             ObjectMapper objectMapper,
                             MeterRegistry meterRegistry) {
        this.config = appProperties.summary();
        this.backgroundExecutor = backgroundExecutor;
        this.messageService = messageService;
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
        // Pinned to HTTP/1.1: the base URL is configurable to any OpenAI-compatible endpoint,
        // including a cleartext local one, where an h2c upgrade attempt would 404.
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        this.summaryTimer = Timer.builder("chat_summary_generation_seconds")
                .description("Latency of the LLM facts-free rolling summary call.")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(meterRegistry);
        this.summaryErrors = Counter.builder("chat_summary_generation_errors_total")
                .description("Number of failed LLM summary generation attempts (non-2xx or exception).")
                .register(meterRegistry);
    }

    @Override
    public void requestRegeneration(Conversation conversation, String userId) {
        if (!config.isEnabled()) {
            log.debug("[summary] LLM not configured; skipping regeneration for {}", conversation.getId());
            return;
        }
        String conversationId = conversation.getId();
        if (!inFlight.add(conversationId)) {
            return; // already regenerating
        }
        backgroundExecutor.execute(() -> {
            try {
                regenerate(conversationId, userId);
            } catch (Exception ex) {
                // Fire-and-forget: never propagate. Existing summary is retained.
                log.warn("[summary] regeneration failed for {}: {}", conversationId, ex.getMessage());
            } finally {
                inFlight.remove(conversationId);
            }
        });
    }

    private void regenerate(String conversationId, String userId) throws Exception {
        List<Message> transcript = messageService.allInOrder(conversationId);
        if (transcript.isEmpty()) {
            return;
        }
        // Watermark: the number of messages actually summarised. ContextAssembler re-triggers
        // regeneration once the transcript has grown materially past this, so the summary rolls.
        int summarizedMessageCount = transcript.size();
        String rendered = renderTranscript(transcript);
        // Time the LLM call and count failures. Fire-and-forget: errors are caught by the caller.
        String summary;
        try {
            summary = summaryTimer.recordCallable(() -> callLlm(rendered));
        } catch (Exception ex) {
            summaryErrors.increment();
            throw ex;
        }
        if (summary == null || summary.isBlank()) {
            // callLlm returned null (non-2xx or empty body) — count as an error.
            summaryErrors.increment();
            return; // safe fallback: leave existing summary untouched
        }
        // Field-scoped update (never a full-document save from this background writer): $set only
        // summary + its watermark, scoped by _id+userId. A whole-entity save here would race with
        // ConversationService#touchAndMaybeTitle and clobber title/projectId/archived (the same
        // lost-update that was already fixed there). Scoping the update to owned docs also enforces
        // ownership without a separate read.
        Query query = new Query(Criteria.where("_id").is(conversationId).and("userId").is(userId));
        Update update = new Update()
                .set("summary", summary.strip())
                .set("summaryWatermark", summarizedMessageCount);
        long modified = mongoTemplate.updateFirst(query, update, Conversation.class).getModifiedCount();
        if (modified > 0) {
            log.debug("[summary] updated rolling summary for {} (watermark={} msgs)",
                    conversationId, summarizedMessageCount);
        }
    }

    private static String renderTranscript(List<Message> transcript) {
        StringBuilder sb = new StringBuilder();
        for (Message m : transcript) {
            sb.append(m.getRole()).append(": ").append(m.getContent()).append('\n');
        }
        return sb.toString();
    }

    private String callLlm(String transcript) throws Exception {
        Map<String, Object> body = Map.of(
                "model", config.model(),
                "temperature", config.temperature(),
                "max_tokens", config.maxTokens(),
                "messages", List.of(
                        Map.of("role", "system", "content", FACTS_FREE_SYSTEM_PROMPT),
                        Map.of("role", "user", "content",
                                "Conversation transcript to summarize (topics/intent only):\n\n" + transcript)
                )
        );

        HttpRequest request = HttpRequest.newBuilder(URI.create(chatCompletionsUrl()))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.warn("[summary] LLM returned {}", response.statusCode());
            return null;
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        return content.isTextual() ? content.asText() : null;
    }

    private String chatCompletionsUrl() {
        String base = config.baseUrl();
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/chat/completions";
    }
}
