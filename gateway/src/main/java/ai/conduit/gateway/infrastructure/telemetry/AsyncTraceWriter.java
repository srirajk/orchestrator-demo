package ai.conduit.gateway.infrastructure.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Flushes a request's trace events to storage <em>off the request thread</em>.
 *
 * <p><b>Nothing that does not produce the answer belongs on the request path.</b> Trace persistence
 * previously ran synchronously inside {@link TraceEventPublisher#publish}, ~45 times per request,
 * each doing {@code rpush+expire} (+{@code zadd+expire} on the first) — roughly 92 Redis round-trips
 * per request, through the same connection pool the routing KNN search depends on. It served nothing
 * the user was looking at: the live glass-box panel reads the in-memory SSE fan-out, not Redis.
 *
 * <p>Two properties matter more than "asynchronous":
 * <ol>
 *   <li><b>Batched.</b> A request's events are handed over once, and {@link TraceStorageAdapter#saveAll}
 *       collapses them into a single round-trip. Moving 92 round-trips onto another thread would not
 *       have fixed anything — it would only have moved the cost.</li>
 *   <li><b>Bounded.</b> The queue has a fixed capacity and drops the oldest batch under sustained
 *       overload, incrementing {@code conduit.trace.dropped}. An unbounded queue (the default for a
 *       Spring {@code @Async} executor) is a slower way to fall over. A silent drop is a lie, so the
 *       drop is counted and exported.</li>
 * </ol>
 *
 * <p>Consequence, accepted deliberately: if the process dies with batches still queued, those traces
 * are lost. That is fine for the panel and for Insights. It is <em>not</em> fine for audit — a T7 audit
 * store is a different {@link TraceStorageAdapter} with strict, durable, fail-closed semantics.
 */
@Component
public class AsyncTraceWriter {

    private static final Logger log = LoggerFactory.getLogger(AsyncTraceWriter.class);

    private final TraceStorageAdapter storage;
    private final BlockingQueue<List<TraceEvent>> queue;
    private final ExecutorService workers;
    private final Counter droppedEvents;
    private final Counter flushedEvents;
    private final int workerCount;

    private volatile boolean running = true;

    public AsyncTraceWriter(
            TraceStorageAdapter storage,
            MeterRegistry meterRegistry,
            @Value("${conduit.telemetry.async.queue-capacity:2048}") int queueCapacity,
            @Value("${conduit.telemetry.async.workers:2}") int workerCount) {

        this.storage     = storage;
        this.workerCount = Math.max(1, workerCount);
        this.queue       = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));

        this.droppedEvents = Counter.builder("conduit.trace.dropped")
                .description("Trace events discarded because the async write queue was full")
                .register(meterRegistry);
        this.flushedEvents = Counter.builder("conduit.trace.flushed")
                .description("Trace events successfully persisted off the request path")
                .register(meterRegistry);
        Gauge.builder("conduit.trace.queue.depth", queue, BlockingQueue::size)
                .description("Batches waiting to be persisted")
                .register(meterRegistry);

        // Virtual threads: a drain worker spends its life blocked on take() and on Redis I/O, so it
        // parks and costs no carrier.
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < this.workerCount; i++) {
            this.workers.submit(this::drain);
        }
        log.info("AsyncTraceWriter started: {} worker(s), queue capacity {}", this.workerCount, queueCapacity);
    }

    /**
     * Hand a request's events over for persistence. Never blocks, never throws.
     *
     * <p>On overflow the OLDEST batch is discarded, not the newest: under sustained overload the most
     * recent traces are the ones an operator wants.
     */
    public void submit(List<TraceEvent> batch) {
        if (batch == null || batch.isEmpty()) return;
        if (queue.offer(batch)) return;

        List<TraceEvent> evicted = queue.poll();     // drop-oldest
        if (evicted != null) {
            droppedEvents.increment(evicted.size());
        }
        if (!queue.offer(batch)) {
            droppedEvents.increment(batch.size());   // still full — drop the new one too
        }
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            try {
                List<TraceEvent> batch = queue.poll(200, TimeUnit.MILLISECONDS);
                if (batch == null) continue;
                storage.saveAll(batch);              // saveAll never throws by contract
                flushedEvents.increment(batch.size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("AsyncTraceWriter: flush failed: {}", e.getMessage());
            }
        }
    }

    /** Drain what is queued on shutdown, but never hang the JVM waiting for it. */
    @PreDestroy
    public void shutdown() {
        running = false;
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                List<List<TraceEvent>> remaining = new ArrayList<>();
                queue.drainTo(remaining);
                int lost = remaining.stream().mapToInt(List::size).sum();
                if (lost > 0) {
                    droppedEvents.increment(lost);
                    log.warn("AsyncTraceWriter: {} trace event(s) unflushed at shutdown", lost);
                }
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }

    /** Visible for tests. */
    int queueDepth() {
        return queue.size();
    }
}
