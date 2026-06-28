package ai.meridian.gateway.domain.session;

import ai.meridian.gateway.orchestration.model.NodeResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Snapshot of conversation state persisted per {@code conversation_id} in Redis.
 *
 * <p>Written after every successful {@code FETCH_DATA} turn. Read at the start of
 * every turn to carry forward resolved entities and optionally reuse cached results.
 *
 * @param conversationId         LibreChat conversation ID (from X-Conversation-Id header
 *                               or derived from message history hash)
 * @param relationshipId         last successfully resolved relationship ID (e.g. REL-00042)
 * @param fundId                 last successfully resolved fund ID (e.g. FND-7781), or null
 * @param clientName             human-readable client name resolved in a prior turn, or null
 * @param timePeriod             time period string resolved in a prior turn (e.g. "Q1 2025"), or null
 * @param domain                 primary domain selected for the last turn (e.g. "wealth"), or null
 * @param lastAgentResults       agent results from the most recent FETCH turn (may be null)
 * @param agentResultsEpochMs    wall-clock ms when {@code lastAgentResults} was stored
 * @param turnCount              number of completed turns in this session
 * @param domainWorkflowState    per-sub-domain opaque state strings keyed by sub-domain name
 * @param authorizationCache     short-lived authz verdict cache keyed by cacheKey string
 * @param deferredClarifications pending clarification slots keyed by slot name
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationSession(
        String conversationId,
        String relationshipId,
        String fundId,
        String clientName,
        String timePeriod,
        String domain,
        List<NodeResult> lastAgentResults,
        long agentResultsEpochMs,
        int turnCount,
        Map<String, String> domainWorkflowState,
        Map<String, String> authorizationCache,
        Map<String, String> deferredClarifications
) {

    /** True if cached agent results exist and are fresher than {@code ttlMs}. */
    public boolean hasFreshResults(long ttlMs) {
        if (lastAgentResults == null || lastAgentResults.isEmpty()) return false;
        return (System.currentTimeMillis() - agentResultsEpochMs) < ttlMs;
    }

    /** Returns a copy with updated entity IDs and fresh agent results, incrementing turnCount. */
    public ConversationSession withResults(String relId, String fId, List<NodeResult> results) {
        return new ConversationSession(
                conversationId,
                relId != null ? relId : relationshipId,
                fId != null ? fId : fundId,
                clientName,
                timePeriod,
                domain,
                results,
                System.currentTimeMillis(),
                turnCount + 1,
                domainWorkflowState,
                authorizationCache,
                deferredClarifications
        );
    }

    /** Returns a copy with the given sub-domain workflow state entry set or overwritten. */
    public ConversationSession withDomainWorkflowState(String subDomainKey, String state) {
        Map<String, String> newState = new java.util.HashMap<>();
        if (domainWorkflowState != null) newState.putAll(domainWorkflowState);
        newState.put(subDomainKey, state);
        return new ConversationSession(
                conversationId, relationshipId, fundId, clientName, timePeriod, domain,
                lastAgentResults, agentResultsEpochMs, turnCount,
                newState, authorizationCache, deferredClarifications
        );
    }

    /** Returns a copy with the given authz cache entry set or overwritten. */
    public ConversationSession withAuthCacheEntry(String cacheKey, String verdict) {
        Map<String, String> newCache = new java.util.HashMap<>();
        if (authorizationCache != null) newCache.putAll(authorizationCache);
        newCache.put(cacheKey, verdict);
        return new ConversationSession(
                conversationId, relationshipId, fundId, clientName, timePeriod, domain,
                lastAgentResults, agentResultsEpochMs, turnCount,
                domainWorkflowState, newCache, deferredClarifications
        );
    }

    /** Returns the workflow state for the given sub-domain key, or null. */
    public String getDomainWorkflowState(String subDomainKey) {
        if (domainWorkflowState == null) return null;
        return domainWorkflowState.get(subDomainKey);
    }

    /** Returns the cached authz verdict for the given cache key, or null. */
    public String getAuthCacheEntry(String cacheKey) {
        if (authorizationCache == null) return null;
        return authorizationCache.get(cacheKey);
    }

    /** Empty session for a brand-new conversation. */
    public static ConversationSession empty(String conversationId) {
        return new ConversationSession(
                conversationId, null, null, null, null, null,
                null, 0L, 0, null, null, null
        );
    }
}
