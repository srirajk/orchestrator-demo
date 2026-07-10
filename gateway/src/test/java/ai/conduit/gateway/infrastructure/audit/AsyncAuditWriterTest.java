package ai.conduit.gateway.infrastructure.audit;

import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import ai.conduit.gateway.infrastructure.telemetry.event.RequestStartData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The audit write must be entirely off the request path: {@code submit} never blocks, and the record
 * is assembled and written on a drain worker.
 */
class AsyncAuditWriterTest {

    private final AuditRecordAssembler assembler = new AuditRecordAssembler("test");

    private static List<TraceEvent> req(String id) {
        return List.of(new TraceEvent("request_start", id, null, 1L, new RequestStartData("u", "p")),
                new TraceEvent("request_complete", id, null, 2L,
                        new ai.conduit.gateway.infrastructure.telemetry.event.RequestCompleteData(1, 0, 0)));
    }

    @Test
    void submitDoesNotBlockAndTheRecordIsWrittenOnAnotherThread() {
        List<String> written = new CopyOnWriteArrayList<>();
        long callerThread = Thread.currentThread().threadId();
        AtomicInteger writeThreadWasCaller = new AtomicInteger(0);

        AuditRecordSink sink = record -> {
            if (Thread.currentThread().threadId() == callerThread) writeThreadWasCaller.incrementAndGet();
            written.add(record.transactionId());
        };
        AsyncAuditWriter writer = new AsyncAuditWriter(assembler, sink, new SimpleMeterRegistry(), 64, 2);

        long start = System.nanoTime();
        writer.submit(req("req-A"));
        long submitNanos = System.nanoTime() - start;

        assertThat(submitNanos)
                .as("submit must return immediately — it only offers to a queue")
                .isLessThan(TimeUnit.MILLISECONDS.toNanos(50));

        await().atMost(5, TimeUnit.SECONDS).until(() -> written.contains("req-A"));
        assertThat(writeThreadWasCaller.get())
                .as("the object write must never happen on the calling (request) thread")
                .isZero();
    }

    @Test
    void aSlowSinkNeverBlocksTheSubmitter() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AuditRecordSink blockingSink = record -> release.await();   // hangs until released
        AsyncAuditWriter writer = new AsyncAuditWriter(assembler, blockingSink, new SimpleMeterRegistry(), 4, 1);

        // Even with the sink wedged, submits return fast (they only enqueue / drop-oldest).
        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) writer.submit(req("req-" + i));
        long elapsed = System.nanoTime() - start;

        assertThat(elapsed)
                .as("a wedged sink must not back-pressure onto the request path")
                .isLessThan(TimeUnit.MILLISECONDS.toNanos(200));
        release.countDown();
    }

    @Test
    void anEmptyOrNullSubmitIsANoOp() {
        AtomicInteger writes = new AtomicInteger();
        AuditRecordSink sink = r -> writes.incrementAndGet();
        AsyncAuditWriter writer = new AsyncAuditWriter(assembler, sink, new SimpleMeterRegistry(), 8, 1);

        writer.submit(null);
        writer.submit(List.of());

        assertThat(writer.queueDepth()).isZero();
    }
}
