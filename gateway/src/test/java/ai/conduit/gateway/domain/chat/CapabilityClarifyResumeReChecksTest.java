package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.domain.auth.CerbosEntitlementAdapter;
import ai.conduit.gateway.domain.clarify.ClarificationDescriptor;
import ai.conduit.gateway.domain.clarify.ClarificationDescriptorStore;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.RoutingCandidate;
import ai.conduit.gateway.resolver.model.ResolverResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * The resume-re-CHECK test. Selecting a capability from a {@code clarify_capability} form is a ROUTE HINT,
 * never authorization: the resume re-drives the FULL pipeline and the entitlement CHECK still fires. Here the
 * principal's access to the chosen capability is REVOKED between the offer turn and the resume turn (Cerbos
 * flips to deny) — the resumed turn must DENY, not serve the capability on the strength of the form. This is
 * the TOCTOU-dissolving contract: the form is not trusted as a grant.
 */
class CapabilityClarifyResumeReChecksTest extends CapabilityClarifyFixture {

    @Autowired ClarificationDescriptorStore descriptorStore;

    @Test
    void selectingACapability_reDrivesPipeline_andDeniedCapabilityIsDeniedAtResume() throws Exception {
        String convId = "conv-cap-resume-1";

        // Cerbos allows at OFFER time, denies at RESUME time (access revoked between the two turns).
        AtomicBoolean allow = new AtomicBoolean(true);
        when(cerbosAdapter.checkAgents(any(), any())).thenAnswer(inv -> {
            List<AgentManifest> ms = inv.getArgument(1);
            Map<String, Boolean> decisions = new HashMap<>();
            if (ms != null) ms.forEach(m -> decisions.put(m.agentId(), allow.get()));
            return new CerbosEntitlementAdapter.BatchResult(decisions, "cerbos");
        });
        // S1c: mirror the stub for the ctx-aware overload the PRIMARY filterAgents gate now calls
        // (same revocable verdict via the shared `allow` flag, so the resume still re-CHECKs and denies).
        when(cerbosAdapter.checkAgents(any(), any(), any())).thenAnswer(inv -> {
            List<AgentManifest> ms = inv.getArgument(1);
            Map<String, Boolean> decisions = new HashMap<>();
            if (ms != null) ms.forEach(m -> decisions.put(m.agentId(), allow.get()));
            return new CerbosEntitlementAdapter.BatchResult(decisions, "cerbos");
        });
        // Routing abstains (low margin) with the two plausible capabilities — on BOTH turns.
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(new ResolverResult(
                List.of(),
                List.of(new RoutingCandidate(POLICY, 0.44), new RoutingCandidate(PAYROLL, 0.43)),
                true, 0.44, "q", 0.01, false));

        // ── OFFER turn: the capability form is streamed and its descriptor is stored for resume ──
        String offer = perform("tell me about that", convId, null, null);
        assertThat(offer).contains("HR Policy Assistant").contains("Payroll Assistant");

        Optional<ClarificationDescriptor> stored = descriptorStore.peek(convId);
        assertThat(stored).isPresent();
        String nonce = stored.get().nonce();
        assertThat(stored.get().offeredValues()).contains(POLICY.agentId());

        // ── RESUME turn: pick POLICY, but access has been revoked → DENY (form is not authorization) ──
        allow.set(false);
        String resume = perform("HR Policy Assistant", convId, nonce, POLICY.agentId());

        // The full pipeline + CHECK re-ran and honoured the deny — not a served answer, not a re-offered
        // form. The wording is manifest-declared capability-unavailable copy (World-B), so assert on the
        // denial semantics rather than a fixed string.
        assertThat(resume.toLowerCase()).containsAnyOf(
                "isn't available", "not available", "do not have access", "does not have access");
        assertThat(resume).doesNotContain("Payroll Assistant");   // the form is gone; this is a denial
        assertThat(resume).doesNotContain("Reply with the name");  // not a re-offered clarify form

        // The entitlement CHECK fired on the resume (offer + resume ⇒ ≥2 invocations).
        verify(cerbosAdapter, atLeast(2)).checkAgents(any(), any(), any());
    }
}
