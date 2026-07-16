package ai.conduit.gateway.infrastructure.telemetry.event;

/**
 * Trace/audit payload emitted by the {@code GovernedInvoker} for EVERY governed agent hop — one per
 * harness call, including denied and timed-out hops (a map.over of N items emits N of these). It is
 * the authz-verdict audit record for the invocation checkpoint: it names the agent, the checkpoint
 * verdict ({@code ALLOWED} | {@code DENIED}), the terminal {@link ai.conduit.gateway.orchestration.model.NodeResult.Status},
 * and the decision source — never any domain value (World B).
 *
 * @param nodeId        the plan node (or {@code parent[idx]} map item) this hop ran
 * @param agentId       the manifest-declared agent id
 * @param verdict       {@code "ALLOWED"} or {@code "DENIED"} — the checkpoint's authorize decision
 * @param status        the terminal {@code NodeResult.Status} name (OK/DENIED/TIMEOUT/…)
 * @param source        the authorization decision source ({@code "grant"} | {@code "cerbos"} | …)
 * @param reason        machine-readable deny reason when denied, else {@code null}
 * @param resourceScoped whether the authorizing grant was bound to a resource id
 */
public record AgentInvocationData(
        String  nodeId,
        String  agentId,
        String  verdict,
        String  status,
        String  source,
        String  reason,
        boolean resourceScoped) {

    public static final String VERDICT_ALLOWED = "ALLOWED";
    public static final String VERDICT_DENIED  = "DENIED";
}
