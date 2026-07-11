package ai.conduit.gateway.domain.chat;

import java.util.List;

/**
 * The read-only diagnostic view of ONE production pre-routing decision (routing spec V2 Piece 6).
 *
 * <p>{@link ChatService#decideRoute} runs the REAL shared preparation pipeline — {@code RoutePreparer
 * → resolveContextual → buildRequestedPlan → per-group structural authz} — and STOPS before any agent
 * is invoked, projecting the outcome into this record for the test-profile decision endpoint the
 * goal-pick harness gates on. It is <b>not</b> a forked copy of the routing logic: every field here is
 * captured from the same {@link RoutePreparer} bean, the same {@link
 * ai.conduit.gateway.resolver.service.AgentResolver}, and the same {@link
 * ai.conduit.gateway.domain.auth.EntitlementService} the chat request path uses, so
 * {@link #path()} is stamped {@code "production"} and the harness can assert it never drifted onto a
 * debug-only shortcut.
 *
 * <p><b>No raw entity values.</b> Grounded references are projected as their manifest {@code entityKey}
 * + generic status enums + sub-domain id + a machine-readable denial reason code — never the user's
 * verbatim text or a resolved canonical id. The one free-text field, {@link #maskedRoutingText}, is the
 * text actually handed to the resolver: resolved entity spans are already blanked to the neutral mask
 * deictic, so it carries the CALLER's own capability words, not a resolved subject. Callers must not log
 * it.
 *
 * <p><b>World-B.</b> Every field is an agent/domain/sub-domain id, a manifest entity key, a generic
 * enum name, a character count/offset, a score, a stable hash, or the caller's own (masked) words — no
 * domain literal, id pattern, or entity-type word is authored into this class.
 *
 * @param path               always {@code "production"} — a witness that the shared preparation ran.
 * @param preparationVersion the configured preparation/masking contract version (harness asserts it).
 * @param maskMode           the masking mode the preparation chose (e.g. {@code masked-base}); from
 *                           {@link PreparedRoute.MaskDiagnostics}.
 * @param maskedRoutingText  the exact routing text handed to the resolver (resolved spans blanked).
 * @param maskDiagnostics    the non-reversible masking fingerprint (counts, hashes) — no raw values.
 * @param relaxationAllowed  whether the resolver ran with the bias-to-fetch margin relaxation.
 * @param focalVerdict       the terminal focal grounding verdict name (drives coverage DENY/UNAVAILABLE).
 * @param grounded           per-mention grounded statuses (no raw text/id).
 * @param resolver           the resolver's decision projection (scores, margin, rerank, leader).
 * @param candidates         ALL routed candidates incl. below-floor, each with its score + selected flag.
 * @param requestedGroups    the requested-capability groups the plan partitioned.
 * @param disposition        per-group post-authorization disposition (structural authz only; no invoke).
 * @param overallDisposition the merged terminal disposition class for the whole request.
 */
public record RouteDecision(
        String path,
        String preparationVersion,
        String maskMode,
        String maskedRoutingText,
        PreparedRoute.MaskDiagnostics maskDiagnostics,
        boolean relaxationAllowed,
        String focalVerdict,
        List<GroundedStatus> grounded,
        ResolverDecision resolver,
        List<CandidateView> candidates,
        List<GroupView> requestedGroups,
        List<GroupDisposition> disposition,
        String overallDisposition) {

    public RouteDecision {
        grounded = grounded == null ? List.of() : List.copyOf(grounded);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        requestedGroups = requestedGroups == null ? List.of() : List.copyOf(requestedGroups);
        disposition = disposition == null ? List.of() : List.copyOf(disposition);
    }

    /** The {@link #path} value stamped on every production decision. */
    public static final String PRODUCTION_PATH = "production";

    /**
     * One grounded reference's status — projected WITHOUT its raw verbatim text or resolved id.
     *
     * @param entityKey    the manifest entity key the reference resolves into.
     * @param subDomainId  the owning sub-domain id (or {@code null}).
     * @param status       the grounding status enum name ({@code RESOLVED_ALLOWED}/{@code DENIED}/…).
     * @param mentionKind  {@code EXPLICIT} vs {@code ANAPHORA} provenance.
     * @param aligned      whether a deterministic span was computed (false ⇒ masking incomplete).
     * @param denialReason machine-readable reason code on a denied status, else {@code null}.
     * @param focal        whether this is the focal mention (drives {@link #focalVerdict}).
     */
    public record GroundedStatus(
            String entityKey,
            String subDomainId,
            String status,
            String mentionKind,
            boolean aligned,
            String denialReason,
            boolean focal) {}

    /**
     * The resolver's decision, as scores + the diagnostic leader (never the fulfilment contract).
     *
     * @param fallback         whether routing abstained (no confident selection).
     * @param topScore         the leader's similarity score.
     * @param margin           the leader-vs-runner-up (or leader-vs-other-domain) margin the resolver kept.
     * @param rerankFired      whether the bounded LLM reranker ran.
     * @param rerankSelectedIds the reranker's explicit multi-facet shortlist (empty on every other path).
     * @param primaryAgentId   the post-rerank leader agent id (diagnostic), or {@code null}.
     * @param primarySubDomain the leader's sub-domain id, or {@code null}.
     * @param primaryDomain    the leader's domain id, or {@code null}.
     */
    public record ResolverDecision(
            boolean fallback,
            double topScore,
            double margin,
            boolean rerankFired,
            List<String> rerankSelectedIds,
            String primaryAgentId,
            String primarySubDomain,
            String primaryDomain) {

        public ResolverDecision {
            rerankSelectedIds = rerankSelectedIds == null ? List.of() : List.copyOf(rerankSelectedIds);
        }
    }

    /**
     * One routed candidate — surfaced for the FULL score vector the re-baseline needs, including the
     * below-floor tail ({@code selected == false}) the plan never groups.
     *
     * @param agentId   the candidate agent id.
     * @param domain    the candidate's domain id.
     * @param subDomain the candidate's sub-domain id (or {@code null}).
     * @param protocol  the candidate's wire protocol.
     * @param score     the embedding similarity score.
     * @param selected  whether it cleared the confidence floor (in {@code resolved.selected()}).
     */
    public record CandidateView(
            String agentId,
            String domain,
            String subDomain,
            String protocol,
            double score,
            boolean selected) {}

    /**
     * One requested-capability group as the plan partitioned it.
     *
     * @param requestedCapabilityIds the agent ids this group fulfils.
     * @param kind                   {@code FLAT} or {@code DAG}.
     * @param goalId                 the DAG fan-in goal agent id, or {@code null}.
     * @param requiredEntityKeys     manifest-declared required entity keys across the group.
     * @param routingEvidence        why the group was formed (e.g. {@code rerank-facet}).
     */
    public record GroupView(
            List<String> requestedCapabilityIds,
            String kind,
            String goalId,
            List<String> requiredEntityKeys,
            String routingEvidence) {

        public GroupView {
            requestedCapabilityIds = requestedCapabilityIds == null ? List.of() : List.copyOf(requestedCapabilityIds);
            requiredEntityKeys = requiredEntityKeys == null ? List.of() : List.copyOf(requiredEntityKeys);
        }
    }

    /**
     * One group's post-authorization disposition — the structural (Cerbos) verdict per requested
     * capability, computed WITHOUT invoking any agent (the endpoint stops before execution).
     *
     * @param requestedCapabilityIds the group's requested agent ids.
     * @param allowedCapabilityIds    the subset the principal is structurally entitled to invoke.
     * @param deniedCapabilityIds     the structurally-denied subset.
     * @param disposition             {@code SERVED} / {@code PARTIAL} / {@code DENIED}.
     */
    public record GroupDisposition(
            List<String> requestedCapabilityIds,
            List<String> allowedCapabilityIds,
            List<String> deniedCapabilityIds,
            String disposition) {

        public GroupDisposition {
            requestedCapabilityIds = requestedCapabilityIds == null ? List.of() : List.copyOf(requestedCapabilityIds);
            allowedCapabilityIds = allowedCapabilityIds == null ? List.of() : List.copyOf(allowedCapabilityIds);
            deniedCapabilityIds = deniedCapabilityIds == null ? List.of() : List.copyOf(deniedCapabilityIds);
        }
    }
}
