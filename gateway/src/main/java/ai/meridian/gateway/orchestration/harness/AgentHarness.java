package ai.meridian.gateway.orchestration.harness;

import ai.meridian.gateway.adapter.ProtocolAdapter;
import ai.meridian.gateway.orchestration.model.NodeResult;
import ai.meridian.gateway.orchestration.model.PlanNode;
import ai.meridian.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
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
            @Value("${meridian.orchestration.harness.default-sla-ms:5000}") int defaultSlaMs,
            @Value("${meridian.orchestration.harness.bulkhead.max-concurrent:5}") int bulkheadMaxConcurrent,
            @Value("${meridian.orchestration.harness.bulkhead.queue-capacity:20}") int bulkheadQueueCapacity,
            @Value("${meridian.orchestration.harness.circuit-breaker.failure-rate-threshold:50}") int cbFailureRate,
            @Value("${meridian.orchestration.harness.circuit-breaker.slow-call-rate-threshold:80}") int cbSlowCallRate,
            @Value("${meridian.orchestration.harness.circuit-breaker.slow-call-duration-seconds:10}") int cbSlowCallDurationSeconds,
            @Value("${meridian.orchestration.harness.circuit-breaker.open-wait-seconds:30}") int cbOpenWaitSeconds,
            @Value("${meridian.orchestration.harness.circuit-breaker.sliding-window-size:10}") int cbSlidingWindowSize,
            @Value("${meridian.orchestration.harness.circuit-breaker.min-calls:3}") int cbMinCalls,
            @Value("${meridian.orchestration.harness.circuit-breaker.half-open-permitted-calls:2}") int cbHalfOpenPermittedCalls) {
        this.adapters = adapters;
        this.cbRegistry = cbRegistry;
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
            return failed(node, latency,
                    "No ProtocolAdapter registered for protocol: " + agent.protocol());
        }

        CircuitBreaker cb   = circuitBreaker(agentId);
        Semaphore executing = executingSlots.computeIfAbsent(agentId,
                k -> new Semaphore(bulkheadMaxConcurrent));
        Semaphore queued    = queueSlots.computeIfAbsent(agentId,
                k -> new Semaphore(bulkheadQueueCapacity));

        // Reject immediately if the queue is full — no blocking here.
        if (!queued.tryAcquire()) {
            long latency = System.currentTimeMillis() - start;
            log.warn("Agent {} QUEUE FULL — max-concurrent={} queue-capacity={}",
                    agentId, bulkheadMaxConcurrent, bulkheadQueueCapacity);
            return failed(node, latency, "Bulkhead queue full (" + bulkheadQueueCapacity + " max)");
        }

        // Submit to the virtual-thread executor. The virtual thread parks on
        // executing.acquire() — it unmounts from its carrier, freeing the OS thread
        // for other work. This is the correct pattern: no platform thread pool needed.
        Future<JsonNode> future = vtExecutor.submit(() -> {
            boolean acquiredExecuting = false;
            try {
                executing.acquire();  // park this virtual thread until a slot opens
                acquiredExecuting = true;
                queued.release();     // slot acquired — free the queue permit
                return CircuitBreaker.decorateCallable(cb,
                        () -> adapter.invoke(node.agent(), node.input())).call();
            } finally {
                if (acquiredExecuting) executing.release();
                // If acquire() was interrupted (future cancelled while parked),
                // acquiredExecuting=false and executing permit was never taken — no release needed.
                // queued permit is handled by the outer catch block in that case.
            }
        });

        try {
            JsonNode data = future.get(slaMs, TimeUnit.MILLISECONDS);
            long latency = System.currentTimeMillis() - start;
            log.debug("Agent {} OK in {}ms", agentId, latency);
            return new NodeResult(node.nodeId(), agentId, agent.protocol(),
                    NodeResult.Status.OK, data, latency, null);

        } catch (TimeoutException e) {
            future.cancel(true);
            long latency = System.currentTimeMillis() - start;
            log.warn("Agent {} TIMEOUT after {}ms (sla={}ms)", agentId, latency, slaMs);
            return new NodeResult(node.nodeId(), agentId, agent.protocol(),
                    NodeResult.Status.TIMEOUT, null, latency,
                    "Agent timed out after " + latency + "ms");

        } catch (ExecutionException e) {
            long latency = System.currentTimeMillis() - start;
            Throwable cause = e.getCause();
            if (cause instanceof CallNotPermittedException) {
                log.warn("Circuit breaker OPEN for agent {}", agentId);
                return new NodeResult(node.nodeId(), agentId, agent.protocol(),
                        NodeResult.Status.BREAKER_OPEN, null, latency,
                        "Circuit breaker is open: " + cause.getMessage());
            }
            // queued.release() already ran inside the VT (line after executing.acquire()).
            // The only exception: if executing.acquire() itself was interrupted before
            // queued.release() could run — in that case the queue permit is still held.
            if (cause instanceof InterruptedException) {
                queued.release();
            }
            log.warn("Agent {} FAILED: {}", agentId, cause != null ? cause.getMessage() : "unknown", cause);
            String msg = cause != null && cause.getMessage() != null
                    ? cause.getMessage() : e.getClass().getSimpleName();
            return failed(node, latency, msg);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long latency = System.currentTimeMillis() - start;
            return failed(node, latency, "Interrupted while waiting for agent " + agentId);
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
}
