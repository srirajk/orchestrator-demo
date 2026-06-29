package ai.meridian.gateway.domain.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.Map;

/**
 * Loads principal attributes from Redis.
 *
 * <p>Storage layout — Redis Hash keyed by {@code principal:{userId}}:
 * <pre>
 *   id           rm_jane
 *   roles        ["relationship_manager"]
 *   clearance    2
 *   segments     ["wealth","servicing"]
 *   domains      ["wealth-private-banking"]
 *   adminDomains []
 * </pre>
 *
 * <p>No {@code book} field — book-of-business is enforced by the domain coverage service at
 * runtime, never stored here. Demo principals are seeded at startup via seed-users.sh.
 */
@Service
public class PrincipalStore {

    private static final Logger log = LoggerFactory.getLogger(PrincipalStore.class);
    private static final String KEY_PREFIX = "principal:";

    private final JedisPooled jedis;
    private final ObjectMapper mapper;

    public PrincipalStore(JedisPooled jedis, ObjectMapper mapper) {
        this.jedis = jedis;
        this.mapper = mapper;
    }

    /**
     * Load the principal for {@code userId}.
     *
     * <p>Falls back to {@link Principal#anonymous()} if the user is not found or Redis
     * is unavailable.
     */
    public Principal load(String userId) {
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) {
            return Principal.anonymous();
        }
        try {
            Map<String, String> fields = jedis.hgetAll(KEY_PREFIX + userId);
            if (fields == null || fields.isEmpty()) {
                log.debug("PrincipalStore: no record for userId={}, using anonymous", userId);
                return Principal.anonymous();
            }
            List<String> roles        = parseList(fields.get("roles"));
            int          clearance    = parseInt(fields.get("clearance"), 2);
            List<String> segments     = parseList(fields.get("segments"));
            List<String> domains      = parseList(fields.get("domains"));
            List<String> adminDomains = parseList(fields.get("adminDomains"));
            return new Principal(userId, "default", roles, clearance, adminDomains, segments, domains);
        } catch (Exception e) {
            log.warn("PrincipalStore.load failed for {}: {}", userId, e.getMessage());
            return Principal.anonymous();
        }
    }

    private List<String> parseList(String json) throws Exception {
        if (json == null || json.isBlank()) return List.of();
        return mapper.readValue(json, new TypeReference<>() {});
    }

    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
