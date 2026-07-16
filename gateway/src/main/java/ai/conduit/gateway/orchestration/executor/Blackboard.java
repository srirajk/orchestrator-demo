package ai.conduit.gateway.orchestration.executor;

import ai.conduit.gateway.infrastructure.expression.CelEvalEngine;
import ai.conduit.gateway.infrastructure.expression.CompiledExpr;
import ai.conduit.gateway.infrastructure.expression.EvalEngine;
import ai.conduit.gateway.infrastructure.expression.RootVar;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-request working memory for multi-step ({@link ai.conduit.gateway.orchestration.model.Plan DAG})
 * execution. It holds two things:
 *
 * <ul>
 *   <li><b>Produced outputs</b> — as each node completes, its {@link ai.conduit.gateway.orchestration.model.NodeResult}
 *       data is <i>projected</i> into the board keyed by every {@code io.produces[].type} symbol the
 *       node declares. A downstream node whose {@code io.consumes[].from} names that type reads it back.</li>
 *   <li><b>Leaf inputs</b> — the pre-bound, entity-satisfied inputs for the graph's leaf nodes
 *       (today's flat-path inputs), keyed by {@code nodeId}. These are handed straight back so
 *       entity-only consumers behave exactly as they do on the flat path.</li>
 * </ul>
 *
 * <h2>Binding convention (what {@link #bind(PlanNode)} returns)</h2>
 * For a node about to execute, its wire input is derived as:
 * <ol>
 *   <li><b>No upstream {@code from} input available</b> (all consumes are entity refs, or none of the
 *       node's {@code from} types have been produced yet) → return the node's <i>pre-bound leaf input</i>
 *       (today's entity-bound input). Entity-only consumers are never disturbed.</li>
 *   <li><b>Exactly one {@code from} producer resolved</b> → <i>pass-through</i>: the producer's output
 *       JSON becomes this node's input verbatim. This is the flagship fan-in shape (one required
 *       producer feeding one consumer; any optional producers that didn't run are simply absent).</li>
 *   <li><b>More than one {@code from} producer resolved</b> → <i>merge</i>: build a single JSON object
 *       whose keys are the producers' declared {@code produces[].name} and whose values are their
 *       outputs.</li>
 * </ol>
 *
 * <h2>Per-edge projection ({@code select}) and the pre-dispatch gate</h2>
 * A {@code from} edge may declare an optional {@code select} — a CEL expression that reshapes
 * the producer's output into exactly what the consumer expects (field projection, not a blob
 * pass-through). No {@code select} = identity, i.e. exactly the behavior above, unchanged. Before a
 * bound node is released to the harness, {@link #checkComposable} runs a fail-safe gate: an upstream
 * output marked incomplete ({@code _complete:false}/{@code truncated:true}), or a bound input that
 * doesn't satisfy the consumer's introspected input schema, fails the node with a clear reason
 * instead of ever dispatching a malformed request — never a 422 to the user, never a wrong number.
 *
 * <p><b>World B:</b> every symbol reasoned over here — produced type, produced name, entity key,
 * CEL expression, schema keyword — comes from the manifests/introspection at runtime and is
 * matched/interpreted generically. This class embeds no domain vocabulary.
 *
 * <p>Thread-safe: {@link #project(PlanNode, JsonNode)} is called from parallel node-completion
 * threads within a layer; the produced maps are {@link ConcurrentHashMap}s. {@link #bind} and
 * {@link #checkComposable} are called from the caller thread before a node is released.
 */
public final class Blackboard {

    private static final Logger log = LoggerFactory.getLogger(Blackboard.class);

    /** Manifest-expression engine (CEL behind the {@link EvalEngine} seam) — stateless/thread-safe. */
    private final EvalEngine evalEngine;

    private final ObjectMapper mapper;

    /** produced type symbol → that producer node's output data. */
    private final Map<String, JsonNode> producedByType = new ConcurrentHashMap<>();
    /** produced type symbol → the producer's declared output name (for merge keys). */
    private final Map<String, String> nameByType = new ConcurrentHashMap<>();

    /** entity keys already available (resolved entities); read-only. */
    private final Set<String> availableEntities;
    /** nodeId → today's entity-bound leaf input; read-only. */
    private final Map<String, JsonNode> preBoundInputs;

    public Blackboard(Set<String> availableEntities,
                      Map<String, JsonNode> preBoundInputs,
                      ObjectMapper mapper) {
        this(availableEntities, preBoundInputs, mapper, new CelEvalEngine(mapper));
    }

    public Blackboard(Set<String> availableEntities,
                      Map<String, JsonNode> preBoundInputs,
                      ObjectMapper mapper,
                      EvalEngine evalEngine) {
        this.availableEntities = availableEntities == null ? Set.of() : Set.copyOf(availableEntities);
        this.preBoundInputs = preBoundInputs == null ? Map.of() : Map.copyOf(preBoundInputs);
        this.mapper = mapper;
        this.evalEngine = evalEngine;
    }

    /** Entity keys already available (resolved entities). */
    public Set<String> availableEntities() {
        return availableEntities;
    }

    /** True once some node has produced this type symbol. */
    public boolean hasType(String type) {
        return producedByType.containsKey(type);
    }

    /**
     * Project a completed node's output into the board under every type it declares to produce.
     * A {@code null} data payload (a failed/timed-out node) produces nothing.
     */
    public void project(PlanNode node, JsonNode data) {
        if (data == null || node == null) return;
        AgentManifest.Io io = node.agent().io();
        if (io == null || io.produces() == null) return;
        for (AgentManifest.Produce p : io.produces()) {
            if (p == null || p.type() == null) continue;
            producedByType.put(p.type(), data);
            if (p.name() != null) nameByType.put(p.type(), p.name());
        }
    }

    /**
     * Derive the wire input for {@code node} from the board, per the binding convention documented
     * on this class. Never throws; returns {@code null} only if nothing is available to bind (which
     * the resolver's required-input check should already have prevented for a valid plan).
     */
    public JsonNode bind(PlanNode node) {
        AgentManifest.Io io = node.agent().io();
        List<AgentManifest.Consume> consumes =
                (io == null || io.consumes() == null) ? List.of() : io.consumes();

        // Upstream produced inputs whose type has actually been produced (order preserved).
        List<AgentManifest.Consume> resolvedFrom = new ArrayList<>();
        for (AgentManifest.Consume c : consumes) {
            if (c != null && c.isProducedRef() && producedByType.containsKey(c.from())) {
                resolvedFrom.add(c);
            }
        }

        if (resolvedFrom.isEmpty()) {
            // Entity-only consumer (or upstream not yet produced) → keep today's entity-bound input.
            JsonNode pre = preBoundInputs.get(node.nodeId());
            return pre != null ? pre : node.input();
        }

        if (resolvedFrom.size() == 1) {
            // Single required producer → its (optionally projected) output becomes the consumer's
            // input. No `select` declared → identity pass-through, byte-for-byte today's behavior.
            AgentManifest.Consume c = resolvedFrom.get(0);
            return projectEdge(c, producedByType.get(c.from()));
        }

        // Fan-in from several producers → merge, keyed by each producer's declared output name.
        // Each edge is projected independently before merging (still identity when no `select`).
        ObjectNode merged = mapper.createObjectNode();
        for (AgentManifest.Consume c : resolvedFrom) {
            String type = c.from();
            String key = nameByType.getOrDefault(type, type);
            merged.set(key, projectEdge(c, producedByType.get(type)));
        }
        return merged;
    }

    /**
     * Apply the edge's declared {@code select} (a CEL expression rooted at {@code input}) to reshape
     * {@code raw} into exactly what the consumer expects. No {@code select} (or a null producer output)
     * → identity (same reference) — the default, backward-compatible path. A malformed/failed
     * projection is treated as "could not project" (returns {@code null}, which {@link #checkComposable}
     * then reports as a missing field) rather than throwing and taking the whole request down.
     */
    private JsonNode projectEdge(AgentManifest.Consume c, JsonNode raw) {
        if (raw == null || c == null || !c.hasSelect()) return raw;
        try {
            CompiledExpr compiled = evalEngine.compile(c.select(), RootVar.INPUT);
            JsonNode projected = evalEngine.eval(compiled, raw, EvalEngine.Mode.LENIENT);
            return (projected == null || projected.isMissingNode()) ? null : projected;
        } catch (RuntimeException e) {
            log.warn("edge projection failed for select='{}': {}", c.select(), e.getMessage());
            return null;
        }
    }

    /**
     * Fail-safe gate run before a node is dispatched (hard rule: never a wrong number, never a
     * fabricated/422 surface to the user). Checks, in order:
     * <ol>
     *   <li><b>Completeness contract</b> — any REQUIRED upstream {@code from} producer whose raw
     *       output is marked {@code _complete:false} or {@code truncated:true} fails this node,
     *       regardless of what schema validation would say. Generic: reads a manifest/output
     *       convention, not a domain literal.</li>
     *   <li><b>Input-schema validation</b> — only for nodes with at least one {@code from} edge
     *       (entity-only leaf consumers are never gated here, so their behavior is unchanged): the
     *       bound input must satisfy the consumer's introspected input schema per
     *       {@link InputContractValidator}.</li>
     * </ol>
     *
     * @return {@code null} if {@code node} may be dispatched as bound; otherwise a human-readable
     *         reason it cannot ("could not compose input for &lt;agent&gt;: ...").
     */
    public String checkComposable(PlanNode node, JsonNode boundInput) {
        AgentManifest.Io io = node.agent().io();
        List<AgentManifest.Consume> consumes =
                (io == null || io.consumes() == null) ? List.of() : io.consumes();

        boolean hasFromEdge = false;
        for (AgentManifest.Consume c : consumes) {
            if (c == null || !c.isProducedRef()) continue;
            hasFromEdge = true;
            if (!c.isRequired()) continue;
            JsonNode raw = producedByType.get(c.from());
            if (raw != null && isIncomplete(raw)) {
                return "could not compose input for " + node.agent().agentId() + ": upstream '"
                        + c.from() + "' output is incomplete (marked _complete=false/truncated=true)";
            }
        }

        if (!hasFromEdge || node.hasMap()) return null;   // entity-only/map carriers are checked elsewhere

        List<String> missing = InputContractValidator.missingFields(node.agent().inputSchema(), boundInput);
        if (!missing.isEmpty()) {
            return "could not compose input for " + node.agent().agentId()
                    + ": missing/mismatched field(s) " + missing;
        }
        return null;
    }

    private static boolean isIncomplete(JsonNode raw) {
        JsonNode complete = raw.path("_complete");
        if (complete.isBoolean() && !complete.asBoolean()) return true;
        JsonNode truncated = raw.path("truncated");
        return truncated.isBoolean() && truncated.asBoolean();
    }
}
