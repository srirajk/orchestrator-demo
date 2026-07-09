package ai.conduit.gateway.orchestration.model;

import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A single node in an execution {@link Plan}.
 *
 * <p>For the Phase 4 flat fan-out, {@code dependsOn} is always empty — every node
 * is independent and can be executed in parallel.  The field is present so the model
 * can support sequential dependencies in a later phase without a schema change.
 */
public record PlanNode(
        String nodeId,
        AgentManifest agent,
        JsonNode input,
        List<String> dependsOn,  // empty for flat fan-out (Phase 4)
        String condition
) {
    public PlanNode(String nodeId, AgentManifest agent, JsonNode input, List<String> dependsOn) {
        this(nodeId, agent, input, dependsOn, conditionOf(agent));
    }

    private static String conditionOf(AgentManifest agent) {
        AgentManifest.Io io = agent == null ? null : agent.io();
        return io != null && io.hasCondition() ? io.condition() : null;
    }

    public boolean hasCondition() {
        return condition != null && !condition.isBlank();
    }

    public boolean hasMap() {
        AgentManifest.Io io = agent == null ? null : agent.io();
        return io != null && io.hasMap();
    }
}
