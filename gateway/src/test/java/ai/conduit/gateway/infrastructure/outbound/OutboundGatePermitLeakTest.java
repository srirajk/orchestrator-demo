package ai.conduit.gateway.infrastructure.outbound;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The permit is released in a {@code finally} that also covers the CANCELLATION path. So when 100
 * in-flight calls all blow their deadline (and are {@code cancel(true)}'d), every permit comes back:
 * the {@code conduit.outbound.gate.inflight} gauge returns to 0. A gate that released permits only on
 * the normal return would leak here and the gauge would stay pinned at 100 — a slow-permanent stall.
 */
class OutboundGatePermitLeakTest {

    @Test
    void cancelledInFlightCallsReleaseTheirPermits() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboundGate gate = new OutboundGate(registry, /*permits*/ 100, /*maxWaitMs*/ 250);
        String key = "http://slow-dep:8086";

        CountDownLatch neverFires = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        for (int i = 0; i < 100; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    // deadline 150ms; work blocks forever → every call times out and is cancelled.
                    gate.call(key, 150, () -> {
                        started.incrementAndGet();
                        neverFires.await();
                        return "unreachable";
                    });
                } catch (Exception expectedTimeout) { }
            });
        }

        // All 100 acquired a permit and are in-flight at some point.
        await().atMost(3, TimeUnit.SECONDS).until(() -> started.get() == 100);

        // After the deadline elapses and each call is cancelled, every permit is released.
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(inflight(registry, key)).isZero());

        neverFires.countDown();
    }

    private static double inflight(SimpleMeterRegistry registry, String key) {
        return registry.get("conduit.outbound.gate.inflight").tag("gate", key).gauge().value();
    }
}
