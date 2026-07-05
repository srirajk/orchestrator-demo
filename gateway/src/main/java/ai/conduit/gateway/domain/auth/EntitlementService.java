package ai.conduit.gateway.domain.auth;

import ai.conduit.gateway.registry.model.AgentManifest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Enforces relationship-level entitlements by delegating to the Cerbos PDP.
 *
 * <p><strong>Phase 11:</strong> The Cerbos PDP verdict is the authoritative answer.
 * {@link CerbosEntitlementAdapter} issues a single batched {@code CheckResources} call
 * for all candidate relationships — {@link #filterCovered(Principal, List)} never makes
 * N round-trips.
 *
 * <p>Fail-mode (open or closed) is configured on the adapter. The {@link EntitlementResult}
 * carries a {@code source} field ("cerbos" | "local-fallback") so the glass-box and audit
 * trail show where the verdict came from.
 */
@Service
public class EntitlementService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementService.class);

    private final CerbosEntitlementAdapter cerbos;
    private final MeterRegistry meterRegistry;
    private final RevocationChecker revocationChecker;

    /** Cerbos resource kind — domain-declared via config; must match conduit.authz.resource-type. */
    @Value("${conduit.authz.resource-type:relationship}")
    private String resourceType;

    public EntitlementService(CerbosEntitlementAdapter cerbos, MeterRegistry meterRegistry,
                              RevocationChecker revocationChecker) {
        this.cerbos = cerbos;
        this.meterRegistry = meterRegistry;
        this.revocationChecker = revocationChecker;
    }

    /**
     * Checks whether {@code principal} may read {@code relationshipId} according to the PDP.
     */
    public EntitlementResult checkRelationship(Principal principal, String relationshipId) {
        if (relationshipId == null || relationshipId.isBlank()) {
            return new EntitlementResult(true, relationshipId, principal.id(), "no-entity", "cerbos");
        }

        // Revocation overlay: check Redis before trusting any cached Cerbos verdict
        if (revocationChecker.isRevoked(principal.id(), relationshipId)) {
            log.info("Entitlement overridden by revocation: userId={} relationship={}", principal.id(), relationshipId);
            emitAuthzDecision("DENY", resourceType, "revocation-override");
            return new EntitlementResult(false, relationshipId, principal.id(), "revoked", "revocation-override");
        }

        // Structural + data-aware check via Cerbos PDP
        CerbosEntitlementAdapter.BatchResult batch =
                cerbos.checkRelationships(principal, List.of(relationshipId));

        boolean allowed = batch.isAllowed(relationshipId);
        String reason = allowed ? "allowed-by-pdp" : "denied-by-pdp";

        log.info("Entitlement: userId={} relationship={} allowed={} source={} reason={}",
                principal.id(), relationshipId, allowed, batch.source(), reason);

        emitAuthzDecision(allowed ? "ALLOW" : "DENY", resourceType, batch.source());
        return new EntitlementResult(allowed, relationshipId, principal.id(), reason, batch.source());
    }

    /**
     * Filters {@code candidateRelIds} to those the principal is entitled to see.
     *
     * <p><strong>One PDP call</strong> for all candidates — never N round-trips.
     */
    public List<String> filterCovered(Principal principal, List<String> candidateRelIds) {
        if (candidateRelIds == null || candidateRelIds.isEmpty()) return List.of();

        CerbosEntitlementAdapter.BatchResult batch =
                cerbos.checkRelationships(principal, candidateRelIds);

        List<String> allowed = candidateRelIds.stream()
                .filter(batch::isAllowed)
                .collect(Collectors.toList());

        // Emit one counter per candidate relationship result
        candidateRelIds.forEach(relId ->
                emitAuthzDecision(batch.isAllowed(relId) ? "ALLOW" : "DENY", resourceType, batch.source()));

        log.debug("filterCovered: principal={} candidates={} allowed={} source={}",
                principal.id(), candidateRelIds.size(), allowed.size(), batch.source());
        return allowed;
    }

    /**
     * Filters {@code manifests} to those the principal is entitled to invoke.
     *
     * <p><strong>One PDP call</strong> for all candidate agents — no N round-trips.
     * Reasons for filtering: domain mismatch, data-classification clearance, or mutating agent.
     */
    public List<AgentManifest> filterAgents(Principal principal, List<AgentManifest> manifests) {
        if (manifests == null || manifests.isEmpty()) return List.of();

        CerbosEntitlementAdapter.BatchResult batch = cerbos.checkAgents(principal, manifests);

        List<AgentManifest> allowed = manifests.stream()
                .filter(m -> batch.isAllowed(m.agentId()))
                .collect(Collectors.toList());

        // Emit one counter per candidate agent result
        manifests.forEach(m ->
                emitAuthzDecision(batch.isAllowed(m.agentId()) ? "ALLOW" : "DENY", "agent", batch.source()));

        log.debug("filterAgents: principal={} candidates={} allowed={} source={}",
                principal.id(), manifests.size(), allowed.size(), batch.source());

        if (allowed.size() < manifests.size()) {
            List<String> denied = manifests.stream()
                    .map(AgentManifest::agentId)
                    .filter(id -> !batch.isAllowed(id))
                    .collect(Collectors.toList());
            log.info("Agent access denied by PDP: principal={} denied={}", principal.id(), denied);
            // Per-agent denial dimension (Insights "denials by agent"). agentId is a manifest-
            // declared identifier read off the candidate list — no hardcoded domain literal.
            denied.forEach(this::emitAgentDenial);
        }
        return allowed;
    }

    /**
     * Produces the ordered, per-agent breakdown of the structural authorization gates for the
     * glass box: {@code audience → segment → classification}. This EXPLAINS the decision the
     * Cerbos PDP enforces (AUTHZ-SPEC §1) so the trace shows each gate's pass/deny + a plain-english
     * reason instead of one opaque allow/deny.
     *
     * <p><strong>The verdicts come from Cerbos, not from re-deciding here.</strong> The gateway
     * carries zero domain knowledge (World B): the domain→segment mapping and the classification
     * ladder live in the Cerbos policy. Two batched PDP verdicts label each agent:
     * <ul>
     *   <li>{@code invoke_membership} — segment membership only (ignores tier)</li>
     *   <li>{@code invoke}            — the full, enforced structural decision</li>
     * </ul>
     * from which the gate that decided is inferred:
     * <ol>
     *   <li><b>audience</b> — {@code enterprise} agents (manifest-declared) are open to all; no
     *       segment/classification gate applies.</li>
     *   <li><b>segment</b> — {@code invoke_membership} deny ⇒ the agent's domain maps to no segment
     *       the principal holds.</li>
     *   <li><b>classification</b> — member but {@code invoke} deny ⇒ the principal's tier in that
     *       segment is below the agent's declared {@code data_classification}.</li>
     * </ol>
     *
     * <p>Reason copy is built from data the manifest/principal declare (the agent's domain and
     * required classification, the tiers the principal holds) — never a hardcoded domain literal or
     * the domain→segment mapping, so this stays World-B clean. The coverage gate is entity-scoped
     * and lives in the request pipeline; it is not evaluated here.
     */
    public List<GateResult> explainStructuralGates(Principal principal, List<AgentManifest> manifests) {
        if (manifests == null || manifests.isEmpty()) return List.of();

        Map<String, Boolean> member = cerbos.checkAgentMembership(principal, manifests);
        CerbosEntitlementAdapter.BatchResult invoke = cerbos.checkAgents(principal, manifests);

        List<GateResult> out = new java.util.ArrayList<>();
        for (AgentManifest m : manifests) {
            String agent    = m.agentId();
            String domain   = m.domain();
            String audience = m.audience() != null ? m.audience() : "segment";
            String required = (m.constraints() != null && m.constraints().dataClassification() != null)
                    ? m.constraints().dataClassification() : "confidential";

            if ("enterprise".equals(audience)) {
                out.add(new GateResult("audience", true,
                        "enterprise agent — available to all users", agent));
                continue;
            }

            boolean isMember  = member.getOrDefault(agent, false);
            boolean canInvoke = invoke.isAllowed(agent);

            if (!isMember) {
                out.add(new GateResult("segment", false, "not in segment for " + domain, agent));
                continue;
            }
            out.add(new GateResult("segment", true, "in segment for " + domain, agent));

            if (!canInvoke) {
                out.add(new GateResult("classification", false,
                        domain + " requires " + required + "; you hold " + heldTiers(principal), agent));
            } else {
                out.add(new GateResult("classification", true,
                        "clearance meets " + required, agent));
            }
        }
        return out;
    }

    /** Renders the principal's per-segment tiers for a legible classification reason, e.g. "wealth=confidential". */
    private static String heldTiers(Principal principal) {
        if (principal.segments() == null || principal.segments().isEmpty()) return "no segment clearances";
        return principal.segments().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }

    /** One gate's verdict for one agent, for the glass-box authorization trace. */
    public record GateResult(String gate, boolean allow, String reason, String agent) {}

    /**
     * Whether an agent is entity-scoped and therefore subject to the coverage (book-of-business)
     * gate. Only {@code segment}-audience agents carry an entity; {@code enterprise} agents are
     * open knowledge services (e.g. HR policy Q&A) with no per-user entity to check, so the
     * DISCOVER/RESOLVE/CHECK coverage pipeline is skipped for them.
     *
     * <p>Audience is manifest-declared — no domain knowledge in the gateway (World B).
     */
    public boolean requiresCoverage(AgentManifest manifest) {
        if (manifest == null) return true;
        return !"enterprise".equals(manifest.audience());
    }

    /** Immutable result of an entitlement check — published as a trace event. */
    public record EntitlementResult(
            boolean allowed,
            String  relationshipId,
            String  userId,
            String  reason,
            String  source   // "cerbos" | "local-fallback"
    ) {}

    /** Per-agent denial counter for the Insights governance board. Micrometer caches by key. */
    private void emitAgentDenial(String agentId) {
        Counter.builder("conduit.agent.denials")
                .description("Agent invocations denied by the PDP, per agent")
                .tag("agentId", agentId)
                .register(meterRegistry)
                .increment();
    }

    /** Emits one authorization decision counter increment. Micrometer caches the Counter by key. */
    private void emitAuthzDecision(String decision, String resourceType, String source) {
        Counter.builder("conduit.authz.decisions")
                .description("Authorization decisions by outcome and source")
                .tag("decision", decision)
                .tag("resource_type", resourceType)
                .tag("source", source)
                .register(meterRegistry)
                .increment();
    }
}
