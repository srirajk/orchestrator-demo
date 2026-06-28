package ai.meridian.gateway.domain.session;

import ai.meridian.gateway.orchestration.model.NodeResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.Map;

/**
 * Persists {@link ConversationSession} state in Redis.
 *
 * <p>Storage layout — Redis Hash keyed by {@code session:{conversationId}}:
 * <pre>
 *   relationship_id           REL-00042
 *   fund_id                   FND-7781          (may be absent)
 *   client_name               "Whitman Capital"  (may be absent)
 *   time_period               "Q1 2025"          (may be absent)
 *   domain                    "wealth"            (may be absent)
 *   agent_results             {JSON array}        (may be absent)
 *   agent_results_ts          1782309000000       (epoch ms)
 *   turn_count                3
 *   domain_workflow_state     {JSON map}          (may be absent)
 *   authorization_cache       {JSON map}          (may be absent)
 *   deferred_clarifications   {JSON map}          (may be absent)
 * </pre>
 *
 * <p>Each key has a configurable TTL (default 30 minutes) reset on every write so idle
 * sessions expire automatically.
 */
@Service
public class ConversationSessionStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationSessionStore.class);
    private static final String KEY_PREFIX = "session:";

    private static final TypeReference<List<NodeResult>>    NODE_RESULT_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP       = new TypeReference<>() {};

    private final JedisPooled jedis;
    private final ObjectMapper mapper;
    private final long sessionTtlSeconds;

    public ConversationSessionStore(
            JedisPooled jedis,
            ObjectMapper mapper,
            @Value("${meridian.session.ttl-seconds:1800}") long sessionTtlSeconds) {
        this.jedis = jedis;
        this.mapper = mapper;
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    /** Load session for {@code conversationId}; returns an empty session if not found. */
    public ConversationSession load(String conversationId) {
        String key = KEY_PREFIX + conversationId;
        try {
            Map<String, String> fields = jedis.hgetAll(key);
            if (fields == null || fields.isEmpty()) {
                return ConversationSession.empty(conversationId);
            }

            String relId      = fields.get("relationship_id");
            String fundId     = fields.get("fund_id");
            String clientName = fields.get("client_name");
            String timePeriod = fields.get("time_period");
            String domain     = fields.get("domain");
            long   ts         = parseLong(fields.get("agent_results_ts"), 0L);
            int    turns      = parseInt(fields.get("turn_count"), 0);

            List<NodeResult> results = null;
            String resultsJson = fields.get("agent_results");
            if (resultsJson != null && !resultsJson.isBlank()) {
                results = mapper.readValue(resultsJson, NODE_RESULT_LIST);
            }

            Map<String, String> domainWorkflowState = null;
            String dwsJson = fields.get("domain_workflow_state");
            if (dwsJson != null && !dwsJson.isBlank()) {
                domainWorkflowState = mapper.readValue(dwsJson, STRING_MAP);
            }

            Map<String, String> authorizationCache = null;
            String acJson = fields.get("authorization_cache");
            if (acJson != null && !acJson.isBlank()) {
                authorizationCache = mapper.readValue(acJson, STRING_MAP);
            }

            Map<String, String> deferredClarifications = null;
            String dcJson = fields.get("deferred_clarifications");
            if (dcJson != null && !dcJson.isBlank()) {
                deferredClarifications = mapper.readValue(dcJson, STRING_MAP);
            }

            return new ConversationSession(
                    conversationId, relId, fundId, clientName, timePeriod, domain,
                    results, ts, turns,
                    domainWorkflowState, authorizationCache, deferredClarifications
            );
        } catch (Exception e) {
            log.warn("SessionStore.load failed for {}: {} — returning empty session",
                    conversationId, e.getMessage());
            return ConversationSession.empty(conversationId);
        }
    }

    /**
     * Persist a session after a successful {@code FETCH_DATA} turn.
     * Resets the TTL so idle sessions expire.
     */
    public void save(ConversationSession session) {
        String key = KEY_PREFIX + session.conversationId();
        try {
            Map<String, String> fields = new java.util.LinkedHashMap<>();

            // Always write all fields — for null values, delete the Redis field so stale
            // data from a prior turn cannot carry forward.
            putOrDelete(fields, key, "relationship_id", session.relationshipId());
            putOrDelete(fields, key, "fund_id",          session.fundId());
            putOrDelete(fields, key, "client_name",      session.clientName());
            putOrDelete(fields, key, "time_period",      session.timePeriod());
            putOrDelete(fields, key, "domain",            session.domain());

            if (session.lastAgentResults() != null) {
                fields.put("agent_results",    mapper.writeValueAsString(session.lastAgentResults()));
                fields.put("agent_results_ts", String.valueOf(session.agentResultsEpochMs()));
            } else {
                jedis.hdel(key, "agent_results", "agent_results_ts");
            }

            fields.put("turn_count", String.valueOf(session.turnCount()));

            if (session.domainWorkflowState() != null) {
                fields.put("domain_workflow_state", mapper.writeValueAsString(session.domainWorkflowState()));
            } else {
                jedis.hdel(key, "domain_workflow_state");
            }

            if (session.authorizationCache() != null) {
                fields.put("authorization_cache", mapper.writeValueAsString(session.authorizationCache()));
            } else {
                jedis.hdel(key, "authorization_cache");
            }

            if (session.deferredClarifications() != null) {
                fields.put("deferred_clarifications", mapper.writeValueAsString(session.deferredClarifications()));
            } else {
                jedis.hdel(key, "deferred_clarifications");
            }

            if (!fields.isEmpty()) {
                jedis.hset(key, fields);
            }
            jedis.expire(key, sessionTtlSeconds);
            log.debug("SessionStore.save: {} relId={} turns={}",
                    session.conversationId(), session.relationshipId(), session.turnCount());
        } catch (Exception e) {
            log.warn("SessionStore.save failed for {}: {}", session.conversationId(), e.getMessage());
        }
    }

    /** Delete the session (e.g. on conversation reset). */
    public void delete(String conversationId) {
        try {
            jedis.del(KEY_PREFIX + conversationId);
        } catch (Exception e) {
            log.warn("SessionStore.delete failed for {}: {}", conversationId, e.getMessage());
        }
    }

    // --- helpers ---

    private void putOrDelete(Map<String, String> fields, String key, String field, String value) {
        if (value != null) {
            fields.put(field, value);
        } else {
            jedis.hdel(key, field);
        }
    }

    private long parseLong(String s, long def) {
        if (s == null) return def;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return def; }
    }

    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
