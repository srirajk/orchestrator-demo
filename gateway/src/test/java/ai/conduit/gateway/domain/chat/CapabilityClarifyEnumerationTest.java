package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.domain.auth.CerbosEntitlementAdapter;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.RoutingCandidate;
import ai.conduit.gateway.resolver.model.ResolverResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * THE enumeration-oracle security test for the capability form. A capability the principal cannot invoke
 * (Cerbos-denied) must NEVER enter the offered set — the same CHECK-before-display discipline the entity
 * form enforces. Fails genuinely if the trigger built its options from the raw candidate set instead of the
 * Cerbos-filtered set.
 */
class CapabilityClarifyEnumerationTest extends CapabilityClarifyFixture {

    @Test
    void cerbosDeniedCapabilityNeverEntersTheForm() throws Exception {
        // Cerbos allows POLICY, DENIES PAYROLL.
        when(cerbosAdapter.checkAgents(any(), any())).thenAnswer(inv -> {
            List<AgentManifest> ms = inv.getArgument(1);
            Map<String, Boolean> decisions = new HashMap<>();
            if (ms != null) ms.forEach(m -> decisions.put(m.agentId(), POLICY.agentId().equals(m.agentId())));
            return new CerbosEntitlementAdapter.BatchResult(decisions, "cerbos");
        });
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(new ResolverResult(
                List.of(),
                List.of(new RoutingCandidate(POLICY, 0.44), new RoutingCandidate(PAYROLL, 0.43)),
                true, 0.44, "q", 0.01, false));

        String body = ask("tell me about that");

        assertThat(body).contains("HR Policy Assistant");        // entitled capability offered
        assertThat(body).doesNotContain("Payroll Assistant");    // Cerbos-denied capability never offered
        assertThat(body).doesNotContain("Explains payroll");     // nor its manifest description
    }
}
