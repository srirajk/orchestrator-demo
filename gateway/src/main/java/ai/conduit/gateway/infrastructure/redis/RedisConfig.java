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
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

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
                DefaultJedisClientConfig.builder().build(),
                poolConfig);
    }
}
