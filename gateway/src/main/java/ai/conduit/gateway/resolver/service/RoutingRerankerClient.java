package ai.conduit.gateway.resolver.service;

import ai.conduit.gateway.registry.model.RoutingCandidate;

import java.util.List;

/**
 * Second-pass selector for ambiguous embedding results.
 */
public interface RoutingRerankerClient {

    Decision rerank(String queryText, List<RoutingCandidate> candidates) throws Exception;

    record Decision(String candidateId, boolean abstain, String reason) {
        public static Decision pick(String candidateId, String reason) {
            return new Decision(candidateId, false, reason);
        }

        public static Decision abstain(String reason) {
            return new Decision(null, true, reason);
        }
    }
}
