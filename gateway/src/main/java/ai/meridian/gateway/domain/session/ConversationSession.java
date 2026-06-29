package ai.meridian.gateway.domain.session;

import ai.meridian.gateway.orchestration.model.NodeResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of conversation state persisted per {@code conversation_id} in Redis.
 *
 * <p>Written after every successful {@code FETCH_DATA} turn. Read at the start of
 * every turn to carry forward resolved entities and optionally reuse cached results.
 *
 * <p><b>World B:</b> resolved entities are stored as a generic {@code Map<String,String>}
 * keyed by the manifest entity-type KEY (the same key used in agent input binding, e.g.
 * {@code relationship_id}, {@code fund_id}, {@code period}). The gateway carries no named
 * wealth-shaped fields — adding an entity type is a manifest edit, not a new Java field.
 *
 * @param conversationId         LibreChat conversation ID (from X-Conversation-Id header
 *                               or derived from message history hash)
 * @param resolvedEntities       last successfully resolved entity IDs keyed by manifest
 *                               entity-type key (data keys, never Java literals); may be null
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
        Map<String, String> resolvedEntities,
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

    /** Returns the resolved entity ID for the given manifest entity-type key, or null. */
    public String resolvedEntity(String key) {
        if (key == null || resolvedEntities == null) return null;
        return resolvedEntities.get(key);
    }

    /** True if any entity has been resolved in a prior turn. */
    public boolean hasResolvedEntities() {
        return resolvedEntities != null && !resolvedEntities.isEmpty();
    }

    /** True if cached agent results exist and are fresher than {@code ttlMs}. */
    public boolean hasFreshResults(long ttlMs) {
        if (lastAgentResults == null || lastAgentResults.isEmpty()) return false;
        return (System.currentTimeMillis() - agentResultsEpochMs) < ttlMs;
    }

    /**
     * Returns a copy with the given resolved entities merged in and fresh agent results,
     * incrementing turnCount. Existing entity values are preserved when the incoming value
     * for that key is null (carry-forward); non-null incoming values overwrite.
     */
    public ConversationSession withResults(Map<String, String> newEntities, List<NodeResult> results) {
        Map<String, String> merged = new HashMap<>();
        if (resolvedEntities != null) merged.putAll(resolvedEntities);
        if (newEntities != null) {
            newEntities.forEach((k, v) -> {
                if (k != null && v != null) merged.put(k, v);
            });
        }
        return new ConversationSession(
                conversationId,
                merged.isEmpty() ? null : merged,
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

    /** Returns a copy with a single resolved entity set or overwritten (no turn increment). */
    public ConversationSession withResolvedEntity(String key, String value) {
        Map<String, String> merged = new HashMap<>();
        if (resolvedEntities != null) merged.putAll(resolvedEntities);
        if (key != null && value != null) merged.put(key, value);
        return new ConversationSession(
                conversationId, merged.isEmpty() ? null : merged, clientName, timePeriod, domain,
                lastAgentResults, agentResultsEpochMs, turnCount,
                domainWorkflowState, authorizationCache, deferredClarifications
        );
    }

    /** Returns a copy with the given sub-domain workflow state entry set or overwritten. */
    public ConversationSession withDomainWorkflowState(String subDomainKey, String state) {
        Map<String, String> newState = new HashMap<>();
        if (domainWorkflowState != null) newState.putAll(domainWorkflowState);
        newState.put(subDomainKey, state);
        return new ConversationSession(
                conversationId, resolvedEntities, clientName, timePeriod, domain,
                lastAgentResults, agentResultsEpochMs, turnCount,
                newState, authorizationCache, deferredClarifications
        );
    }

    /** Returns a copy with the given authz cache entry set or overwritten. */
    public ConversationSession withAuthCacheEntry(String cacheKey, String verdict) {
        Map<String, String> newCache = new HashMap<>();
        if (authorizationCache != null) newCache.putAll(authorizationCache);
        newCache.put(cacheKey, verdict);
        return new ConversationSession(
                conversationId, resolvedEntities, clientName, timePeriod, domain,
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
                conversationId, null, null, null, null,
                null, 0L, 0, null, null, null
        );
    }
}
