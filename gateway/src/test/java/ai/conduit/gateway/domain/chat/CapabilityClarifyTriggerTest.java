package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.registry.model.RoutingCandidate;
import ai.conduit.gateway.resolver.model.ResolverResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * THE trigger test. Routing abstains because it cannot confidently pick among several plausible,
 * CHECK-passed capabilities (low margin, no single confident route) and it is NOT a missing-entity case
 * (the candidates are enterprise-audience). The gateway must offer a {@code clarify_capability} form over
 * those capabilities — the candidate capability descriptions from their manifests — instead of dead-ending
 * at the flat no-service.
 *
 * <p>Red on today's tree (this path returned the flat no-service before the wire); green after.
 */
class CapabilityClarifyTriggerTest extends CapabilityClarifyFixture {

    @Test
    void capabilityAmbiguousAbstain_offersCapabilityClarify_notNoService() throws Exception {
        cerbosAllowAll();
        // Routing abstains (low margin) with TWO plausible capabilities in the broad (skipped) set.
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(new ResolverResult(
                List.of(),
                List.of(new RoutingCandidate(POLICY, 0.44), new RoutingCandidate(PAYROLL, 0.43)),
                true, 0.44, "q", 0.01, false));

        String body = ask("tell me about that");

        // The manifest-declared capability labels are offered (World-B: copy from the manifest).
        assertThat(body).contains("HR Policy Assistant");
        assertThat(body).contains("Payroll Assistant");
        // NOT the flat no-service dead-end (the pre-wire behaviour for this exact path).
        assertThat(body).doesNotContain("none of the services I can reach");
    }
}
