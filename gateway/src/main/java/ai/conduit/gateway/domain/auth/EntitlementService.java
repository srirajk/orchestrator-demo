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
        }
        return allowed;
    }

    /** Immutable result of an entitlement check — published as a trace event. */
    public record EntitlementResult(
            boolean allowed,
            String  relationshipId,
            String  userId,
            String  reason,
            String  source   // "cerbos" | "local-fallback"
    ) {}

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
