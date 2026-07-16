package ai.conduit.gateway.infrastructure.audit;

import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * F1 §b (AC3) — a deny-verdict audit record survives queue pressure: it is never the victim of the
 * drop-oldest eviction, while droppable (non-deny) records are shed first. Full per-invocation WORM
 * durability remains an OPEN PREREQUISITE on the audit-outbox story; this proves only the must-keep
 * preference under pressure.
 */
class AuditDenyMustKeepTest {

    @Test
    void denyRecordsSurviveQueuePressureWhileDroppablesAreShed() throws Exception {
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        List<String> written = new CopyOnWriteArrayList<>();

        // The single drain worker parks in the sink until the gate opens, so the queue fills under our
        // control. capacity=2, workers=1.
        AuditRecordSink blockingSink = record -> {
            workerEntered.countDown();
            gate.await();
            written.add(record.transactionId());
        };
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AsyncAuditWriter writer = new AsyncAuditWriter(
                new AuditRecordAssembler("test"), blockingSink, meters, 2, 1);
        try {
            writer.submit(events("D1", false));                 // worker grabs D1, parks in sink
            assertThat(workerEntered.await(3, TimeUnit.SECONDS)).isTrue();

            writer.submit(events("D2", false));                 // queue: [D2]
            writer.submit(events("D3", false));                 // queue: [D2, D3] (full)
            writer.submit(events("K1", true));                  // evict D2 -> [D3, K1]
            writer.submit(events("K2", true));                  // evict D3 -> [K1, K2]
            writer.submit(events("D4", false));                 // full, all must-keep -> D4 dropped

            gate.countDown();                                   // release: worker writes D1, K1, K2

            await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> written.containsAll(List.of("D1", "K1", "K2")));

            assertThat(written).contains("D1", "K1", "K2");
            assertThat(written).doesNotContain("D2", "D3", "D4");
            assertThat(meters.get("conduit.audit.dropped").counter().count()).isEqualTo(3.0);
        } finally {
            writer.shutdown();
        }
    }

    private static List<TraceEvent> events(String requestId, boolean deny) {
        if (deny) {
            return List.of(
                    TraceEvent.of("agent_invocation", requestId, null),
                    TraceEvent.of("check_denied", requestId, null));   // marks the record must-keep
        }
        return List.of(TraceEvent.of("agent_invocation", requestId, null));
    }
}
