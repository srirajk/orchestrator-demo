package ai.conduit.gateway.infrastructure.telemetry.event;

public record NodeConditionData(
        String nodeId,
        String agentId,
        String expression,
        String verdict,
        String reason
) {}
