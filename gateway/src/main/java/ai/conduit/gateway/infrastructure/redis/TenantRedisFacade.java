package ai.conduit.gateway.infrastructure.redis;

import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.List;

/**
 * A tenant-qualified Redis facade for gateway application commands (Axiom Story A3).
 *
 * <p>Every command is qualified with the request's tenant segment via {@link TenantKeyspace} before
 * it reaches Redis, so application code cannot accidentally read or enumerate another tenant's keys.
 * The facade exposes <b>no</b> unqualified {@code SCAN} / {@code FT._LIST} / {@code KEYS}: its only
 * listing operation ({@link #listOwnKeys}) is bounded to this tenant's own prefix.
 *
 * <p><b>Boundary honesty.</b> This is an <em>application-level</em> guardrail, not protection from a
 * raw Redis operator. A privileged client can still {@code SCAN 0 MATCH *} across every tenant; the
 * answer to that threat is Redis ACLs or a per-tenant instance, which is out of A3's scope. The
 * facade's job is narrower and real: no gateway code path can enumerate or fetch across tenants.
 *
 * <p>Bind one per request via {@link #forTenant}. Immutable and cheap.
 */
public final class TenantRedisFacade {

    private final JedisPooled jedis;
    private final TenantKeyspace keyspace;
    private final TenantExecutionContext tenant;

    private TenantRedisFacade(JedisPooled jedis, TenantKeyspace keyspace, TenantExecutionContext tenant) {
        this.jedis = jedis;
        this.keyspace = keyspace;
        this.tenant = tenant;
    }

    /** Bind the facade to a request's tenant context. The tenant segment is fixed for this instance. */
    public static TenantRedisFacade forTenant(JedisPooled jedis, TenantKeyspace keyspace,
                                              TenantExecutionContext tenant) {
        return new TenantRedisFacade(jedis, keyspace, tenant);
    }

    /** GET a value key, qualified to this tenant. A key written under another tenant is invisible. */
    public String get(String legacyKey) {
        return jedis.get(keyspace.key(legacyKey, tenant));
    }

    /** SET a value key, qualified to this tenant. */
    public String set(String legacyKey, String value) {
        return jedis.set(keyspace.key(legacyKey, tenant), value);
    }

    /** DEL a value key, qualified to this tenant. */
    public long del(String legacyKey) {
        return jedis.del(keyspace.key(legacyKey, tenant));
    }

    /** Whether a value key exists for this tenant. */
    public boolean exists(String legacyKey) {
        return jedis.exists(keyspace.key(legacyKey, tenant));
    }

    /**
     * List this tenant's own keys matching a legacy pattern suffix (e.g. {@code "vec:*"}), returned
     * as fully-qualified key names. The {@code SCAN} is bounded to this tenant's prefix — there is no
     * way through this facade to scan another tenant's prefix or the whole keyspace, so a diagnostic
     * listing can only ever surface this tenant's keys.
     */
    public List<String> listOwnKeys(String legacyPatternSuffix) {
        String match = keyspace.keyPrefix(tenant) + legacyPatternSuffix;
        List<String> keys = new ArrayList<>();
        String cursor = ScanParams.SCAN_POINTER_START;
        ScanParams params = new ScanParams().match(match).count(500);
        do {
            ScanResult<String> result = jedis.scan(cursor, params);
            keys.addAll(result.getResult());
            cursor = result.getCursor();
        } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
        return keys;
    }

    /** The tenant segment this facade is bound to (validated/canonical, or the configured default). */
    public String tenantSegment() {
        return keyspace.segment(tenant);
    }
}
