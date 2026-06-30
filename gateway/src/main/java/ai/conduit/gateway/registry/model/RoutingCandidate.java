package ai.conduit.gateway.registry.model;

/**
 * Result of a vector search hit — one candidate agent with its similarity score.
 */
public record RoutingCandidate(
        AgentManifest manifest,
        double score
) {}
