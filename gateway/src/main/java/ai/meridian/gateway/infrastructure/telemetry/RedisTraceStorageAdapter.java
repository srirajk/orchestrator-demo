package ai.meridian.gateway.infrastructure.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Redis-backed trace storage.
 *
 * <p>Schema:
 * <ul>
 *   <li>{@code trace:{requestId}} — a Redis List (RPUSH). Each element is a JSON-serialized
 *       {@link TraceEvent}. TTL: 24 hours.</li>
 *   <li>{@code conv_traces:{conversationId}} — a Redis Sorted Set (ZADD). Score = event timestamp,
 *       member = requestId. Allows newest-first retrieval via ZREVRANGEBYSCORE.
 *       TTL: 24 hours (refreshed on each write to the set).</li>
 * </ul>
 */
@Component
public class RedisTraceStorageAdapter implements TraceStorageAdapter {

    private static final Logger log = LoggerFactory.getLogger(RedisTraceStorageAdapter.class);

    private final JedisPooled jedis;
    private final ObjectMapper mapper;
    private final long ttlSeconds;

    public RedisTraceStorageAdapter(
            JedisPooled jedis,
            ObjectMapper mapper,
            @Value("${meridian.telemetry.trace-ttl-seconds:86400}") long ttlSeconds) {
        this.jedis      = jedis;
        this.mapper     = mapper;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public void save(TraceEvent event) {
        try {
            String json   = mapper.writeValueAsString(event);
            String listKey = "trace:" + event.requestId();

            jedis.rpush(listKey, json);
            jedis.expire(listKey, ttlSeconds);

            // Also index under conversationId when present
            String convId = event.conversationId();
            if (convId != null && !convId.isBlank()) {
                String setKey = "conv_traces:" + convId;
                jedis.zadd(setKey, (double) event.timestamp(), event.requestId());
                jedis.expire(setKey, ttlSeconds);
            }
        } catch (Exception e) {
            log.warn("TraceStorageAdapter: failed to persist event type={} requestId={}: {}",
                    event.type(), event.requestId(), e.getMessage());
        }
    }

    @Override
    public List<TraceEvent> getByRequestId(String requestId) {
        try {
            List<String> items = jedis.lrange("trace:" + requestId, 0, -1);
            return items.stream()
                    .map(json -> {
                        try {
                            return mapper.readValue(json, TraceEvent.class);
                        } catch (Exception e) {
                            log.debug("Could not deserialize trace event: {}", e.getMessage());
                            return null;
                        }
                    })
                    .filter(e -> e != null)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("TraceStorageAdapter: getByRequestId({}) failed: {}", requestId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public List<String> getRequestIdsByConversation(String conversationId, int limit) {
        try {
            // ZREVRANGE returns members ordered by descending score (newest timestamp first)
            return jedis.zrevrange("conv_traces:" + conversationId, 0, limit - 1)
                    .stream().collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("TraceStorageAdapter: getRequestIdsByConversation({}) failed: {}", conversationId, e.getMessage());
            return Collections.emptyList();
        }
    }
}
