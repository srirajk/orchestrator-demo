package ai.conduit.gateway.infrastructure.telemetry.event;

import java.util.List;

public record AgentsResolvedData(List<AgentRef> selected, List<FilteredRef> filtered) {
    public record AgentRef(String agentId, String protocol, double score) {}
    public record FilteredRef(String agentId, String reason) {}
}
