package ai.conduit.gateway.orchestration.harness;

import ai.conduit.gateway.adapter.ProtocolAdapter;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
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
 * Wraps each agent adapter call in a resilience harness:
 * bulkhead (dual-semaphore) → circuit breaker → adapter.invoke() → SLA timeout.
 *
 * <p>Bulkhead: per-agent pair of {@link Semaphore}s — one bounds concurrent execution,
 * one bounds the queue. Virtual threads park cheaply on the executing semaphore;
 * the queue semaphore gives instant rejection when the backlog is full.
 *
 * <p>Circuit breaker: R4j {@link CircuitBreaker} keyed by agentId. After enough
 * failures it opens and subsequent calls return {@link NodeResult.Status#BREAKER_OPEN}
 * instantly, without touching the network.
 *
 * <p>Contract: {@link #execute} NEVER throws — every outcome is encoded in
 * the returned {@link NodeResult}.
 */
@Service
public class AgentHarness {

    private static final Logger log = LoggerFactory.getLogger(AgentHarness.class);

    private final List<ProtocolAdapter> adapters;
    private final CircuitBreakerRegistry cbRegistry;
    private final MeterRegistry meterRegistry;

    // ── Gauge registration guard: track which agentIds already have gauges ────
    private final Set<String> registeredGauges = ConcurrentHashMap.newKeySet();

    // ── Per-agent bulkhead: dual-semaphore + virtual-thread executor ──────────
    // One virtual thread per agent call. Semaphores control concurrency and queue depth
    // without a platform thread pool — acquire() on a virtual thread merely parks it
    // (unmounts from its carrier), so blocked callers cost ~few KB each, not ~1MB.
    private final ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, Semaphore> executingSlots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> queueSlots    = new ConcurrentHashMap<>();

    // ── Harness config (all tunable via application.yml / env vars) ──────────
    private final int defaultSlaMs;
    private final int bulkheadMaxConcurrent;
    private final int bulkheadQueueCapacity;
    private final int cbFailureRate;
    private final int cbSlowCallRate;
    private final int cbSlowCallDurationSeconds;
    private final int cbOpenWaitSeconds;
    private final int cbSlidingWindowSize;
    private final int cbMinCalls;
    private final int cbHalfOpenPermittedCalls;

    public AgentHarness(
            List<ProtocolAdapter> adapters,
            CircuitBreakerRegistry cbRegistry,
            MeterRegistry meterRegistry,
            @Value("${conduit.orchestration.harness.default-sla-ms:5000}") int defaultSlaMs,
            @Value("${conduit.orchestration.harness.bulkhead.max-concurrent:5}") int bulkheadMaxConcurrent,
            @Value("${conduit.orchestration.harness.bulkhead.queue-capacity:20}") int bulkheadQueueCapacity,
            @Value("${conduit.orchestration.harness.circuit-breaker.failure-rate-threshold:50}") int cbFailureRate,
            @Value("${conduit.orchestration.harness.circuit-breaker.slow-call-rate-threshold:80}") int cbSlowCallRate,
            @Value("${conduit.orchestration.harness.circuit-breaker.slow-call-duration-seconds:10}") int cbSlowCallDurationSeconds,
            @Value("${conduit.orchestration.harness.circuit-breaker.open-wait-seconds:30}") int cbOpenWaitSeconds,
            @Value("${conduit.orchestration.harness.circuit-breaker.sliding-window-size:10}") int cbSlidingWindowSize,
            @Value("${conduit.orchestration.harness.circuit-breaker.min-calls:3}") int cbMinCalls,
            @Value("${conduit.orchestration.harness.circuit-breaker.half-open-permitted-calls:2}") int cbHalfOpenPermittedCalls) {
        this.adapters = adapters;
        this.cbRegistry = cbRegistry;
        this.meterRegistry = meterRegistry;
        this.defaultSlaMs = defaultSlaMs;
        this.bulkheadMaxConcurrent = bulkheadMaxConcurrent;
        this.bulkheadQueueCapacity = bulkheadQueueCapacity;
        this.cbFailureRate = cbFailureRate;
        this.cbSlowCallRate = cbSlowCallRate;
        this.cbSlowCallDurationSeconds = cbSlowCallDurationSeconds;
        this.cbOpenWaitSeconds = cbOpenWaitSeconds;
        this.cbSlidingWindowSize = cbSlidingWindowSize;
        this.cbMinCalls = cbMinCalls;
        this.cbHalfOpenPermittedCalls = cbHalfOpenPermittedCalls;
    }

    /**
     * Execute a single plan node through the resilience harness.
     *
     * <p>Never throws — all failures are captured in the returned {@link NodeResult}.
     */
    public NodeResult execute(PlanNode node) {
        // Delegate to overloaded form with no external executor (creates a per-call virtual thread).
        return execute(node, null);
    }

    /**
     * Execute a plan node through the resilience harness.
     * The {@code exec} parameter is ignored — the harness owns the virtual-thread executor.
     * Kept for API backward-compatibility with callers that pass an external executor.
     *
     * <p>Never throws — all failures are captured in the returned {@link NodeResult}.
     */
    public NodeResult execute(PlanNode node, ExecutorService exec) {
        AgentManifest agent = node.agent();
        String agentId     = agent.agentId();
        long start         = System.currentTimeMillis();
        int  slaMs         = resolveTimeoutMs(agent);

        ProtocolAdapter adapter = findAdapter(agent.protocol());
        if (adapter == null) {
            long latency = System.currentTimeMillis() - start;
            log.warn("No adapter found for protocol '{}' (agent={})", agent.protocol(), agentId);
            NodeResult noAdapterResult = failed(node, latency,
                    "No ProtocolAdapter registered for protocol: " + agent.protocol());
            emitCallCounter(agentId, agent.protocol(), noAdapterResult.status().name());
            emitLatencyTimer(agentId, agent.protocol(), latency);
            return noAdapterResult;
        }

        CircuitBreaker cb   = circuitBreaker(agentId);
        Semaphore executing = executingSlots.computeIfAbsent(agentId,
                k -> new Semaphore(bulkheadMaxConcurrent));
        Semaphore queued    = queueSlots.computeIfAbsent(agentId,
                k -> new Semaphore(bulkheadQueueCapacity));

        // Register gauges exactly once per agentId — Gauge holds a weak ref to the semaphore
        // so the semaphore instance must be stable (computeIfAbsent guarantees that).
        if (registeredGauges.add(agentId)) {
            registerGauges(agentId, cb, executing, queued);
        }

        // Reject immediately if the queue is full — no blocking here.
        if (!queued.tryAcquire()) {
            long latency = System.currentTimeMillis() - start;
            log.warn("Agent {} QUEUE FULL — max-concurrent={} queue-capacity={}",
                    agentId, bulkheadMaxConcurrent, bulkheadQueueCapacity);
            emitCallCounter(agentId, agent.protocol(), "QUEUE_FULL");
            return failed(node, latency, "Bulkhead queue full (" + bulkheadQueueCapacity + " max)");
        }

        // Submit to the virtual-thread executor. The virtual thread parks on
        // executing.acquire() — it unmounts from its carrier, freeing the OS thread
        // for other work. This is the correct pattern: no platform thread pool needed.
        Future<JsonNode> future = vtExecutor.submit(() -> {
            // Phase 1: leave the queue. Whether we successfully acquire an executing
            // slot OR the acquire() is interrupted (future cancelled on SLA timeout),
            // the queue permit is released exactly once in this finally — so a
            // timed-out call parked on acquire() can never leak its queue permit.
            try {
                executing.acquire();  // park this virtual thread until a slot opens
            } finally {
                queued.release();     // free the queue permit deterministically
            }
            // Phase 2: executing slot is held — run the call and release it on the way out.
            try {
                return CircuitBreaker.decorateCallable(cb,
                        () -> adapter.invoke(node.agent(), node.input())).call();
            } finally {
                executing.release();
            }
        });

        try {
            JsonNode data = future.get(slaMs, TimeUnit.MILLISECONDS);
            long latency = System.currentTimeMillis() - start;
            log.debug("Agent {} OK in {}ms", agentId, latency);
            NodeResult okResult = new NodeResult(node.nodeId(), agentId, agent.protocol(),
                    NodeResult.Status.OK, data, latency, null);
            emitCallCounter(agentId, agent.protocol(), okResult.status().name());
            emitLatencyTimer(agentId, agent.protocol(), latency);
            return okResult;

        } catch (TimeoutException e) {
            future.cancel(true);
            long latency = System.currentTimeMillis() - start;
            log.warn("Agent {} TIMEOUT after {}ms (sla={}ms)", agentId, latency, slaMs);
            NodeResult timeoutResult = new NodeResult(node.nodeId(), agentId, agent.protocol(),
                    NodeResult.Status.TIMEOUT, null, latency,
                    "Agent timed out after " + latency + "ms");
            emitCallCounter(agentId, agent.protocol(), timeoutResult.status().name());
            emitLatencyTimer(agentId, agent.protocol(), latency);
            return timeoutResult;

        } catch (ExecutionException e) {
            long latency = System.currentTimeMillis() - start;
            Throwable cause = e.getCause();
            if (cause instanceof CallNotPermittedException) {
                log.warn("Circuit breaker OPEN for agent {}", agentId);
                NodeResult breakerResult = new NodeResult(node.nodeId(), agentId, agent.protocol(),
                        NodeResult.Status.BREAKER_OPEN, null, latency,
                        "Circuit breaker is open: " + cause.getMessage());
                emitCallCounter(agentId, agent.protocol(), breakerResult.status().name());
                emitLatencyTimer(agentId, agent.protocol(), latency);
                return breakerResult;
            }
            // queued.release() always runs inside the VT (phase-1 finally), whether the
            // executing slot was acquired or the acquire() was interrupted — so there is
            // no queue permit to reconcile here.
            log.warn("Agent {} FAILED: {}", agentId, cause != null ? cause.getMessage() : "unknown", cause);
            String msg = cause != null && cause.getMessage() != null
                    ? cause.getMessage() : e.getClass().getSimpleName();
            NodeResult failedResult = failed(node, latency, msg);
            emitCallCounter(agentId, agent.protocol(), failedResult.status().name());
            emitLatencyTimer(agentId, agent.protocol(), latency);
            return failedResult;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long latency = System.currentTimeMillis() - start;
            NodeResult interruptedResult = failed(node, latency, "Interrupted while waiting for agent " + agentId);
            emitCallCounter(agentId, agent.protocol(), interruptedResult.status().name());
            emitLatencyTimer(agentId, agent.protocol(), latency);
            return interruptedResult;
        }
    }

    // ── Component factories ──────────────────────────────────────────────────

    private CircuitBreaker circuitBreaker(String agentId) {
        // Supplier-based lookup is atomic (create-if-absent) — eliminates the TOCTOU race
        // that could cause IllegalStateException under concurrent first-calls for the same agent.
        return cbRegistry.circuitBreaker(agentId, () -> CircuitBreakerConfig.custom()
                .failureRateThreshold(cbFailureRate)
                .slowCallRateThreshold(cbSlowCallRate)
                .slowCallDurationThreshold(Duration.ofSeconds(cbSlowCallDurationSeconds))
                .waitDurationInOpenState(Duration.ofSeconds(cbOpenWaitSeconds))
                .slidingWindowSize(cbSlidingWindowSize)
                .minimumNumberOfCalls(cbMinCalls)
                .permittedNumberOfCallsInHalfOpenState(cbHalfOpenPermittedCalls)
                .build());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ProtocolAdapter findAdapter(String protocol) {
        return adapters.stream()
                .filter(a -> a.protocol().equals(protocol))
                .findFirst()
                .orElse(null);
    }

    private int resolveTimeoutMs(AgentManifest agent) {
        if (agent.constraints() != null && agent.constraints().slaTimeoutMs() > 0) {
            return agent.constraints().slaTimeoutMs();
        }
        return defaultSlaMs;
    }

    private NodeResult failed(PlanNode node, long latencyMs, String message) {
        return new NodeResult(
                node.nodeId(),
                node.agent().agentId(),
                node.agent().protocol(),
                NodeResult.Status.FAILED,
                null,
                latencyMs,
                message);
    }

    // ── Metric helpers ───────────────────────────────────────────────────────

    /** Increments the per-agent call counter. Micrometer caches the Counter by key. */
    private void emitCallCounter(String agentId, String protocol, String status) {
        Counter.builder("conduit.agent.calls")
                .description("Total agent invocations by outcome")
                .tag("agentId", agentId)
                .tag("protocol", protocol)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    /** Records the per-agent call latency. Micrometer caches the Timer by key. */
    private void emitLatencyTimer(String agentId, String protocol, long latencyMs) {
        Timer.builder("conduit.agent.latency")
                .description("Agent response time distribution")
                .tag("agentId", agentId)
                .tag("protocol", protocol)
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Registers the three per-agent gauges exactly once (called inside
     * {@code registeredGauges.add(agentId)} guard so it fires only on the first
     * call per agent).  Gauges hold weak references to the semaphore/CB instances,
     * which are stable because they live in {@link #executingSlots} /
     * {@link #queueSlots} / {@link #cbRegistry} maps.
     */
    private void registerGauges(String agentId, CircuitBreaker cb,
                                 Semaphore executing, Semaphore queued) {
        Gauge.builder("conduit.circuit.breaker.state", cb,
                        c -> switch (c.getState()) {
                            case CLOSED    -> 0.0;
                            case HALF_OPEN -> 1.0;
                            case OPEN      -> 2.0;
                            default        -> -1.0;
                        })
                .description("Circuit breaker state: 0=CLOSED 1=HALF_OPEN 2=OPEN")
                .tag("agentId", agentId)
                .register(meterRegistry);

        Gauge.builder("conduit.bulkhead.executing", executing,
                        s -> (double) (bulkheadMaxConcurrent - s.availablePermits()))
                .description("Currently executing calls (0 to max-concurrent)")
                .tag("agentId", agentId)
                .register(meterRegistry);

        Gauge.builder("conduit.bulkhead.queued", queued,
                        s -> (double) (bulkheadQueueCapacity - s.availablePermits()))
                .description("Calls waiting for execution slot (0 to queue-capacity)")
                .tag("agentId", agentId)
                .register(meterRegistry);
    }
}
