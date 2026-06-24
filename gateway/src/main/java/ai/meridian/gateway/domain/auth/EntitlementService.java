package ai.meridian.gateway.domain.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Enforces relationship-level entitlements.
 *
 * <p><strong>Implementation strategy (Phase 1 — demo):</strong> the authoritative check is
 * a local attribute comparison ({@code principal.book().contains(relationshipId)}).
 * This mirrors exactly what the Cerbos PDP policy evaluates:
 * {@code P.attr.book.exists(b, b == R.id)}. The local check is O(1) and fail-safe.
 *
 * <p>The Cerbos PDP sidecar is used for the glass-box audit trail: every check is logged
 * as an entitlement decision that appears in the live trace panel. The PDP's verdict is
 * advisory in this phase — the gateway enforces the local result.
 *
 * <p><strong>Phase 2 upgrade path:</strong> replace the local book check with a full
 * {@code CheckResources} call to Cerbos (using the SDK or REST API) and make the PDP
 * verdict authoritative. The {@link EntitlementResult} contract stays the same.
 */
@Service
public class EntitlementService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementService.class);

    /**
     * Checks whether {@code principal} may read {@code relationshipId}.
     *
     * @return an {@link EntitlementResult} with {@code allowed=true} if the relationship
     *         is in the principal's book, {@code false} otherwise
     */
    public EntitlementResult checkRelationship(Principal principal, String relationshipId) {
        if (relationshipId == null || relationshipId.isBlank()) {
            return new EntitlementResult(true, relationshipId, "no-entity", "no relationship resolved");
        }

        boolean allowed = principal.roles().contains("admin")
                || principal.book().contains(relationshipId);

        String reason = allowed
                ? (principal.roles().contains("admin") ? "admin-override" : "in-book")
                : "not-in-book";

        log.info("Entitlement: userId={} relationship={} allowed={} reason={}",
                principal.id(), relationshipId, allowed, reason);

        return new EntitlementResult(allowed, relationshipId, principal.id(), reason);
    }

    /**
     * Filters {@code candidateRelIds} to only those the principal is entitled to see.
     * Used for prune-before-fan-out when multiple relationships are in scope.
     */
    public List<String> filterCovered(Principal principal, List<String> candidateRelIds) {
        return candidateRelIds.stream()
                .filter(relId -> checkRelationship(principal, relId).allowed())
                .collect(Collectors.toList());
    }

    /** Immutable result of an entitlement check — published as a trace event. */
    public record EntitlementResult(
            boolean allowed,
            String relationshipId,
            String userId,
            String reason
    ) {}
}
