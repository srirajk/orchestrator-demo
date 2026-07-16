package ai.conduit.gateway.infrastructure.outbound;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Per-dependency outbound concurrency gate with a true end-to-end deadline.
 *
 * <p>Two jobs, one seam, so they cannot drift apart:
 * <ol>
 *   <li><b>Bounded, keyed bulkhead.</b> Each distinct {@code key} (one coverage service base-URL,
 *       or the Cerbos PDP) gets its own {@link Semaphore}. A caller that cannot get a permit within
 *       {@code max-wait-ms} is rejected immediately ({@link OutboundGateRejectedException}) rather
 *       than parking forever — so a slow dependency drains only <em>its own</em> permits and never
 *       another's. This is the request-path analogue of {@code AgentHarness}'s per-agent bulkhead
 *       for the two outbound legs that are NOT agent calls (coverage, authz).</li>
 *   <li><b>Body-phase deadline.</b> {@code HttpRequest.timeout()} on the JDK client bounds only
 *       time-to-headers; a trickle body (e.g. 4 B/s) streams under the socket read timeout forever.
 *       So the actual exchange runs on a virtual thread and is joined with {@code future.get(deadline)};
 *       on expiry the in-flight work is {@code cancel(true)}'d (interrupting the blocking read).</li>
 * </ol>
 *
 * <p><b>Leak-proof permits.</b> The permit is released in a {@code finally} that also covers the
 * cancellation path — so a timed-out (and cancelled) call can never leak its permit. Proven by
 * {@code OutboundGatePermitLeakTest}: cancel 100 in-flight calls and the inflight gauge returns to 0.
 *
 * <p><b>Metrics</b> (tagged by {@code gate} key): {@code conduit.outbound.gate.inflight} gauge and
 * {@code conduit.outbound.gate.rejected} counter.
 *
 * <p>The gate carries no domain knowledge — keys are manifest-derived coverage base-URLs (World B).
 */
@Component
public class OutboundGate {

    private static final Logger log = LoggerFactory.getLogger(OutboundGate.class);

    private final MeterRegistry meterRegistry;
    private final int permitsPerKey;
    private final long maxWaitMs;

    /** One VT per exchange — a blocked/parked exchange costs a few KB, not a platform thread. */
    private final ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final ConcurrentHashMap<String, Semaphore> gates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> rejectedCounters = new ConcurrentHashMap<>();
    private final Set<String> registeredGauges = ConcurrentHashMap.newKeySet();

    public OutboundGate(
            MeterRegistry meterRegistry,
            @Value("${conduit.outbound.gate.permits:16}") int permitsPerKey,
            @Value("${conduit.outbound.gate.max-wait-ms:250}") long maxWaitMs) {
        this.meterRegistry = meterRegistry;
        this.permitsPerKey = permitsPerKey;
        this.maxWaitMs = maxWaitMs;
    }

    /**
     * Runs {@code work} under the gate keyed by {@code key}, bounded by {@code deadlineMs}.
     *
     * @throws OutboundGateRejectedException if no permit is available within {@code max-wait-ms}
     * @throws TimeoutException              if {@code work} does not finish within {@code deadlineMs}
     *                                       (the in-flight work is cancelled/interrupted first)
     * @throws Exception                     the checked cause thrown by {@code work}, unwrapped
     */
    public <T> T call(String key, long deadlineMs, java.util.concurrent.Callable<T> work) throws Exception {
        Semaphore gate = gates.computeIfAbsent(key, k -> new Semaphore(permitsPerKey));
        registerGauge(key, gate);

        boolean acquired;
        try {
            acquired = gate.tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw ie;
        }
        if (!acquired) {
            rejected(key).increment();
            log.warn("OutboundGate '{}' exhausted — no permit within {}ms (permits={})",
                    key, maxWaitMs, permitsPerKey);
            throw new OutboundGateRejectedException(key);
        }

        Future<T> future = vtExecutor.submit(work);
        try {
            return future.get(deadlineMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            // Cancel the in-flight exchange (interrupt the VT → abort the blocking read/body phase)
            // rather than leave it running against a caller that has already given up.
            future.cancel(true);
            throw te;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw ee;
        } finally {
            // Covers BOTH the normal return and the cancellation path — the cancelled call
            // releases its permit here, so a timed-out call can never leak a permit.
            gate.release();
        }
    }

    private Counter rejected(String key) {
        return rejectedCounters.computeIfAbsent(key, k ->
                Counter.builder("conduit.outbound.gate.rejected")
                        .description("Outbound calls rejected because the per-dependency gate was exhausted")
                        .tag("gate", k)
                        .register(meterRegistry));
    }

    private void registerGauge(String key, Semaphore gate) {
        if (registeredGauges.add(key)) {
            Gauge.builder("conduit.outbound.gate.inflight", gate,
                            s -> (double) (permitsPerKey - s.availablePermits()))
                    .description("In-flight outbound calls holding a permit on this gate")
                    .tag("gate", key)
                    .register(meterRegistry);
        }
    }

    /** Thrown when a per-dependency gate has no permit available within its bounded wait. */
    public static class OutboundGateRejectedException extends RuntimeException {
        public OutboundGateRejectedException(String key) {
            super("Outbound gate '" + key + "' exhausted — no permit available");
        }
    }
}
