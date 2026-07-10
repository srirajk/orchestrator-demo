package ai.conduit.gateway.domain.auth;

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
     * Returns true if the authorization for this principal+relationship has been revoked, signalled
     * by the presence of key {@code revocation:{userId}:{relId}} in the gateway's Redis.
     *
     * <p><b>Currently inert, and architecturally wrong as designed.</b> Nothing writes this key:
     * there is no writer in iam-service or in any seed/ops script (verified 2026-07-10), and the
     * gateway and IAM sit on different logical Redis DBs. So this always returns {@code false}.
     *
     * <p>The intent — a short-lived override so a book change takes effect before a stale JWT expires
     * — is sound, but the mechanism has the gateway reading identity-domain state through a shared
     * Redis key. The gateway and IAM are separate bounded contexts that will run separate Redis
     * instances; one must not read the other's namespace. The correct shape is either a short JWT TTL
     * (drop this entirely) or a gateway-owned revocation store in the gateway's own namespace, fed by
     * an IAM event on book-change. Do not "fix" this by tuning the fail-open branch below; it is a
     * no-op guarding a no-op. Tracked as a bounded-context task.
     */
    public boolean isRevoked(String userId, String relationshipId) {
        if (userId == null || relationshipId == null) return false;
        String key = KEY_PREFIX + userId + ":" + relationshipId;
        try {
            boolean revoked = jedis.exists(key);
            if (revoked) {
                log.warn("Revocation detected: userId={} relationshipId={}", userId, relationshipId);
                Counter.builder("conduit.authz.revocations")
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
