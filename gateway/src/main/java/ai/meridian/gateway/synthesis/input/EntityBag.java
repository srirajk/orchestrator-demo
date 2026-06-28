package ai.meridian.gateway.synthesis.input;

import java.util.List;

public record EntityBag(
    String relationshipReference,
    String fundReference,
    List<String> tickerReferences,
    String period,
    String relationshipId,
    String fundId,
    boolean needsClarification,
    List<EntityCandidate> candidates
) {

    public record EntityCandidate(String entityId, String name) {}

    public static EntityBag extracted(String relationshipReference, String fundReference,
                                       List<String> tickerReferences, String period) {
        return new EntityBag(
            relationshipReference, fundReference,
            tickerReferences == null ? List.of() : List.copyOf(tickerReferences),
            period != null ? period : "QTD",
            null, null, false, List.of()
        );
    }

    public EntityBag withResolved(String relationshipId, String fundId, boolean needsClarification) {
        return new EntityBag(
            this.relationshipReference, this.fundReference, this.tickerReferences, this.period,
            relationshipId, fundId, needsClarification, List.of()
        );
    }

    public EntityBag withCandidates(List<EntityCandidate> candidates) {
        return new EntityBag(
            this.relationshipReference, this.fundReference, this.tickerReferences, this.period,
            null, null, true, candidates
        );
    }
}
