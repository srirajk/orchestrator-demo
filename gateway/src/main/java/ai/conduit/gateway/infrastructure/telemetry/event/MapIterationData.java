package ai.conduit.gateway.infrastructure.telemetry.event;

/**
 * Payload for a dynamic map node's aggregate execution summary.
 *
 * <p><b>World B:</b> carries only graph symbols, counts, caps, and the manifest-declared JMESPath.
 */
public record MapIterationData(
        String nodeId,
        String agentId,
        String over,
        int total,
        int ran,
        int ok,
        int failed,
        boolean truncated,
        int maxItems,
        int maxConcurrency
) {}
