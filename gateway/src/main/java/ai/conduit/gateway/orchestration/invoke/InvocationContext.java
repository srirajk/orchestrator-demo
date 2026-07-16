package ai.conduit.gateway.orchestration.invoke;

import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-request authorization envelope carried on the {@link ai.conduit.gateway.orchestration.model.Plan}
 * and threaded into the {@link GovernedInvoker} for every agent hop. It carries only DATA — the
 * principal, the request identifiers, the caller's verified token, and the set of authorization
 * grants minted upstream (structural, from {@code filterAgents}) plus any resource-scoped grants the
 * pre-dispatch coverage gate mints. The authorizer is held by the {@code GovernedInvoker}, not here.
 *
 * <p><b>Fail-closed.</b> A hop is authorized only if a fresh matching grant is present. The empty
 * context ({@link #empty}) grants nothing, so an un-minted plan denies every hop — the desired
 * default for any path that forgets to mint.
 *
 * <p>The grant list and bound-resource map are concurrency-safe: the coverage gate mints
 * resource-scoped grants on the caller thread immediately before a layer fans out, while the
 * invoker reads them from the per-hop virtual thread.
 */
public final class InvocationContext {

    private final String principalId;
    private final String conversationId;
    private final String requestId;
    private final String callerToken;
    private final List<AuthorizationGrant> grants = new CopyOnWriteArrayList<>();
    private final Map<String, String> boundResources = new ConcurrentHashMap<>();

    private InvocationContext(String principalId, String conversationId, String requestId,
                              String callerToken, List<AuthorizationGrant> seed) {
        this.principalId = principalId;
        this.conversationId = conversationId;
        this.requestId = requestId;
        this.callerToken = callerToken;
        if (seed != null) this.grants.addAll(seed);
    }

    public static InvocationContext of(String principalId, String conversationId, String requestId,
                                       String callerToken, List<AuthorizationGrant> grants) {
        return new InvocationContext(principalId, conversationId, requestId, callerToken, grants);
    }

    /** An empty, deny-everything context (fail-closed). */
    public static InvocationContext empty(String principalId, String requestId) {
        return new InvocationContext(principalId, null, requestId, null, null);
    }

    /**
     * A test convenience: a context that mints a structural grant for every agent named by the plan's
     * nodes, so the fail-closed invoker admits exactly that plan. Never used on a production path — the
     * real path mints grants from actual authorization verdicts, not from the plan it is about to run.
     */
    public static InvocationContext grantingPlan(String principalId, String requestId, Plan plan) {
        InvocationContext ctx = empty(principalId, requestId);
        if (plan != null && plan.nodes() != null) {
            plan.nodes().forEach(n -> {
                AgentManifest a = n.agent();
                if (a != null) ctx.addGrant(AuthorizationGrant.structural(principalId, a.agentId(),
                        "test", requestId));
            });
        }
        return ctx;
    }

    public String principalId()    { return principalId; }
    public String conversationId() { return conversationId; }
    public String requestId()      { return requestId; }
    public String callerToken()    { return callerToken; }
    public List<AuthorizationGrant> grants() { return List.copyOf(grants); }

    /** Add a grant minted mid-flight (e.g. a resource-scoped grant from the coverage gate). */
    public void addGrant(AuthorizationGrant grant) {
        if (grant != null) grants.add(grant);
    }

    /** Record the resource id a node's input actually bound to, for the invoker's TOCTOU closure. */
    public void bindResource(String nodeId, String resourceId) {
        if (nodeId != null && resourceId != null) boundResources.put(baseNodeId(nodeId), resourceId);
    }

    /**
     * The resource id this node bound to, or {@code null} for a structural-only hop. Map-item nodes
     * ({@code parent[idx]}) inherit their envelope's binding — the coverage/grant decision is made
     * once on the envelope and every item verifies it.
     */
    public String boundResourceId(String nodeId) {
        if (nodeId == null) return null;
        return boundResources.get(baseNodeId(nodeId));
    }

    private static String baseNodeId(String nodeId) {
        int bracket = nodeId.indexOf('[');
        return bracket < 0 ? nodeId : nodeId.substring(0, bracket);
    }
}
