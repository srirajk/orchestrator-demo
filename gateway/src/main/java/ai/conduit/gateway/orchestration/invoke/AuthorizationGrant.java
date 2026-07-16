package ai.conduit.gateway.orchestration.invoke;

/**
 * A short-lived authorization decision, minted by the gateway when it has already decided —
 * structurally (Cerbos {@code filterAgents}) or with a resource-scoped coverage check — that a
 * principal may invoke an agent for this request. The {@link GovernedInvoker} authorize phase
 * VERIFIES a fresh matching grant before dispatching; no grant ⇒ fail-closed DENY.
 *
 * <p>A grant is intentionally cheap to verify (in-memory equality on
 * {@code principalId + agentId + resourceId}) so the green path costs zero extra PDP round-trips —
 * the verdict was already computed upstream, and the grant merely carries it to the checkpoint.
 *
 * @param principalId    the caller the verdict was decided for
 * @param agentId        the agent the verdict permits
 * @param resourceId     the resource the verdict is scoped to; {@code null} for a structural-only
 *                       grant (no per-entity coverage binding)
 * @param gateName       which gate minted this grant ({@code "structural"} | {@code "coverage"})
 * @param source         the decision source that produced it ({@code "cerbos"} | {@code "coverage"})
 * @param requestId      the request the grant belongs to (grants never cross requests)
 * @param decidedAtMillis wall-clock time the verdict was decided, for per-request TTL freshness
 */
public record AuthorizationGrant(
        String principalId,
        String agentId,
        String resourceId,
        String gateName,
        String source,
        String requestId,
        long decidedAtMillis) {

    public static final String GATE_STRUCTURAL = "structural";
    public static final String GATE_COVERAGE   = "coverage";

    /** A structural (no resource binding) grant, decided now. */
    public static AuthorizationGrant structural(String principalId, String agentId,
                                                String source, String requestId) {
        return new AuthorizationGrant(principalId, agentId, null, GATE_STRUCTURAL, source,
                requestId, System.currentTimeMillis());
    }

    /** A resource-scoped grant (bound to a coverage entity id), decided now. */
    public static AuthorizationGrant resourceScoped(String principalId, String agentId,
                                                    String resourceId, String source, String requestId) {
        return new AuthorizationGrant(principalId, agentId, resourceId, GATE_COVERAGE, source,
                requestId, System.currentTimeMillis());
    }

    /**
     * True when this grant covers ({@code principalId}, {@code agentId}) and its resource scope is
     * compatible with {@code boundResourceId}: a structural grant ({@code resourceId == null}) covers
     * any hop of that agent; a resource-scoped grant must match the bound resource exactly (the TOCTOU
     * closure — a hop bound to a different entity than the one the coverage check cleared is NOT
     * covered).
     */
    public boolean covers(String principalId, String agentId, String boundResourceId) {
        if (!java.util.Objects.equals(this.principalId, principalId)) return false;
        if (!java.util.Objects.equals(this.agentId, agentId)) return false;
        if (this.resourceId == null) return true;                 // structural — agent-wide
        return java.util.Objects.equals(this.resourceId, boundResourceId);
    }

    /** Fresh iff decided within {@code ttlMillis} of now (a stale grant is re-verified, never trusted). */
    public boolean isFresh(long nowMillis, long ttlMillis) {
        return (nowMillis - decidedAtMillis) <= ttlMillis;
    }
}
