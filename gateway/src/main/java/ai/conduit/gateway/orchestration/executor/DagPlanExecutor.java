package ai.conduit.gateway.orchestration.executor;

import ai.conduit.gateway.orchestration.harness.AgentHarness;
import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import ai.conduit.gateway.infrastructure.telemetry.TraceEventPublisher;
import ai.conduit.gateway.infrastructure.telemetry.event.NodeConditionData;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import com.fasterxml.jackson.databind.JsonNode;
import io.burt.jmespath.JmesPath;
import io.burt.jmespath.jackson.JacksonRuntime;
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Executes a dependency-wired {@link Plan} (populated {@code dependsOn} edges, topological order)
 * by <b>topological layers</b>: every node whose dependencies have all completed successfully is
 * released together, in parallel, on virtual threads through the existing {@link AgentHarness}. When
 * a layer finishes, each successful node's output is projected into the {@link Blackboard} and the
 * next layer is bound from it.
 *
 * <p>Additive counterpart to {@link FlatPlanExecutor}: the flat path fans out independent nodes; this
 * path honours producer→consumer edges. Both reuse the same {@link AgentHarness} (per-node bulkhead /
 * circuit-breaker / SLA) and {@link ai.conduit.gateway.adapter.ProtocolAdapter} unchanged.
 *
 * <p><b>Partial-failure tolerant (hard-rule d).</b> A FAILED / TIMEOUT / BREAKER_OPEN node never
 * aborts the plan: its transitive dependents are marked <i>unmet</i> and skipped (a synthetic FAILED
 * {@link NodeResult}), while independent branches keep running. Callers receive one {@link NodeResult}
 * per plan node — survivors carry data; skipped nodes carry an explanatory error message.
 *
 * <p><b>World B:</b> no domain vocabulary. The executor reasons only over {@code nodeId} /
 * {@code dependsOn} graph structure and the manifest-declared {@code io} contract via the blackboard.
 */
@Service
public class DagPlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(DagPlanExecutor.class);
    private static final JmesPath<JsonNode> JMES_PATH = new JacksonRuntime();

    private final AgentHarness harness;
    private final Tracer tracer;
    private final MeterRegistry meterRegistry;
    private final TraceEventPublisher tracePublisher;
    private final long overallDeadlineMs;

    public DagPlanExecutor(
            AgentHarness harness,
            Tracer tracer,
            MeterRegistry meterRegistry,
            @Value("${conduit.orchestration.fan-out-deadline-ms:60000}") long overallDeadlineMs) {
        this(harness, tracer, meterRegistry, null, overallDeadlineMs);
    }

    @Autowired
    public DagPlanExecutor(
            AgentHarness harness,
            Tracer tracer,
            MeterRegistry meterRegistry,
            TraceEventPublisher tracePublisher,
            @Value("${conduit.orchestration.fan-out-deadline-ms:60000}") long overallDeadlineMs) {
        this.harness = harness;
        this.tracer = tracer;
        this.meterRegistry = meterRegistry;
        this.tracePublisher = tracePublisher;
        this.overallDeadlineMs = overallDeadlineMs;
    }

    /**
     * Execute {@code plan} layer by layer, binding each node's input from {@code blackboard}.
     *
     * @param plan       a resolver-produced plan with populated {@code dependsOn} edges
     * @param blackboard seeded with available entity keys and the leaf nodes' pre-bound inputs;
     *                   mutated as producer outputs are projected
     * @return one {@link NodeResult} per plan node, in plan (topological) order
     */
    public List<NodeResult> execute(Plan plan, Blackboard blackboard) {
        return execute(plan, blackboard, null, null);
    }

    public List<NodeResult> execute(Plan plan, Blackboard blackboard,
                                    String requestId, String conversationId) {
        if (plan == null || plan.nodes() == null || plan.nodes().isEmpty()) {
            log.warn("DagPlanExecutor received an empty plan");
            return List.of();
        }

        List<PlanNode> allNodes = plan.nodes();
        log.info("DagPlanExecutor: executing {} nodes by topological layers (deadline={}ms)",
                allNodes.size(), overallDeadlineMs);

        // Resolver-SELECTION counter — parity with FlatPlanExecutor (agentId is manifest-declared).
        allNodes.forEach(n -> Counter.builder("conduit.resolver.selection")
                .description("Agents selected by the resolver, per agent")
                .tag("agentId", n.agent().agentId())
                .register(meterRegistry)
                .increment());

        Map<String, NodeResult> results = new LinkedHashMap<>();   // nodeId → terminal result
        long deadline = System.currentTimeMillis() + overallDeadlineMs;

        final Context parentContext = Context.current();
        var exec = Executors.newVirtualThreadPerTaskExecutor();

        try {
            // Loop until every node has a terminal result (executed, or skipped as unmet).
            while (results.size() < allNodes.size()) {
                List<PlanNode> ready = new ArrayList<>();
                List<PlanNode> unmet = new ArrayList<>();
                List<PlanNode> cleanSkipped = new ArrayList<>();

                for (PlanNode node : allNodes) {
                    if (results.containsKey(node.nodeId())) continue;   // already terminal
                    DepState state = classifyDeps(node, results);
                    switch (state) {
                        case READY -> ready.add(node);
                        case BLOCKED -> unmet.add(node);
                        case CLEAN_SKIPPED -> cleanSkipped.add(node);
                        case WAITING -> { /* a dep is still pending — a later layer */ }
                    }
                }

                // Clean condition-false skips propagate as "not applicable", not as failures.
                for (PlanNode node : cleanSkipped) {
                    log.info("Node {} (agent={}) SKIPPED — an upstream condition-false node was not applicable",
                            node.nodeId(), node.agent().agentId());
                    results.put(node.nodeId(), cleanSkipResult(node,
                            "Skipped: an upstream condition evaluated false"));
                }

                // Skip nodes whose dependency failed — harvest, never abort the plan.
                for (PlanNode node : unmet) {
                    log.warn("Node {} (agent={}) SKIPPED — an upstream dependency did not succeed",
                            node.nodeId(), node.agent().agentId());
                    results.put(node.nodeId(), unmetResult(node));
                }

                if (ready.isEmpty()) {
                    // No runnable node this pass. If nothing was skipped either, the remaining nodes
                    // are wedged (should not happen for a valid DAG) — mark them unmet and stop.
                    if (unmet.isEmpty() && cleanSkipped.isEmpty()) {
                        for (PlanNode node : allNodes) {
                            results.computeIfAbsent(node.nodeId(), k -> unmetResult(node));
                        }
                        break;
                    }
                    continue;   // skips may have unblocked/blocked more — re-classify
                }

                runLayer(ready, blackboard, results, exec, parentContext, deadline,
                        requestId, conversationId);
            }
        } finally {
            exec.close();
        }

        long okCount = results.values().stream().filter(NodeResult::isOk).count();
        log.info("DagPlanExecutor done: {}/{} nodes succeeded", okCount, allNodes.size());

        // Return in plan (topological) order.
        return allNodes.stream().map(n -> results.get(n.nodeId())).toList();
    }

    private enum DepState { READY, WAITING, BLOCKED, CLEAN_SKIPPED }

    /** READY = all deps done+ok; CLEAN_SKIPPED = not applicable; BLOCKED = failed dep; WAITING = pending dep. */
    private DepState classifyDeps(PlanNode node, Map<String, NodeResult> results) {
        boolean sawCleanSkip = false;
        boolean allOk = true;
        for (String dep : node.dependsOn()) {
            NodeResult r = results.get(dep);
            if (r == null) {
                allOk = false;            // still pending → keep for a later pass
            } else if (r.isCleanSkip()) {
                sawCleanSkip = true;
            } else if (!r.isOk()) {
                return DepState.BLOCKED;   // a dependency failed/skipped → this node is unmet
            }
        }
        if (sawCleanSkip) return DepState.CLEAN_SKIPPED;
        return allOk ? DepState.READY : DepState.WAITING;
    }

    /**
     * Bind and execute a layer of ready nodes in parallel; project survivors into the blackboard.
     *
     * <p>Binding + the pre-dispatch composability gate ({@link Blackboard#checkComposable}) both run
     * on the caller thread (deterministic) before the layer is released. A node whose bound input is
     * not composable (an incomplete upstream, or a schema mismatch — see {@link Blackboard}) is never
     * handed to the harness: it gets an immediate synthetic FAILED result, same shape as {@link
     * #unmetResult}, so it degrades through the existing partial-failure path rather than ever
     * reaching the agent with a malformed request.
     */
    private void runLayer(List<PlanNode> ready,
                          Blackboard blackboard,
                          Map<String, NodeResult> results,
                          ExecutorService exec,
                          Context parentContext,
                          long deadline,
                          String requestId,
                          String conversationId) {

        // Bind + gate on the caller thread (deterministic) before releasing the layer.
        List<PlanNode> bound = new ArrayList<>();
        for (PlanNode n : ready) {
            JsonNode input = blackboard.bind(n);
            PlanNode candidate = new PlanNode(n.nodeId(), n.agent(), input, n.dependsOn(), n.condition());
            String reason = blackboard.checkComposable(candidate, input);
            if (reason != null) {
                log.warn("Node {} (agent={}) NOT dispatched — {}", n.nodeId(), n.agent().agentId(), reason);
                results.put(n.nodeId(), composeFailure(n, reason));
                continue;
            }
            // T4-REVERIFY: condition input must remain the post-coverage-filter bound input once
            // per-producer coverage filtering lands, so predicates cannot observe uncovered data.
            NodeResult conditionResult = evaluateCondition(candidate, input, requestId, conversationId);
            if (conditionResult != null) {
                results.put(n.nodeId(), conditionResult);
                continue;
            }
            bound.add(candidate);
        }

        List<CompletableFuture<NodeResult>> futures = bound.stream()
                .map(node -> CompletableFuture.supplyAsync(() -> {
                    Span span = tracer.spanBuilder("agent.invoke")
                            .setParent(parentContext)
                            .startSpan();
                    span.setAttribute("openinference.span.kind", "AGENT");
                    span.setAttribute("agent.id", node.agent().agentId());
                    span.setAttribute("agent.protocol", node.agent().protocol());
                    try (Scope ignored = span.makeCurrent()) {
                        NodeResult result = harness.execute(node, exec);
                        span.setAttribute("agent.status", result.status().name());
                        span.setAttribute("agent.latency_ms", result.latencyMs());
                        if (!result.isOk()) {
                            span.setStatus(StatusCode.ERROR, result.status().name());
                        }
                        return result;
                    } finally {
                        span.end();
                    }
                }, exec))
                .toList();

        long remaining = Math.max(1, deadline - System.currentTimeMillis());
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(remaining, TimeUnit.MILLISECONDS)
                .exceptionally(t -> null)   // swallow the layer deadline — harvest survivors
                .join();

        for (int i = 0; i < bound.size(); i++) {
            PlanNode node = bound.get(i);
            NodeResult r = harvest(futures.get(i), node);
            results.put(node.nodeId(), r);
            if (r.isOk()) {
                blackboard.project(node, r.data());   // publish for downstream layers
            }
        }
    }

    private NodeResult harvest(CompletableFuture<NodeResult> future, PlanNode node) {
        if (future.isDone() && !future.isCompletedExceptionally()) {
            return future.join();
        }
        log.warn("Node {} (agent={}) did not complete within the overall deadline — marking TIMEOUT",
                node.nodeId(), node.agent().agentId());
        return new NodeResult(node.nodeId(), node.agent().agentId(), node.agent().protocol(),
                NodeResult.Status.TIMEOUT, null, overallDeadlineMs,
                "Did not complete within the " + overallDeadlineMs + "ms overall deadline");
    }

    /** Synthetic result for a node skipped because an upstream dependency did not succeed. */
    private NodeResult unmetResult(PlanNode node) {
        return new NodeResult(node.nodeId(), node.agent().agentId(), node.agent().protocol(),
                NodeResult.Status.FAILED, null, 0,
                "Skipped: an upstream dependency did not complete successfully");
    }

    private NodeResult cleanSkipResult(PlanNode node, String reason) {
        return new NodeResult(node.nodeId(), node.agent().agentId(), node.agent().protocol(),
                NodeResult.Status.SKIPPED_CONDITION_FALSE, null, 0, reason);
    }

    private NodeResult conditionErrorResult(PlanNode node, String reason) {
        return new NodeResult(node.nodeId(), node.agent().agentId(), node.agent().protocol(),
                NodeResult.Status.CONDITION_ERROR, null, 0, reason);
    }

    private NodeResult evaluateCondition(PlanNode node, JsonNode input,
                                         String requestId, String conversationId) {
        if (!node.hasCondition()) return null;
        String expression = node.condition();
        try {
            JsonNode result = JMES_PATH.compile(expression).search(input);
            if (result == null || !result.isBoolean()) {
                String reason = "condition did not evaluate to boolean";
                publishCondition(node, expression, "error", reason, requestId, conversationId);
                return conditionErrorResult(node, reason);
            }
            boolean verdict = result.asBoolean();
            publishCondition(node, expression, verdict ? "true" : "false", null, requestId, conversationId);
            if (!verdict) {
                return cleanSkipResult(node, "Skipped: condition evaluated false");
            }
            return null;
        } catch (Exception e) {
            String reason = "condition evaluation failed: " + e.getMessage();
            publishCondition(node, expression, "error", reason, requestId, conversationId);
            return conditionErrorResult(node, reason);
        }
    }

    private void publishCondition(PlanNode node, String expression, String verdict, String reason,
                                  String requestId, String conversationId) {
        if (tracePublisher == null || requestId == null || conversationId == null) return;
        tracePublisher.publish(TraceEvent.of("node_condition", requestId, conversationId,
                new NodeConditionData(node.nodeId(), node.agent().agentId(), expression, verdict, reason)));
    }

    /**
     * Synthetic result for a node whose bound input failed {@link Blackboard#checkComposable} —
     * never dispatched to the harness/agent. Same shape as {@link #unmetResult}: a plain FAILED
     * result, so it flows through the existing partial-failure path (transitive dependents are
     * skipped too; synthesis proceeds from whatever else succeeded and states what's missing).
     */
    private NodeResult composeFailure(PlanNode node, String reason) {
        return new NodeResult(node.nodeId(), node.agent().agentId(), node.agent().protocol(),
                NodeResult.Status.FAILED, null, 0, reason);
    }
}
