package ai.conduit.gateway.infrastructure.telemetry.event;

import java.util.List;

/**
 * Payload for the {@code plan_graph} trace event — the resolved multi-step execution graph, emitted
 * once after a DAG plan is built so the glass-box panel can render the producer→consumer topology.
 *
 * <p>Additive: only emitted on the (feature-flagged) DAG path. The flat fan-out path emits its
 * existing {@code agent_start}/{@code agent_complete} events unchanged and never publishes this.
 *
 * <p><b>World B:</b> carries only manifest-declared symbols (agent ids, protocol) and graph structure
 * ({@code dependsOn} edges); no domain vocabulary.
 *
 * @param nodes one entry per plan node, in topological order
 */
public record PlanGraphData(List<Node> nodes) {

    /**
     * @param nodeId    the plan node id (equals the agent id in the current single-capability-per-node model)
     * @param agentId   the manifest-declared agent id executing this node
     * @param protocol  the agent's wire protocol (http / mcp / …)
     * @param dependsOn ids of the nodes that must complete before this one (the incoming edges)
     * @param status    per-node status at publish time (e.g. {@code "planned"})
     */
    public record Node(String nodeId, String agentId, String protocol, List<String> dependsOn, String status) {}
}
