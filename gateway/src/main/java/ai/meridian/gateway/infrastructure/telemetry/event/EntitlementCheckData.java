package ai.meridian.gateway.infrastructure.telemetry.event;

public record EntitlementCheckData(
        String relationshipId,
        String userId,
        boolean allowed,
        String reason
) {}
