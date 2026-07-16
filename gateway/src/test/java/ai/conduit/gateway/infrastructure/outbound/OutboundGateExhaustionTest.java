package ai.conduit.gateway.infrastructure.outbound;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * When a gate's permits are all held, a further caller is rejected FAST (within max-wait-ms) rather
 * than parking — the fail-closed backpressure that stops a slow dependency turning into a livelock —
 * and the rejection is counted in {@code conduit.outbound.gate.rejected}.
 */
class OutboundGateExhaustionTest {

    @Test
    void exhaustedGateRejectsFastAndCountsIt() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboundGate gate = new OutboundGate(registry, /*permits*/ 2, /*maxWaitMs*/ 100);
        String key = "http://dep-a:8086";

        CountDownLatch hold = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();
        // Occupy both permits with work that blocks on `hold`.
        for (int i = 0; i < 2; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    gate.call(key, 60_000, () -> {
                        acquired.incrementAndGet();
                        hold.await();
                        return "done";
                    });
                } catch (Exception ignored) { }
            });
        }
        await().atMost(2, TimeUnit.SECONDS).until(() -> acquired.get() == 2);

        long t0 = System.nanoTime();
        assertThatThrownBy(() -> gate.call(key, 60_000, () -> "should-not-run"))
                .isInstanceOf(OutboundGate.OutboundGateRejectedException.class);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        // Rejected within roughly the bounded wait — never parked for the deadline.
        assertThat(elapsedMs).as("rejection must be fast (bounded by max-wait-ms)").isLessThan(2_000);
        assertThat(registry.get("conduit.outbound.gate.rejected").tag("gate", key).counter().count())
                .isEqualTo(1.0);

        hold.countDown();
    }
}
