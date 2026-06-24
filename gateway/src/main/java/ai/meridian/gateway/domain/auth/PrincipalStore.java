package ai.meridian.gateway.domain.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.Map;

/**
 * Loads (and seeds) principal attributes from Redis.
 *
 * <p>Storage layout — Redis Hash keyed by {@code principal:{userId}}:
 * <pre>
 *   id        rm_jane
 *   roles     ["relationship_manager"]
 *   book      ["REL-00042","REL-00099"]
 *   clearance 2
 * </pre>
 *
 * <p>Seeds demo principals at startup so the demo works out of the box.
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

    @PostConstruct
    void seedDemoPrincipals() {
        seed("rm_jane",   List.of("relationship_manager"), List.of("REL-00042", "REL-00099"), 2);
        seed("rm_okafor", List.of("relationship_manager"), List.of("REL-00188", "REL-00200"), 2);
        seed("admin",     List.of("admin"),                List.of(),                         5);
        log.info("PrincipalStore: demo principals seeded (rm_jane, rm_okafor, admin)");
    }

    private void seed(String userId, List<String> roles, List<String> book, int clearance) {
        String key = KEY_PREFIX + userId;
        try {
            jedis.hset(key, Map.of(
                    "id",        userId,
                    "roles",     mapper.writeValueAsString(roles),
                    "book",      mapper.writeValueAsString(book),
                    "clearance", String.valueOf(clearance)
            ));
        } catch (Exception e) {
            log.warn("PrincipalStore.seed failed for {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Load the principal for {@code userId}.
     *
     * <p>Phase 8 (M15): if a JWT-verified {@link Principal} is available in
     * {@link RequestContext} and its {@code id} matches {@code userId}, it is
     * returned immediately — the JWT issuer's attestation takes precedence over
     * the Redis record, and we avoid an unnecessary network hop.
     *
     * <p>Falls back to the Redis record, then to {@link Principal#anonymous()}
     * if the user is not found or Redis is unavailable.
     */
    public Principal load(String userId) {
        // Short-circuit: use JWT-verified principal when it matches the requested userId
        Principal jwtPrincipal = RequestContext.getPrincipal();
        if (jwtPrincipal != null && jwtPrincipal.id() != null && jwtPrincipal.id().equals(userId)) {
            log.debug("PrincipalStore: using JWT-verified principal for userId={}", userId);
            return jwtPrincipal;
        }

        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) {
            return Principal.anonymous();
        }
        try {
            Map<String, String> fields = jedis.hgetAll(KEY_PREFIX + userId);
            if (fields == null || fields.isEmpty()) {
                log.debug("PrincipalStore: no record for userId={}, using anonymous", userId);
                return Principal.anonymous();
            }
            List<String> roles = parseList(fields.get("roles"));
            List<String> book  = parseList(fields.get("book"));
            int clearance      = parseInt(fields.get("clearance"), 2);
            return new Principal(userId, roles, book, clearance, List.of());
        } catch (Exception e) {
            log.warn("PrincipalStore.load failed for {}: {}", userId, e.getMessage());
            return Principal.anonymous();
        }
    }

    /** Create or replace a principal's attributes. */
    public void upsert(String userId, List<String> roles, List<String> book, int clearance) {
        try {
            jedis.hset(KEY_PREFIX + userId, Map.of(
                    "id",        userId,
                    "roles",     mapper.writeValueAsString(roles),
                    "book",      mapper.writeValueAsString(book),
                    "clearance", String.valueOf(clearance)
            ));
            log.info("PrincipalStore.upsert: userId={} book={}", userId, book);
        } catch (Exception e) {
            log.warn("PrincipalStore.upsert failed for {}: {}", userId, e.getMessage());
        }
    }

    /** Delete a principal (e.g. offboarded RM). */
    public void delete(String userId) {
        try {
            jedis.del(KEY_PREFIX + userId);
            log.info("PrincipalStore.delete: userId={}", userId);
        } catch (Exception e) {
            log.warn("PrincipalStore.delete failed for {}: {}", userId, e.getMessage());
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
