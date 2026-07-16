package ai.conduit.gateway.orchestration.executor;

import ai.conduit.gateway.infrastructure.telemetry.MdcPropagation;
import ai.conduit.gateway.domain.coverage.CoverageCheckResult;
import ai.conduit.gateway.domain.coverage.CoverageClient;
import ai.conduit.gateway.domain.manifest.DomainManifest;
import ai.conduit.gateway.domain.manifest.EffectiveManifest;
import ai.conduit.gateway.orchestration.harness.AgentHarness;
import ai.conduit.gateway.orchestration.invoke.AllowAllInvocationAuthorizer;
import ai.conduit.gateway.orchestration.invoke.AuthorizationGrant;
import ai.conduit.gateway.orchestration.invoke.GovernedInvoker;
import ai.conduit.gateway.orchestration.invoke.InvocationContext;
import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import ai.conduit.gateway.infrastructure.telemetry.TraceEventPublisher;
import ai.conduit.gateway.infrastructure.telemetry.event.CheckDeniedData;
import ai.conduit.gateway.infrastructure.telemetry.event.MapIterationData;
import ai.conduit.gateway.infrastructure.telemetry.event.NodeConditionData;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ai.conduit.gateway.infrastructure.expression.CompiledExpr;
import ai.conduit.gateway.infrastructure.expression.EvalEngine;
import ai.conduit.gateway.infrastructure.expression.RootVar;
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
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;

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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GovernedInvoker governedInvoker;
    private final Tracer tracer;
    private final MeterRegistry meterRegistry;
    private final TraceEventPublisher tracePublisher;
    private final EvalEngine evalEngine;
    private final long overallDeadlineMs;
    private final int globalMapMaxItems;
    private final int globalMapMaxConcurrency;

    /**
     * Test-convenience constructor (NOT {@code @Autowired}): wraps the harness in a
     * {@link GovernedInvoker} with an {@link AllowAllInvocationAuthorizer} so pre-existing executor
     * unit tests exercise fan-out/DAG/map mechanics without minting grants. The production wiring uses
     * the {@code @Autowired} constructor below with the fail-closed grant/Cerbos authorizer bean.
     */
    public DagPlanExecutor(
            AgentHarness harness,
            Tracer tracer,
            MeterRegistry meterRegistry,
            long overallDeadlineMs) {
        this(new GovernedInvoker(new AllowAllInvocationAuthorizer(), harness, null),
                tracer, meterRegistry, null, overallDeadlineMs, 100, 8,
                new ai.conduit.gateway.infrastructure.expression.CelEvalEngine(MAPPER));
    }

    @Autowired
    public DagPlanExecutor(
            GovernedInvoker governedInvoker,
            Tracer tracer,
            MeterRegistry meterRegistry,
            TraceEventPublisher tracePublisher,
            @Value("${conduit.orchestration.fan-out-deadline-ms:60000}") long overallDeadlineMs,
            @Value("${conduit.orchestration.map.max-items:100}") int globalMapMaxItems,
            @Value("${conduit.orchestration.map.max-concurrency:8}") int globalMapMaxConcurrency,
            EvalEngine evalEngine) {
        this.governedInvoker = governedInvoker;
        this.tracer = tracer;
        this.meterRegistry = meterRegistry;
        this.tracePublisher = tracePublisher;
        this.evalEngine = evalEngine;
        this.overallDeadlineMs = overallDeadlineMs;
        this.globalMapMaxItems = Math.max(1, globalMapMaxItems);
        this.globalMapMaxConcurrency = Math.max(1, globalMapMaxConcurrency);
    }

    /**
     * Execute {@code plan} layer by layer, binding each node's input from {@code blackboard}.
     *
     * @param plan        a resolver-produced plan with populated {@code dependsOn} edges
     * @param blackboard  seeded with available entity keys and the leaf nodes' pre-bound inputs;
     *                    mutated as producer outputs are projected
     * @param callerToken the calling principal's verified JWT, captured as request-scoped DATA at
     *                    ingress and threaded here because {@code SecurityContextHolder} does not
     *                    survive the hop onto this executor's virtual threads (F-IDENTITY). Passed
     *                    through to every layer's {@link AgentHarness#execute} call.
     * @return one {@link NodeResult} per plan node, in plan (topological) order
     */
    public List<NodeResult> execute(Plan plan, Blackboard blackboard) {
        return execute(plan, blackboard, null, null, null);
    }

    public List<NodeResult> execute(Plan plan, Blackboard blackboard,
                                    String requestId, String conversationId) {
        return execute(plan, blackboard, requestId, conversationId, null);
    }

    public List<NodeResult> execute(Plan plan, Blackboard blackboard,
                                    String requestId, String conversationId, String callerToken) {
        return execute(plan, blackboard, requestId, conversationId, callerToken, null);
    }

    public List<NodeResult> execute(Plan plan, Blackboard blackboard,
                                    String requestId, String conversationId, String callerToken,
                                    CoverageContext coverageContext) {
        if (plan == null || plan.nodes() == null || plan.nodes().isEmpty()) {
            log.warn("DagPlanExecutor received an empty plan");
            return List.of();
        }

        List<PlanNode> allNodes = plan.nodes();
        log.info("DagPlanExecutor: executing {} nodes by topological layers (deadline={}ms)",
                allNodes.size(), overallDeadlineMs);

        // Authorization envelope for every governed hop in this plan. Production: ChatService attaches
        // it (structural grants minted from filterAgents). Legacy/no-mint: build one carrying the
        // caller token so the harness still receives identity; grants stay empty (fail-closed unless
        // the convenience allow-all authorizer is wired). The coverage gate below mints resource-scoped
        // grants into THIS instance, which the invoker's authorize phase then verifies.
        final InvocationContext ctx = plan.context() != null
                ? plan.context()
                : InvocationContext.of(coverageContext != null ? coverageContext.principalId() : null,
                        conversationId, requestId, callerToken, java.util.List.of());

        // Resolver-SELECTION counter — parity with FlatPlanExecutor (agentId is manifest-declared).
        allNodes.forEach(n -> Counter.builder("conduit.resolver.selection")
                .description("Agents selected by the resolver, per agent")
                .tag("agentId", n.agent().agentId())
                .register(meterRegistry)
                .increment());

        Map<String, NodeResult> results = new LinkedHashMap<>();   // nodeId → terminal result
        long deadline = System.currentTimeMillis() + overallDeadlineMs;

        final Context parentContext = Context.current();
        // See FlatPlanExecutor's identical comment: MDC is thread-local and does not survive the
        // hop onto `exec`'s virtual threads, so it must be captured here and re-applied per layer.
        final Map<String, String> mdcContext = MDC.getCopyOfContextMap();
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

                runLayer(ready, blackboard, results, exec, parentContext, mdcContext, deadline,
                        requestId, conversationId, callerToken, coverageContext, ctx);
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
                          Map<String, String> mdcContext,
                          long deadline,
                          String requestId,
                          String conversationId,
                          String callerToken,
                          CoverageContext coverageContext,
                          InvocationContext ctx) {

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
            NodeResult coverageResult = evaluateNodeCoverage(candidate, input, coverageContext,
                    requestId, conversationId, ctx);
            if (coverageResult != null) {
                results.put(n.nodeId(), coverageResult);
                continue;
            }
            NodeResult conditionResult = evaluateCondition(candidate, input, requestId, conversationId);
            if (conditionResult != null) {
                results.put(n.nodeId(), conditionResult);
                continue;
            }
            if (candidate.hasMap()) {
                NodeResult mapResult = executeMapNode(candidate, exec, parentContext, deadline,
                        requestId, conversationId, callerToken, ctx);
                results.put(n.nodeId(), mapResult);
                if (mapResult.isOk()) {
                    blackboard.project(candidate, mapResult.data());
                }
                continue;
            }
            bound.add(candidate);
        }

        List<CompletableFuture<NodeResult>> futures = bound.stream()
                .map(node -> CompletableFuture.supplyAsync(() -> {
                    NodeResult[] holder = new NodeResult[1];
                    MdcPropagation.run(mdcContext, () -> {
                        Span span = tracer.spanBuilder("agent.invoke")
                                .setParent(parentContext)
                                .startSpan();
                        span.setAttribute("openinference.span.kind", "AGENT");
                        span.setAttribute("agent.id", node.agent().agentId());
                        span.setAttribute("agent.protocol", node.agent().protocol());
                        try (Scope ignored = span.makeCurrent()) {
                            NodeResult result = governedInvoker.invoke(ctx, node, exec);
                            span.setAttribute("agent.status", result.status().name());
                            span.setAttribute("agent.latency_ms", result.latencyMs());
                            if (!result.isOk()) {
                                span.setStatus(StatusCode.ERROR, result.status().name());
                            }
                            holder[0] = result;
                        } finally {
                            span.end();
                        }
                    });
                    return holder[0];
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
            if (r.isOk()) {
                r = applyProducedEntityCoverage(node, r, coverageContext, requestId, conversationId);
            }
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

    private NodeResult executeMapNode(PlanNode node,
                                      ExecutorService exec,
                                      Context parentContext,
                                      long deadline,
                                      String requestId,
                                      String conversationId,
                                      String callerToken,
                                      InvocationContext ctx) {
        long start = System.currentTimeMillis();
        AgentManifest.MapSpec map = node.agent().io().map();
        JsonNode collection;
        try {
            CompiledExpr over = evalEngine.compile(map.over(), RootVar.INPUT);
            collection = evalEngine.eval(over, node.input(), EvalEngine.Mode.LENIENT);
        } catch (RuntimeException e) {
            return new NodeResult(node.nodeId(), node.agent().agentId(), node.agent().protocol(),
                    NodeResult.Status.FAILED, null, elapsed(start),
                    "map.over evaluation failed: " + e.getMessage());
        }
        if (collection == null || collection.isMissingNode() || collection.isNull()) {
            collection = MAPPER.createArrayNode();
        }
        if (!collection.isArray()) {
            return new NodeResult(node.nodeId(), node.agent().agentId(), node.agent().protocol(),
                    NodeResult.Status.FAILED, null, elapsed(start),
                    "map.over did not evaluate to an array");
        }

        int total = collection.size();
        int effectiveMaxItems = clampCap(map.maxItems(), globalMapMaxItems);
        int effectiveMaxConcurrency = clampCap(map.maxConcurrency(), globalMapMaxConcurrency);
        int ran = Math.min(total, effectiveMaxItems);
        boolean truncated = total > ran;
        if (truncated) {
            log.warn("Node {} (agent={}) map capped: total={} ran={} cap={}",
                    node.nodeId(), node.agent().agentId(), total, ran, effectiveMaxItems);
            Counter.builder("conduit.dag.map.capped")
                    .description("Map nodes capped by configured item limits")
                    .tag("reason", "map-capped")
                    .register(meterRegistry)
                    .increment();
        }

        if (ran == 0) {
            ObjectNode aggregate = aggregateFor(node, total, ran, 0, 0, truncated,
                    MAPPER.createArrayNode(), null);
            publishMapIteration(node, map.over(), total, ran, 0, 0, truncated,
                    effectiveMaxItems, effectiveMaxConcurrency, requestId, conversationId);
            return new NodeResult(node.nodeId(), node.agent().agentId(), node.agent().protocol(),
                    NodeResult.Status.OK, aggregate, elapsed(start), null);
        }

        Semaphore permits = new Semaphore(effectiveMaxConcurrency);
        List<CompletableFuture<MapItemOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < ran; i++) {
            final int index = i;
            final JsonNode rawItem = collection.get(i);
            futures.add(CompletableFuture.supplyAsync(() -> invokeMapItem(
                    node, map, rawItem, index, permits, exec, parentContext, callerToken, ctx), exec));
        }

        long remaining = Math.max(1, deadline - System.currentTimeMillis());
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(remaining, TimeUnit.MILLISECONDS)
                .exceptionally(t -> null)
                .join();

        ArrayNode itemSummaries = MAPPER.createArrayNode();
        int ok = 0;
        int failed = 0;
        for (int i = 0; i < futures.size(); i++) {
            MapItemOutcome outcome = harvestMapItem(futures.get(i), i);
            itemSummaries.add(outcome.summary());
            if (outcome.ok()) ok++; else failed++;
        }

        ObjectNode aggregate = aggregateFor(node, total, ran, ok, failed, truncated, itemSummaries, null);
        publishMapIteration(node, map.over(), total, ran, ok, failed, truncated,
                effectiveMaxItems, effectiveMaxConcurrency, requestId, conversationId);
        if (ok == 0) {
            return new NodeResult(node.nodeId(), node.agent().agentId(), node.agent().protocol(),
                    NodeResult.Status.FAILED, null, elapsed(start),
                    "map item fan-out failed for every item");
        }
        return new NodeResult(node.nodeId(), node.agent().agentId(), node.agent().protocol(),
                NodeResult.Status.OK, aggregate, elapsed(start), null);
    }

    private MapItemOutcome invokeMapItem(PlanNode node,
                                         AgentManifest.MapSpec map,
                                         JsonNode rawItem,
                                         int index,
                                         Semaphore permits,
                                         ExecutorService exec,
                                         Context parentContext,
                                         String callerToken,
                                         InvocationContext ctx) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return MapItemOutcome.failed(index, "interrupted before map item dispatch");
        }
        Span span = tracer.spanBuilder("agent.invoke")
                .setParent(parentContext)
                .startSpan();
        span.setAttribute("openinference.span.kind", "AGENT");
        span.setAttribute("agent.id", node.agent().agentId());
        span.setAttribute("agent.protocol", node.agent().protocol());
        span.setAttribute("map.node_id", node.nodeId());
        span.setAttribute("map.index", index);
        try (Scope ignored = span.makeCurrent()) {
            JsonNode itemInput = rawItem;
            if (map.hasItemSelect()) {
                CompiledExpr itemSelect = evalEngine.compile(map.itemSelect(), RootVar.ITEM);
                itemInput = evalEngine.eval(itemSelect, rawItem, EvalEngine.Mode.LENIENT);
            }
            PlanNode itemNode = new PlanNode(
                    node.nodeId() + "[" + index + "]",
                    node.agent(),
                    itemInput,
                    node.dependsOn(),
                    node.condition());
            NodeResult result = governedInvoker.invoke(ctx, itemNode, exec);
            span.setAttribute("agent.status", result.status().name());
            span.setAttribute("agent.latency_ms", result.latencyMs());
            if (!result.isOk()) {
                span.setStatus(StatusCode.ERROR, result.status().name());
            }
            return MapItemOutcome.from(index, result);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getClass().getSimpleName());
            return MapItemOutcome.failed(index, e.getMessage());
        } finally {
            permits.release();
            span.end();
        }
    }

    private MapItemOutcome harvestMapItem(CompletableFuture<MapItemOutcome> future, int index) {
        if (future.isDone() && !future.isCompletedExceptionally()) {
            return future.join();
        }
        return MapItemOutcome.failed(index, "map item did not complete before the overall deadline");
    }

    private ObjectNode aggregateFor(PlanNode node,
                                    int total,
                                    int ran,
                                    int ok,
                                    int failed,
                                    boolean truncated,
                                    ArrayNode items,
                                    String error) {
        ObjectNode aggregate = MAPPER.createObjectNode();
        aggregate.put("_map", true);
        aggregate.put("_items", total);
        aggregate.put("_total", total);
        aggregate.put("_ran", ran);
        aggregate.put("_ok", ok);
        aggregate.put("_failed", failed);
        aggregate.put("_truncated", truncated);
        aggregate.set("results", items);
        String narrative = "Map iteration ran " + ran + " of " + total + " items; "
                + ok + " succeeded and " + failed + " failed"
                + (truncated ? "; remaining items were skipped because the configured cap was reached." : ".");
        if (error != null && !error.isBlank()) {
            narrative = narrative + " Error: " + error;
        }
        aggregate.put("agent_narrative", narrative);
        aggregate.put("agent_id", node.agent().agentId());
        return aggregate;
    }

    private void publishMapIteration(PlanNode node, String over, int total, int ran, int ok, int failed,
                                     boolean truncated, int maxItems, int maxConcurrency,
                                     String requestId, String conversationId) {
        if (tracePublisher == null || requestId == null || conversationId == null) return;
        tracePublisher.publish(TraceEvent.of("map_iteration", requestId, conversationId,
                new MapIterationData(node.nodeId(), node.agent().agentId(), over, total, ran, ok, failed,
                        truncated, maxItems, maxConcurrency)));
    }

    private int clampCap(Integer manifestCap, int globalCap) {
        int requested = manifestCap == null ? globalCap : Math.max(1, manifestCap);
        return Math.max(1, Math.min(requested, globalCap));
    }

    private long elapsed(long start) {
        return Math.max(0, System.currentTimeMillis() - start);
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
            CompiledExpr compiled = evalEngine.compile(expression, RootVar.INPUT);
            JsonNode result = evalEngine.eval(compiled, input, EvalEngine.Mode.LENIENT);
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

    private NodeResult evaluateNodeCoverage(PlanNode node, JsonNode input, CoverageContext ctx,
                                            String requestId, String conversationId,
                                            InvocationContext invocationCtx) {
        if (ctx == null || node == null || node.agent() == null) return null;
        EffectiveManifest effective = ctx.effectiveManifest(node.agent());
        if (effective == null || !effective.resourceScoped() || effective.coverage() == null) return null;

        AgentManifest.Io io = node.agent().io();
        List<AgentManifest.Consume> consumes =
                (io == null || io.consumes() == null) ? List.of() : io.consumes();
        for (AgentManifest.Consume consume : consumes) {
            if (consume == null || !consume.isEntityRef()) continue;
            // An entity-ref consume slot may only bind from a resolved value. A blank/absent value is
            // an UNRESOLVED REFERENCE, not an access verdict — so it becomes a BIND FAILURE routed to
            // the flat-fallback/partial path, never a coverage denial. (Denying it made synthesis
            // render "outside your access" for an entity that is not a client at all.)
            JsonNode value = input == null ? null : input.path(consume.entity());
            List<String> ids = idsFrom(value);
            if (ids.isEmpty()) {
                if (!consume.isRequired()) {
                    continue;
                }
                return bindFailure(node);
            }
            for (String id : ids) {
                if (id == null || id.isBlank()) {
                    return bindFailure(node);
                }
                CoverageCheckResult check;
                try {
                    check = ctx.check(id, effective.coverage());
                } catch (CoverageClient.CoverageUnavailableException e) {
                    publishCoverageDeny(ctx, node, id, "coverage-unavailable", requestId, conversationId);
                    return coverageDenied(node, "coverage denied: coverage service unavailable");
                }
                if (check == null || !check.allowed()) {
                    publishCoverageDeny(ctx, node, id,
                            check == null ? "coverage-denied" : check.reason(), requestId, conversationId);
                    return coverageDenied(node, "coverage denied: not in coverage");
                }
                // Coverage cleared this id for this principal — mint a resource-scoped grant the invoker
                // verifies, and bind the node to the id so the checkpoint's TOCTOU compare (bound id vs
                // grant resourceId) closes. Coverage RPC count is unchanged (this is in-memory, on the
                // caller thread, inside the SAME check loop) — no per-map-item multiplication.
                if (invocationCtx != null) {
                    invocationCtx.addGrant(AuthorizationGrant.resourceScoped(
                            invocationCtx.principalId(), node.agent().agentId(), id, "coverage",
                            invocationCtx.requestId()));
                    invocationCtx.bindResource(node.nodeId(), id);
                }
            }
        }
        return null;
    }

    private NodeResult applyProducedEntityCoverage(PlanNode node, NodeResult result, CoverageContext ctx,
                                                   String requestId, String conversationId) {
        if (ctx == null || node == null || node.agent() == null || result.data() == null) return result;
        AgentManifest.Io io = node.agent().io();
        List<AgentManifest.Produce> produces =
                (io == null || io.produces() == null) ? List.of() : io.produces();
        JsonNode filtered = result.data();
        for (AgentManifest.Produce produce : produces) {
            List<AgentManifest.ProducedEntity> entities =
                    (produce == null || produce.entities() == null) ? List.of() : produce.entities();
            for (AgentManifest.ProducedEntity entity : entities) {
                FilterOutcome outcome = filterOneProducedEntity(node, filtered, entity, ctx,
                        requestId, conversationId);
                if (outcome.deniedScalar()) {
                    return coverageDenied(node, "coverage denied: produced entity not in coverage");
                }
                filtered = outcome.data();
            }
        }
        // No produced entity was pruned → the tree is untouched; keep the ORIGINAL payload handle
        // (which may be a spilled Ref) so provenance/audit reflect the adapter output verbatim.
        if (filtered == result.data()) {
            return result;
        }
        // F4 §3e: a coverage-denied produced entity was filtered out. The NodeResult is rebuilt with a
        // DERIVED Inline over the FILTERED tree — the audit hash always covers what actually grounded the
        // answer. Any pre-filter spilled Ref is DROPPED here (never projected downstream); it survives only
        // as parent_sha256 lineage, so coverage cannot be bypassed via the spill path. No hashing on this
        // request-path step: the parent sha is taken from the pre-existing Ref when present, else null.
        String parentSha = (result.payload() instanceof ai.conduit.gateway.adapter.PayloadHandle.Ref ref)
                ? ref.sha256() : null;
        ai.conduit.gateway.adapter.PayloadHandle derived =
                new ai.conduit.gateway.adapter.PayloadHandle.Inline(filtered,
                        ai.conduit.gateway.adapter.PayloadHandle.Provenance.DERIVED, parentSha);
        return new NodeResult(result.nodeId(), result.agentId(), result.protocol(), result.status(),
                filtered, result.latencyMs(), result.errorMessage(), derived);
    }

    private FilterOutcome filterOneProducedEntity(PlanNode node,
                                                  JsonNode data,
                                                  AgentManifest.ProducedEntity entity,
                                                  CoverageContext ctx,
                                                  String requestId,
                                                  String conversationId) {
        if (entity == null || entity.select() == null || entity.select().isBlank()) {
            publishCoverageDeny(ctx, node, null, "missing-entity-selector", requestId, conversationId);
            return new FilterOutcome(data, true);
        }
        JsonNode selected;
        try {
            CompiledExpr sel = evalEngine.compile(entity.select(), RootVar.OUTPUT);
            selected = evalEngine.eval(sel, data, EvalEngine.Mode.LENIENT);
        } catch (RuntimeException e) {
            publishCoverageDeny(ctx, node, null, "invalid-entity-selector", requestId, conversationId);
            return new FilterOutcome(data, true);
        }
        List<String> ids = idsFrom(selected);
        if (ids.isEmpty() && selected != null && !selected.isMissingNode() && !selected.isNull()) {
            publishCoverageDeny(ctx, node, null, "invalid-entity-selector-result", requestId, conversationId);
            return new FilterOutcome(data, true);
        }
        Set<String> allowed = new HashSet<>();
        Set<String> denied = new HashSet<>();
        EffectiveManifest effective = ctx.effectiveManifest(node.agent());
        DomainManifest.Coverage coverage = effective == null ? null : effective.coverage();
        if (coverage == null) {
            ids.forEach(id -> publishCoverageDeny(ctx, node, id, "missing-coverage", requestId, conversationId));
            return new FilterOutcome(data, !ids.isEmpty());
        }
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                denied.add(id);
                publishCoverageDeny(ctx, node, id, "blank-entity", requestId, conversationId);
                continue;
            }
            try {
                CoverageCheckResult check = ctx.check(id, coverage);
                if (check != null && check.allowed()) {
                    allowed.add(id);
                } else {
                    denied.add(id);
                    publishCoverageDeny(ctx, node, id,
                            check == null ? "coverage-denied" : check.reason(), requestId, conversationId);
                }
            } catch (CoverageClient.CoverageUnavailableException e) {
                publishCoverageDeny(ctx, node, id, "coverage-unavailable", requestId, conversationId);
                return new FilterOutcome(data, true);
            }
        }
        if (denied.isEmpty()) return new FilterOutcome(data, false);
        if (selected != null && selected.isTextual()) return new FilterOutcome(data, true);

        String arrayPath = arrayPathFromSelect(entity.select());
        if (arrayPath == null) return new FilterOutcome(data, true);
        JsonNode pruned = pruneArrayByAllowedIds(data, arrayPath, itemSelectorFrom(entity.select()), allowed);
        return new FilterOutcome(pruned, false);
    }

    private List<String> idsFrom(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return List.of();
        if (value.isTextual()) return List.of(value.asText());
        if (!value.isArray()) return List.of();
        List<String> ids = new ArrayList<>();
        for (JsonNode item : value) {
            if (item != null && item.isTextual()) ids.add(item.asText());
        }
        return ids;
    }

    /**
     * A produced-entity selector, in CEL dialect, has the canonical shape
     * {@code output.<arrayPath>.map(<var>, <var>.<idField>)} (CEL's projection equivalent of
     * JMESPath's {@code arrayPath[].idField}). Group 1 is the array path relative to {@code output};
     * group 3 is the per-item id field. Back-reference {@code \2} pins the lambda variable so only a
     * well-formed one-hop projection matches; anything else leaves the array un-prunable (fail-safe).
     */
    private static final java.util.regex.Pattern ENTITY_MAP_SELECT =
            java.util.regex.Pattern.compile("^output\\.([\\w.]+)\\.map\\(\\s*(\\w+)\\s*,\\s*\\2\\.([\\w.]+)\\s*\\)$");

    private String arrayPathFromSelect(String select) {
        if (select == null) return null;
        Matcher m = ENTITY_MAP_SELECT.matcher(select.trim());
        return m.matches() ? m.group(1) : null;
    }

    private String itemSelectorFrom(String select) {
        if (select == null) return null;
        Matcher m = ENTITY_MAP_SELECT.matcher(select.trim());
        return m.matches() ? m.group(3) : null;
    }

    private JsonNode pruneArrayByAllowedIds(JsonNode data, String arrayPath, String idField, Set<String> allowed) {
        JsonNode copy = data.deepCopy();
        JsonNode parent = copy;
        String[] segments = arrayPath.split("\\.");
        for (int i = 0; i < segments.length - 1; i++) {
            parent = parent.path(segments[i]);
        }
        String field = segments[segments.length - 1];
        if (!(parent instanceof ObjectNode objectParent)) return copy;
        JsonNode array = objectParent.path(field);
        if (!array.isArray()) return copy;
        // The per-item id selector is evaluated against RootVar.ITEM (each element bound to `item`).
        CompiledExpr itemIdSelector;
        try {
            itemIdSelector = evalEngine.compile("item." + idField, RootVar.ITEM);
        } catch (RuntimeException e) {
            return copy;   // un-prunable selector → fail-safe, leave the array intact
        }
        ArrayNode filtered = MAPPER.createArrayNode();
        for (JsonNode item : array) {
            try {
                JsonNode id = evalEngine.eval(itemIdSelector, item, EvalEngine.Mode.LENIENT);
                if (id != null && id.isTextual() && allowed.contains(id.asText())) {
                    filtered.add(item);
                }
            } catch (RuntimeException ignored) {
                // Invalid item projection was boot-validated for normal manifests; skip unsafe rows.
            }
        }
        objectParent.set(field, filtered);
        return copy;
    }

    private void publishCoverageDeny(CoverageContext ctx, PlanNode node, String entityId, String reason,
                                     String requestId, String conversationId) {
        if (tracePublisher == null || requestId == null || conversationId == null || ctx == null) return;
        tracePublisher.publish(TraceEvent.of("check_denied", requestId, conversationId,
                new CheckDeniedData("coverage", entityId, ctx.principalId(), reason, "coverage")));
    }

    private NodeResult coverageDenied(PlanNode node, String reason) {
        return new NodeResult(node.nodeId(), node.agent().agentId(), node.agent().protocol(),
                NodeResult.Status.FAILED, null, 0, reason);
    }

    /**
     * Synthetic FAILED result for a required entity-ref that never bound to a resolved value
     * (blank/absent). Distinct from {@link #coverageDenied}: it publishes NO coverage-deny event and
     * carries a neutral bind-failure message, so an unresolved reference degrades as a MISSING gap
     * (the flat-fallback / partial-failure path — dependents skip, synthesis states what's missing)
     * rather than being rendered as a coverage access verdict ("outside your access"). This is the
     * fix for a wrong-type entity (e.g. an instrument name) inside an entitled portfolio.
     */
    private NodeResult bindFailure(PlanNode node) {
        return new NodeResult(node.nodeId(), node.agent().agentId(), node.agent().protocol(),
                NodeResult.Status.FAILED, null, 0,
                "bind failure: required entity reference did not resolve to a value");
    }

    public record CoverageContext(
            String principalId,
            String tenantId,
            String bearerToken,
            Function<AgentManifest, EffectiveManifest> effectiveManifestProvider,
            CoverageChecker checker) {
        EffectiveManifest effectiveManifest(AgentManifest manifest) {
            return effectiveManifestProvider == null ? null : effectiveManifestProvider.apply(manifest);
        }
        CoverageCheckResult check(String entityId, DomainManifest.Coverage coverage) {
            return checker.check(principalId, tenantId, entityId, coverage, bearerToken);
        }
    }

    @FunctionalInterface
    public interface CoverageChecker {
        CoverageCheckResult check(String principalId, String tenantId, String entityId,
                                  DomainManifest.Coverage coverage, String bearerToken);
    }

    private record FilterOutcome(JsonNode data, boolean deniedScalar) {}

    private record MapItemOutcome(int index, boolean ok, ObjectNode summary) {
        static MapItemOutcome from(int index, NodeResult result) {
            boolean ok = result.isOk() && !isToolErrorPayload(result.data());
            ObjectNode summary = MAPPER.createObjectNode();
            summary.put("index", index);
            summary.put("status", ok ? "ok" : "failed");
            summary.put("latency_ms", result.latencyMs());
            if (ok) {
                summary.set("data", result.data());
            } else {
                String error = result.errorMessage();
                if (error == null && result.data() != null) {
                    JsonNode text = result.data().path("text");
                    error = text.isTextual() ? text.asText() : result.data().toString();
                }
                summary.put("error", error == null ? result.status().name() : error);
            }
            return new MapItemOutcome(index, ok, summary);
        }

        static MapItemOutcome failed(int index, String error) {
            ObjectNode summary = MAPPER.createObjectNode();
            summary.put("index", index);
            summary.put("status", "failed");
            summary.put("error", error == null ? "map item failed" : error);
            return new MapItemOutcome(index, false, summary);
        }

        private static boolean isToolErrorPayload(JsonNode data) {
            if (data == null || data.isNull() || data.isMissingNode()) return false;
            JsonNode error = data.path("error");
            if (!error.isMissingNode() && !error.isNull()) return true;
            if (data.has("error_code") || data.has("errorCode")) return true;
            JsonNode text = data.path("text");
            return text.isTextual() && text.asText("").startsWith("Error executing tool");
        }
    }
}
