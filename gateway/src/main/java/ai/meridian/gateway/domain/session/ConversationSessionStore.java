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
 *   relationship_id       REL-00042
 *   fund_id               FND-7781          (may be absent)
 *   agent_results         {JSON array}      (may be absent)
 *   agent_results_ts      1782309000000     (epoch ms)
 *   turn_count            3
 * </pre>
 *
 * <p>Each key has a configurable TTL (default 30 minutes) reset on every write so idle
 * sessions expire automatically.
 */
@Service
public class ConversationSessionStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationSessionStore.class);
    private static final String KEY_PREFIX = "session:";

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
            long   ts         = parseLong(fields.get("agent_results_ts"), 0L);
            int    turns      = parseInt(fields.get("turn_count"), 0);

            List<NodeResult> results = null;
            String resultsJson = fields.get("agent_results");
            if (resultsJson != null && !resultsJson.isBlank()) {
                results = mapper.readValue(resultsJson, new TypeReference<>() {});
            }
            return new ConversationSession(conversationId, relId, fundId, results, ts, turns);
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
            if (session.relationshipId() != null) {
                fields.put("relationship_id", session.relationshipId());
            } else {
                jedis.hdel(key, "relationship_id");
            }
            if (session.fundId() != null) {
                fields.put("fund_id", session.fundId());
            } else {
                jedis.hdel(key, "fund_id");
            }
            if (session.lastAgentResults() != null) {
                fields.put("agent_results", mapper.writeValueAsString(session.lastAgentResults()));
                fields.put("agent_results_ts", String.valueOf(session.agentResultsEpochMs()));
            } else {
                jedis.hdel(key, "agent_results", "agent_results_ts");
            }
            fields.put("turn_count", String.valueOf(session.turnCount()));

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

    private long parseLong(String s, long def) {
        if (s == null) return def;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return def; }
    }

    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
