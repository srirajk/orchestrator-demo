package ai.conduit.gateway.infrastructure.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.time.Duration;

/**
 * Redis connection pools.
 *
 * <p>Two pools on purpose. {@code JedisPooled(host, port)} uses Jedis' internal defaults —
 * {@code maxTotal=8} and {@code maxWait=-1}, i.e. <b>eight connections, and a starved caller waits
 * forever</b>. Everything shared them: the routing KNN search (`VectorIndex.ftSearch`), the revocation
 * check, and the trace writes. A burst of trace writes could therefore starve the very query that
 * answers the user, and a starved caller had no deadline to hit.
 *
 * <p>So: telemetry gets its own pool and can no longer crowd out the request path, and both pools get
 * a finite {@code maxWait} — a caller that cannot get a connection fails fast instead of parking
 * indefinitely. Sizes and timeouts are configuration, never constants.
 *
 * <p><b>Tenant-aware key-prefix seam (Axiom A3).</b> The pools are a single host/port with no
 * namespacing of their own. Per-tenant key/index naming is the job of {@link TenantKeyspace} (the
 * seam), and per-tenant application commands go through {@link TenantRedisFacade}, produced here by
 * {@link #tenantRedisFacadeFactory}. Both resolve to the legacy names for the default/single-tenant
 * case, so the demo (which reads the registry-written {@code intent_idx}) is unaffected until
 * per-tenant ingestion (A4) exists.
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    // Explicit socket-level timeouts (F3): DefaultJedisClientConfig's defaults are already 2000ms,
    // but an implicit default is not a discipline. Pinned via config so a Redis that accepts the
    // connection then stalls the read cannot park a borrowed pool connection — and, with the finite
    // maxWait below, cannot then starve every other caller of that pool.
    @Value("${conduit.redis.connect-timeout-ms:2000}")
    private int connectTimeoutMs;

    @Value("${conduit.redis.socket-timeout-ms:2000}")
    private int socketTimeoutMs;

    /** Request-path pool: routing vector search, revocation, registry. */
    @Bean
    @Primary
    public JedisPooled jedisPooled(
            @Value("${conduit.redis.pool.max-total:32}") int maxTotal,
            @Value("${conduit.redis.pool.max-idle:8}") int maxIdle,
            @Value("${conduit.redis.pool.max-wait-ms:250}") long maxWaitMs) {
        return build(maxTotal, maxIdle, maxWaitMs);
    }

    /**
     * Telemetry pool: the async trace flush. Isolated so a trace-write burst cannot exhaust the
     * connections the request path needs. Deliberately small — trace writes are batched into one
     * pipelined round-trip per request, so they need very few connections.
     */
    @Bean("telemetryJedisPooled")
    public JedisPooled telemetryJedisPooled(
            @Value("${conduit.redis.telemetry-pool.max-total:8}") int maxTotal,
            @Value("${conduit.redis.telemetry-pool.max-idle:2}") int maxIdle,
            @Value("${conduit.redis.telemetry-pool.max-wait-ms:250}") long maxWaitMs) {
        return build(maxTotal, maxIdle, maxWaitMs);
    }

    /**
     * Factory for the per-request tenant-qualified Redis facade. A request binds it to its
     * {@link ai.conduit.gateway.domain.auth.TenantExecutionContext} via
     * {@link TenantRedisFacade#forTenant}; every command it issues is then scoped to that tenant,
     * with no unqualified {@code SCAN}/{@code FT._LIST} exposed. Kept as a factory (not a request
     * bean) so the seam is usable off the servlet thread, where the context is passed explicitly.
     */
    @Bean
    public TenantRedisFacadeFactory tenantRedisFacadeFactory(JedisPooled jedisPooled, TenantKeyspace keyspace) {
        return tenant -> TenantRedisFacade.forTenant(jedisPooled, keyspace, tenant);
    }

    /** Binds the request-path pool + keyspace seam so callers need only supply the tenant context. */
    @FunctionalInterface
    public interface TenantRedisFacadeFactory {
        TenantRedisFacade forTenant(ai.conduit.gateway.domain.auth.TenantExecutionContext tenant);
    }

    private JedisPooled build(int maxTotal, int maxIdle, long maxWaitMs) {
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(maxTotal);
        poolConfig.setMaxIdle(maxIdle);
        poolConfig.setMinIdle(0);
        // Finite. -1 (the Jedis default) means a starved caller blocks forever.
        poolConfig.setMaxWait(Duration.ofMillis(maxWaitMs));
        poolConfig.setTestOnBorrow(false);

        return new JedisPooled(
                new HostAndPort(host, port),
                DefaultJedisClientConfig.builder()
                        .connectionTimeoutMillis(connectTimeoutMs)
                        .socketTimeoutMillis(socketTimeoutMs)
                        .build(),
                poolConfig);
    }
}
