package ai.meridian.gateway.domain.session;

import ai.meridian.gateway.orchestration.model.NodeResult;

import java.util.List;

/**
 * Snapshot of conversation state persisted per {@code conversation_id} in Redis.
 *
 * <p>Written after every successful {@code FETCH_DATA} turn. Read at the start of
 * every turn to carry forward resolved entities and optionally reuse cached results.
 *
 * @param conversationId      LibreChat conversation ID (from X-Conversation-Id header
 *                            or derived from message history hash)
 * @param relationshipId      last successfully resolved relationship ID (e.g. REL-00042)
 * @param fundId              last successfully resolved fund ID (e.g. FND-7781), or null
 * @param lastAgentResults    agent results from the most recent FETCH turn (may be null)
 * @param agentResultsEpochMs wall-clock ms when {@code lastAgentResults} was stored
 * @param turnCount           number of completed turns in this session
 */
public record ConversationSession(
        String conversationId,
        String relationshipId,
        String fundId,
        List<NodeResult> lastAgentResults,
        long agentResultsEpochMs,
        int turnCount
) {

    /** True if cached agent results exist and are fresher than {@code ttlMs}. */
    public boolean hasFreshResults(long ttlMs) {
        if (lastAgentResults == null || lastAgentResults.isEmpty()) return false;
        return (System.currentTimeMillis() - agentResultsEpochMs) < ttlMs;
    }

    /** Returns a copy with updated entity IDs and fresh agent results. */
    public ConversationSession withResults(String relId, String fId, List<NodeResult> results) {
        return new ConversationSession(
                conversationId,
                relId != null ? relId : relationshipId,
                fId != null ? fId : fundId,
                results,
                System.currentTimeMillis(),
                turnCount + 1);
    }

    /** Empty session for a brand-new conversation. */
    public static ConversationSession empty(String conversationId) {
        return new ConversationSession(conversationId, null, null, null, 0L, 0);
    }
}
