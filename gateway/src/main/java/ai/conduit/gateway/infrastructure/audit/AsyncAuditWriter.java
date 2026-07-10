package ai.conduit.gateway.infrastructure.audit;

import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Persists the audit record for a request <em>off the request path</em> (Invariant 0 of the audit
 * spec). The request thread's only cost is a non-blocking {@code offer}; assembling the record
 * (hashing, JSON) and the object-store write both happen on a drain worker.
 *
 * <p>Mirrors {@link ai.conduit.gateway.infrastructure.telemetry.AsyncTraceWriter}, with one
 * difference in posture: a dropped trace is acceptable, a dropped <em>audit</em> record is not — so
 * a drop here is logged loudly and metered ({@code conduit.audit.dropped}) as an incident, not a
 * routine event. If guaranteed capture is later required, back the queue with a durable spool before
 * the sink; the async boundary and the metrics stay the same.
 *
 * <p>Present only when {@code conduit.audit.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "conduit.audit.enabled", havingValue = "true")
public class AsyncAuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AsyncAuditWriter.class);

    /** occurredAt is stamped at request completion, not at drain time, so the record is stable. */
    private record Job(List<TraceEvent> events, Instant occurredAt) {}

    private final AuditRecordAssembler assembler;
    private final AuditRecordSink sink;
    private final BlockingQueue<Job> queue;
    private final ExecutorService workers;
    private final Counter dropped;
    private final Counter written;
    private final Counter failed;
    private final int workerCount;

    private volatile boolean running = true;

    public AsyncAuditWriter(
            AuditRecordAssembler assembler,
            AuditRecordSink sink,
            MeterRegistry meterRegistry,
            @Value("${conduit.audit.async.queue-capacity:2048}") int queueCapacity,
            @Value("${conduit.audit.async.workers:2}") int workerCount) {
        this.assembler   = assembler;
        this.sink        = sink;
        this.workerCount = Math.max(1, workerCount);
        this.queue       = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));

        this.dropped = Counter.builder("conduit.audit.dropped")
                .description("Audit records discarded because the write queue was full — a monitored incident")
                .register(meterRegistry);
        this.written = Counter.builder("conduit.audit.written")
                .description("Audit records durably written to the object store")
                .register(meterRegistry);
        this.failed = Counter.builder("conduit.audit.write.failed")
                .description("Audit record writes that threw — a monitored incident")
                .register(meterRegistry);
        Gauge.builder("conduit.audit.queue.depth", queue, BlockingQueue::size)
                .description("Audit records waiting to be written")
                .register(meterRegistry);

        this.workers = Executors.newVirtualThreadPerTaskExecutor();
        for (int i = 0; i < this.workerCount; i++) {
            this.workers.submit(this::drain);
        }
        log.info("AsyncAuditWriter started: {} worker(s), queue capacity {}", this.workerCount, queueCapacity);
    }

    /** Hand a completed request's events over for audit persistence. Never blocks, never throws. */
    public void submit(List<TraceEvent> events) {
        if (events == null || events.isEmpty()) return;
        Job job = new Job(events, Instant.now());
        if (queue.offer(job)) return;

        Job evicted = queue.poll();      // drop-oldest
        if (evicted != null) {
            dropped.increment();
            log.warn("AsyncAuditWriter: queue full — dropped an audit record (txn={})",
                    firstRequestId(evicted.events()));
        }
        if (!queue.offer(job)) {
            dropped.increment();
            log.warn("AsyncAuditWriter: queue still full — dropped an audit record (txn={})",
                    firstRequestId(events));
        }
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            try {
                Job job = queue.poll(200, TimeUnit.MILLISECONDS);
                if (job == null) continue;
                AuditRecord record = assembler.assemble(job.events(), job.occurredAt());
                sink.write(record);
                written.increment();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                failed.increment();
                log.error("AsyncAuditWriter: audit write failed: {}", e.getMessage(), e);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                int lost = queue.size();
                if (lost > 0) {
                    dropped.increment(lost);
                    log.warn("AsyncAuditWriter: {} audit record(s) unwritten at shutdown", lost);
                }
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }

    int queueDepth() {
        return queue.size();
    }

    private static String firstRequestId(List<TraceEvent> events) {
        return events.isEmpty() ? "?" : events.get(0).requestId();
    }
}
