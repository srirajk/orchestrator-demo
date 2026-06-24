package ai.meridian.gateway.infrastructure.telemetry.event;

public record AgentCompleteData(String agentId, long durationMs, String status, String dataPreview) {}
