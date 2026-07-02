package ai.conduit.chat.memory;

import ai.conduit.chat.config.AppProperties;
import ai.conduit.chat.conversation.Conversation;
import ai.conduit.chat.conversation.ConversationRepository;
import ai.conduit.chat.message.Message;
import ai.conduit.chat.message.MessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final AppProperties.Context contextConfig;
    private final ExecutorService backgroundExecutor;
    private final MessageService messageService;
    private final ConversationRepository conversationRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** Prevents stacking multiple concurrent regenerations for the same conversation. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public LlmSummaryService(AppProperties appProperties,
                             ExecutorService backgroundExecutor,
                             MessageService messageService,
                             ConversationRepository conversationRepository,
                             ObjectMapper objectMapper) {
        this.config = appProperties.summary();
        this.contextConfig = appProperties.context();
        this.backgroundExecutor = backgroundExecutor;
        this.messageService = messageService;
        this.conversationRepository = conversationRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
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
        String rendered = renderTranscript(transcript);
        String summary = callLlm(rendered);
        if (summary == null || summary.isBlank()) {
            return; // safe fallback: leave existing summary untouched
        }
        // Re-load under ownership to avoid clobbering a concurrently-updated document.
        conversationRepository.findByIdAndUserId(conversationId, userId).ifPresent(c -> {
            c.setSummary(summary.strip());
            conversationRepository.save(c);
            log.debug("[summary] updated rolling summary for {}", conversationId);
        });
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
                "max_tokens", contextConfig.summaryMaxTokens(),
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
