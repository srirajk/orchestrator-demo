package ai.conduit.gateway.infrastructure.outbound;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Per-key isolation is the whole point of keying the gate by coverage base-URL: a slow coverage
 * service that exhausts ITS gate must not drain another coverage service's permits. Exhaust gate A;
 * a call on gate B still acquires and completes.
 */
class OutboundGateKeyingTest {

    @Test
    void exhaustingOneGateDoesNotStarveAnother() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboundGate gate = new OutboundGate(registry, /*permits*/ 1, /*maxWaitMs*/ 100);
        String keyA = "http://coverage-a:8086";
        String keyB = "http://coverage-b:8088";

        CountDownLatch holdA = new CountDownLatch(1);
        AtomicInteger acquiredA = new AtomicInteger();
        Thread.ofVirtual().start(() -> {
            try {
                gate.call(keyA, 60_000, () -> { acquiredA.incrementAndGet(); holdA.await(); return "a"; });
            } catch (Exception ignored) { }
        });
        await().atMost(2, TimeUnit.SECONDS).until(() -> acquiredA.get() == 1);

        // Gate A is now exhausted (its single permit is held).
        AtomicBoolean aRejected = new AtomicBoolean();
        try {
            gate.call(keyA, 60_000, () -> "should-not-run");
        } catch (OutboundGate.OutboundGateRejectedException e) {
            aRejected.set(true);
        }
        assertThat(aRejected).as("gate A must be exhausted").isTrue();

        // Gate B is untouched — it acquires and completes.
        String bResult = gate.call(keyB, 5_000, () -> "b-ok");
        assertThat(bResult).isEqualTo("b-ok");

        holdA.countDown();
    }
}
