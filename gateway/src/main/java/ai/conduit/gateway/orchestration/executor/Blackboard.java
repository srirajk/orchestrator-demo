package ai.conduit.gateway.orchestration.executor;

import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
 * <p><b>World B:</b> every symbol reasoned over here — produced type, produced name, entity key — comes
 * from the manifests at runtime and is matched by equality only. This class embeds no domain vocabulary.
 *
 * <p>Thread-safe: {@link #project} is called from parallel node-completion threads within a layer;
 * the produced maps are {@link ConcurrentHashMap}s. {@link #bind} is called before a node is released.
 */
public final class Blackboard {

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
        this.availableEntities = availableEntities == null ? Set.of() : Set.copyOf(availableEntities);
        this.preBoundInputs = preBoundInputs == null ? Map.of() : Map.copyOf(preBoundInputs);
        this.mapper = mapper;
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
            // Single required producer → pass its output straight through as the consumer's input.
            return producedByType.get(resolvedFrom.get(0).from());
        }

        // Fan-in from several producers → merge, keyed by each producer's declared output name.
        ObjectNode merged = mapper.createObjectNode();
        for (AgentManifest.Consume c : resolvedFrom) {
            String type = c.from();
            String key = nameByType.getOrDefault(type, type);
            merged.set(key, producedByType.get(type));
        }
        return merged;
    }
}
