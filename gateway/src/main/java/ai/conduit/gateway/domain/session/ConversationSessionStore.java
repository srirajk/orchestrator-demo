package ai.conduit.gateway.domain.session;

import ai.conduit.gateway.orchestration.model.NodeResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persists {@link ConversationSession} state in Redis.
 *
 * <p>Storage layout — Redis Hash keyed by {@code session:{conversationId}}:
 * <pre>
 *   resolved_entities         {JSON map}          (entity-key → resolved id; may be absent)
 *   client_name               "..."               (may be absent)
 *   time_period               "Q1 2025"           (may be absent)
 *   domain                    "wealth"            (may be absent)
 *   agent_results             {JSON array}        (may be absent)
 *   agent_results_ts          1782309000000       (epoch ms)
 *   turn_count                3
 *   domain_workflow_state     {JSON map}          (may be absent)
 *   authorization_cache       {JSON map}          (may be absent)
 *   deferred_clarifications   {JSON map}          (may be absent)
 * </pre>
 *
 * <p><b>World B:</b> resolved entities live inside the generic {@code resolved_entities}
 * sub-map keyed by manifest entity-type key — the gateway never writes a per-entity Redis
 * field literal. The load path also folds any legacy top-level field (older sessions wrote
 * each entity as its own hash field) into the map by its data key, so live sessions written
 * before this refactor keep carrying forward.
 *
 * <p>Each key has a configurable TTL (default 30 minutes) reset on every write so idle
 * sessions expire automatically.
 */
@Service
public class ConversationSessionStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationSessionStore.class);
    private static final String KEY_PREFIX = "session:";
    private static final String RESOLVED_ENTITIES_FIELD = "resolved_entities";

    /**
     * Top-level hash fields the store owns. Any OTHER top-level field is treated as a legacy
     * per-entity field (written by a pre-refactor build) and folded into resolvedEntities by
     * its key — no entity-field literal is hardcoded here.
     */
    private static final Set<String> RESERVED_FIELDS = Set.of(
            RESOLVED_ENTITIES_FIELD, "client_name", "time_period", "domain",
            "agent_results", "agent_results_ts", "turn_count",
            "domain_workflow_state", "authorization_cache", "deferred_clarifications");

    private static final TypeReference<List<NodeResult>>    NODE_RESULT_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP       = new TypeReference<>() {};

    private final JedisPooled jedis;
    private final ObjectMapper mapper;
    private final long sessionTtlSeconds;

    public ConversationSessionStore(
            JedisPooled jedis,
            ObjectMapper mapper,
            @Value("${conduit.session.ttl-seconds:1800}") long sessionTtlSeconds) {
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

            // Resolved entities: read the generic sub-map, then fold in any legacy top-level
            // entity fields (older sessions) by their data key for backward compatibility.
            Map<String, String> resolvedEntities = new java.util.LinkedHashMap<>();
            String reJson = fields.get(RESOLVED_ENTITIES_FIELD);
            if (reJson != null && !reJson.isBlank()) {
                resolvedEntities.putAll(mapper.readValue(reJson, STRING_MAP));
            }
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (!RESERVED_FIELDS.contains(e.getKey())
                        && e.getValue() != null && !e.getValue().isBlank()) {
                    resolvedEntities.putIfAbsent(e.getKey(), e.getValue());
                }
            }

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
                    conversationId,
                    resolvedEntities.isEmpty() ? null : resolvedEntities,
                    clientName, timePeriod, domain,
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

            // Purge any legacy per-entity top-level fields written by a pre-refactor build so
            // they cannot shadow or stale-carry against the generic resolved_entities sub-map.
            Set<String> existing = jedis.hkeys(key);
            if (existing != null) {
                for (String f : existing) {
                    if (!RESERVED_FIELDS.contains(f)) jedis.hdel(key, f);
                }
            }

            // Resolved entities → one generic JSON sub-map (no per-entity field literal).
            Map<String, String> resolvedEntities = session.resolvedEntities();
            if (resolvedEntities != null && !resolvedEntities.isEmpty()) {
                fields.put(RESOLVED_ENTITIES_FIELD, mapper.writeValueAsString(resolvedEntities));
            } else {
                jedis.hdel(key, RESOLVED_ENTITIES_FIELD);
            }

            // Always write all fields — for null values, delete the Redis field so stale
            // data from a prior turn cannot carry forward.
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
            log.debug("SessionStore.save: {} entities={} turns={}",
                    session.conversationId(), session.resolvedEntities(), session.turnCount());
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
