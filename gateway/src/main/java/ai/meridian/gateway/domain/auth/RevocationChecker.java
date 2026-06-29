package ai.meridian.gateway.domain.auth;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;

@Service
public class RevocationChecker {

    private static final Logger log = LoggerFactory.getLogger(RevocationChecker.class);
    private static final String KEY_PREFIX = "revocation:";

    private final JedisPooled jedis;
    private final MeterRegistry meterRegistry;

    public RevocationChecker(JedisPooled jedis, MeterRegistry meterRegistry) {
        this.jedis = jedis;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Returns true if the authorization for this principal+relationship has been revoked.
     * Revocation is signalled by presence of key "revocation:{userId}:{relId}" in Redis.
     * Key is set by iam-service after a book change; TTL is 60s (enough for JWT expiry).
     */
    public boolean isRevoked(String userId, String relationshipId) {
        if (userId == null || relationshipId == null) return false;
        String key = KEY_PREFIX + userId + ":" + relationshipId;
        try {
            boolean revoked = jedis.exists(key);
            if (revoked) {
                log.warn("Revocation detected: userId={} relationshipId={}", userId, relationshipId);
                Counter.builder("meridian.authz.revocations")
                    .description("Revocations detected from revocation overlay")
                    .tag("entity_id", relationshipId)
                    .register(meterRegistry)
                    .increment();
            }
            return revoked;
        } catch (Exception e) {
            log.error("RevocationChecker Redis error for userId={} relId={}: {} — failing open",
                userId, relationshipId, e.getMessage());
            return false;
        }
    }
}
