package ai.conduit.gateway.infrastructure.telemetry.event;

public record RequestCompleteData(long totalMs, int agentCount, int successCount) {}
