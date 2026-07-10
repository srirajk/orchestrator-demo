package ai.conduit.gateway.infrastructure.telemetry;

import ai.conduit.gateway.infrastructure.audit.AsyncAuditWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trace persistence must be batched, off the request path, and bounded.
 *
 * <p>It used to run synchronously inside {@code publish()}, ~45 times per request, each doing
 * {@code rpush+expire} (+{@code zadd+expire} on the first) — ~92 Redis round-trips per request, through
 * the same pool the routing KNN search depends on, to serve a panel that never read it (the live
 * glass-box streams from the in-memory fan-out).
 */
class AsyncTraceWriteTest {

    /** Records batches instead of talking to Redis. */
    private static final class RecordingStorage implements TraceStorageAdapter {
        final List<List<TraceEvent>> batches = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger singleSaves = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);

        @Override public void save(TraceEvent event) { singleSaves.incrementAndGet(); }
        @Override public void saveAll(List<TraceEvent> events) { batches.add(events); latch.countDown(); }
        @Override public List<TraceEvent> getByRequestId(String requestId) { return List.of(); }
        @Override public List<String> getRequestIdsByConversation(String c, int l) { return List.of(); }
    }

    /** Blocks in saveAll so the queue can be driven to overflow deterministically. */
    private static final class BlockingStorage implements TraceStorageAdapter {
        final CountDownLatch release = new CountDownLatch(1);
        @Override public void save(TraceEvent event) { }
        @Override public void saveAll(List<TraceEvent> events) {
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        @Override public List<TraceEvent> getByRequestId(String r) { return List.of(); }
        @Override public List<String> getRequestIdsByConversation(String c, int l) { return List.of(); }
    }

    private static TraceEvent event(String requestId, String type) {
        return TraceEvent.of(type, requestId, "conv-1", null);
    }

    private TraceEventPublisher publisher(AsyncTraceWriter writer) {
        return new TraceEventPublisher(new ObjectMapper(), writer, noAudit(), new SimpleMeterRegistry(), 512, 1000);
    }

    @Test
    void aRequestIsFlushedOnce_asOneBatch_inOrder_andNotPerEvent() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        AsyncTraceWriter writer = new AsyncTraceWriter(storage, new SimpleMeterRegistry(), 64, 1);
        TraceEventPublisher pub = publisher(writer);

        pub.publish(event("req-1", "request_start"));
        pub.publish(event("req-1", "intent_classified"));
        pub.publish(event("req-1", "agent_complete"));

        // Nothing persisted yet: the request has not completed.
        assertThat(storage.batches).as("no write before the request terminates").isEmpty();

        pub.publish(event("req-1", "request_complete"));

        assertThat(storage.latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(storage.batches).hasSize(1);
        assertThat(storage.batches.get(0)).hasSize(4);
        assertThat(storage.batches.get(0).stream().map(TraceEvent::type))
                .containsExactly("request_start", "intent_classified", "agent_complete", "request_complete");

        // The per-event path must not be used at all — that was the ~92-round-trip bug.
        assertThat(storage.singleSaves).hasValue(0);
        writer.shutdown();
    }

    @Test
    void overflowDropsOldest_andCountsIt_ratherThanGrowingUnbounded() throws Exception {
        BlockingStorage storage = new BlockingStorage();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AsyncTraceWriter writer = new AsyncTraceWriter(storage, meters, 2, 1);   // capacity 2

        // 1 batch is taken by the (blocked) worker, 2 fill the queue, the rest must be dropped.
        for (int i = 0; i < 12; i++) {
            writer.submit(List.of(event("req-" + i, "request_complete")));
        }

        double dropped = 0;
        for (int i = 0; i < 60 && dropped == 0; i++) {
            dropped = meters.get("conduit.trace.dropped").counter().count();
            if (dropped == 0) Thread.sleep(50);
        }
        assertThat(dropped)
                .as("a full queue must drop and COUNT, never grow — a silent drop is a lie")
                .isGreaterThan(0.0);

        assertThat(writer.queueDepth())
                .as("queue stays bounded at its configured capacity")
                .isLessThanOrEqualTo(2);

        storage.release.countDown();
        writer.shutdown();
    }

    @Test
    void anOrphanedBufferIsFlushed_soTheBufferTableStaysBounded() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        AsyncTraceWriter writer = new AsyncTraceWriter(storage, new SimpleMeterRegistry(), 64, 1);
        // maxOpenRequests = 1: the second in-flight request must evict and flush the first.
        TraceEventPublisher pub =
                new TraceEventPublisher(new ObjectMapper(), writer, noAudit(), new SimpleMeterRegistry(), 512, 1);

        pub.publish(event("never-completes", "request_start"));   // no request_complete
        pub.publish(event("req-2", "request_start"));             // forces eviction of the orphan

        assertThat(storage.latch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(storage.batches.get(0).get(0).requestId()).isEqualTo("never-completes");
        writer.shutdown();
    }

    /** An ObjectProvider that yields no audit writer — audit is off in these trace tests. */
    private static ObjectProvider<AsyncAuditWriter> noAudit() {
        return new ObjectProvider<>() {
            @Override public AsyncAuditWriter getObject() { return null; }
            @Override public AsyncAuditWriter getObject(Object... args) { return null; }
            @Override public AsyncAuditWriter getIfAvailable() { return null; }
            @Override public AsyncAuditWriter getIfUnique() { return null; }
        };
    }
}
