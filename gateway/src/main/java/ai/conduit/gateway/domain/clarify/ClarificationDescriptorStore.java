package ai.conduit.gateway.domain.clarify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;

import java.util.Optional;

/**
 * Redis-backed store for the outstanding {@link ClarificationDescriptor}, in the GATEWAY Redis
 * namespace (bounded-context isolation, CLAUDE.md §3) — the request-path pool, never the IAM instance.
 * Keyed by {@code conversationId}, one outstanding descriptor per conversation.
 *
 * <p>Lifecycle (the three Phase-1 guarantees Phase-2 resume relies on):
 * <ul>
 *   <li><b>TTL</b> — every descriptor is written with {@code SET … EX ttl}; an expired form fails to
 *       consume gracefully (empty), never a stale ground.</li>
 *   <li><b>Single-use</b> — {@link #consume} deletes on a nonce match, so a second consume of the same
 *       descriptor is rejected (empty).</li>
 *   <li><b>Latest-turn-wins</b> — {@link #invalidate} drops any outstanding descriptor; the chat path
 *       calls it at the start of every new user turn, so a newer free-text turn supersedes an
 *       outstanding form.</li>
 * </ul>
 *
 * <p>Best-effort by contract: a Redis blip loses the OOB form's resume state, degrading to the
 * plain-text clarification already streamed on the SSE — it never fails the request path.
 */
@Component
public class ClarificationDescriptorStore {

    private static final Logger log = LoggerFactory.getLogger(ClarificationDescriptorStore.class);
    private static final String KEY_PREFIX = "clarify:desc:";

    private final JedisPooled jedis;
    private final ObjectMapper mapper;
    private final boolean enabled;

    public ClarificationDescriptorStore(
            JedisPooled jedis,
            ObjectMapper mapper,
            @Value("${conduit.clarify.store.enabled:true}") boolean enabled) {
        this.jedis = jedis;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    private static String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }

    /** Store (or replace) the outstanding descriptor for its conversation, with its TTL. */
    public void store(ClarificationDescriptor descriptor) {
        if (!enabled || descriptor == null || descriptor.conversationId() == null) return;
        try {
            String json = mapper.writeValueAsString(descriptor);
            long ttl = descriptor.ttlSeconds() > 0 ? descriptor.ttlSeconds() : 900;
            jedis.setex(key(descriptor.conversationId()), ttl, json);
        } catch (Exception e) {
            log.warn("ClarificationDescriptorStore.store failed for convId={}: {}",
                    descriptor.conversationId(), e.getMessage());
        }
    }

    /** Read without consuming (Phase-2 pre-flight; loop-bound depth inheritance). Empty if absent/expired. */
    public Optional<ClarificationDescriptor> peek(String conversationId) {
        if (!enabled || conversationId == null) return Optional.empty();
        try {
            String json = jedis.get(key(conversationId));
            if (json == null) return Optional.empty();
            return Optional.of(mapper.readValue(json, ClarificationDescriptor.class));
        } catch (Exception e) {
            log.warn("ClarificationDescriptorStore.peek failed for convId={}: {}", conversationId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Single-use consume: return the descriptor only if the presented {@code nonce} matches, deleting it
     * so a second consume is rejected. A nonce mismatch does NOT burn the descriptor (a stale/forged
     * nonce cannot invalidate a live form); an absent/expired key returns empty. This is the Phase-2
     * resume entry point.
     */
    public Optional<ClarificationDescriptor> consume(String conversationId, String nonce) {
        if (!enabled || conversationId == null || nonce == null) return Optional.empty();
        try {
            String json = jedis.get(key(conversationId));
            if (json == null) return Optional.empty();
            ClarificationDescriptor d = mapper.readValue(json, ClarificationDescriptor.class);
            if (!nonce.equals(d.nonce())) return Optional.empty();  // mismatch: do not burn a live form
            jedis.del(key(conversationId));                          // single-use
            return Optional.of(d);
        } catch (Exception e) {
            log.warn("ClarificationDescriptorStore.consume failed for convId={}: {}", conversationId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Latest-turn-wins: drop any outstanding descriptor for the conversation. */
    public void invalidate(String conversationId) {
        if (!enabled || conversationId == null) return;
        try {
            jedis.del(key(conversationId));
        } catch (Exception e) {
            log.warn("ClarificationDescriptorStore.invalidate failed for convId={}: {}", conversationId, e.getMessage());
        }
    }

    /** The depth of any outstanding descriptor for the conversation, else 0 — for the loop-bound inheritance. */
    public int inheritedDepth(String conversationId) {
        return peek(conversationId).map(ClarificationDescriptor::clarifyDepth).orElse(0);
    }
}
