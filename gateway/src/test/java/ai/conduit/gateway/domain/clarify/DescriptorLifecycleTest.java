package ai.conduit.gateway.domain.clarify;

import ai.conduit.gateway.domain.coverage.CoverageResource;
import ai.conduit.gateway.testsupport.RedisContainerTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * The descriptor's Redis lifecycle (the three Phase-1 guarantees Phase-2 resume relies on): single-use
 * consume, graceful TTL expiry, and latest-turn-wins invalidation. Runs against the shared throwaway
 * Redis Stack container (never a live demo Redis).
 */
class DescriptorLifecycleTest extends RedisContainerTest {

    private static JedisPooled jedis;
    private static ClarificationDescriptorStore store;
    private static ClarificationDescriptorFactory factory;

    @BeforeAll
    static void setup() {
        jedis = new JedisPooled(redisHost(), redisPort());
        store = new ClarificationDescriptorStore(jedis, new ObjectMapper(), true);
        factory = new ClarificationDescriptorFactory(4, 2, 900, "None of these — reply with it directly.");
    }

    private ClarificationDescriptor make(String convId) {
        return factory.forEntity(convId, "Which one?", "Which one?", "client", "REL-\\d+",
                List.of(new CoverageResource("REL-1", "Alpha Trust", "sd")), List.of(), "q", 1);
    }

    @Test
    void singleUse_secondConsumeIsRejected() {
        ClarificationDescriptor d = make("conv-single");
        store.store(d);

        Optional<ClarificationDescriptor> first = store.consume("conv-single", d.nonce());
        assertThat(first).isPresent();
        assertThat(first.get().nonce()).isEqualTo(d.nonce());

        // Second consume of the same descriptor → rejected (already deleted).
        assertThat(store.consume("conv-single", d.nonce())).isEmpty();
    }

    @Test
    void nonceMismatchDoesNotConsumeOrBurnTheLiveForm() {
        ClarificationDescriptor d = make("conv-nonce");
        store.store(d);

        assertThat(store.consume("conv-nonce", "wrong-nonce")).isEmpty();  // rejected
        // ...and the live form survives a forged/stale nonce — the correct nonce still consumes it.
        assertThat(store.consume("conv-nonce", d.nonce())).isPresent();
    }

    @Test
    void ttlExpiry_isGraceful() {
        ClarificationDescriptor base = make("conv-ttl");
        // Rebuild with a 1-second TTL for the test.
        ClarificationDescriptor shortTtl = new ClarificationDescriptor(
                base.nonce(), base.conversationId(), base.kind(), base.entityNoun(), base.question(),
                base.plainText(), base.offeredCandidates(), base.freeTextEnabled(), base.freeTextPrompt(),
                base.idPattern(), base.originatingQuery(), base.clarifyDepth(), base.maxClarifyDepth(),
                base.createdAtEpochMs(), 1L, base.singleUse(), base.blocking());
        store.store(shortTtl);
        assertThat(store.peek("conv-ttl")).isPresent();

        // After the TTL, the key is gone and peek/consume degrade gracefully (empty), never a stale ground.
        await().atMost(4, SECONDS).until(() -> store.peek("conv-ttl").isEmpty());
        assertThat(store.consume("conv-ttl", shortTtl.nonce())).isEmpty();
    }

    @Test
    void newerFreeTextTurnInvalidatesOutstandingDescriptor() {
        ClarificationDescriptor d = make("conv-invalidate");
        store.store(d);
        assertThat(store.peek("conv-invalidate")).isPresent();

        // Latest-turn-wins: a newer free-text turn supersedes the outstanding form.
        store.invalidate("conv-invalidate");
        assertThat(store.peek("conv-invalidate")).isEmpty();
        assertThat(store.consume("conv-invalidate", d.nonce())).isEmpty();
    }

    @Test
    void inheritedDepthReflectsOutstandingDescriptor() {
        assertThat(store.inheritedDepth("conv-none")).isZero();
        ClarificationDescriptor d = make("conv-depth");   // depth 1
        store.store(d);
        assertThat(store.inheritedDepth("conv-depth")).isEqualTo(1);
    }
}
