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
 * The over-trigger guard. A query with NO plausible capability candidate must still return the honest
 * no-service message — the capability form only fires when there is a genuine capability ambiguity to
 * disambiguate. Two ways to have "no plausible candidate": an empty abstain set, and a set where every
 * candidate is below the plausibility floor (long-tail noise). Both stay no-service.
 */
class GenuineNoServiceStillNoServiceTest extends CapabilityClarifyFixture {

    @Test
    void noCandidatesAtAll_staysNoService() throws Exception {
        cerbosAllowAll();
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(new ResolverResult(
                List.of(), List.of(), true, 0.1, "q", 0.0, false));

        String body = ask("what is the meaning of life");

        assertThat(body).doesNotContain("HR Policy Assistant");
        assertThat(body).doesNotContain("Payroll Assistant");
        assertThat(body.toLowerCase()).containsAnyOf("wasn't sure", "add a little detail", "able to answer");
    }

    @Test
    void allCandidatesBelowPlausibilityFloor_staysNoService() throws Exception {
        cerbosAllowAll();
        // The abstain surfaces candidates, but every one is below the confidence floor (long-tail noise) —
        // not a plausible capability to disambiguate.
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(new ResolverResult(
                List.of(),
                List.of(new RoutingCandidate(POLICY, 0.20), new RoutingCandidate(PAYROLL, 0.18)),
                true, 0.20, "q", 0.02, false));

        String body = ask("tell me about that");

        assertThat(body).doesNotContain("HR Policy Assistant");
        assertThat(body).doesNotContain("Payroll Assistant");
        assertThat(body.toLowerCase()).containsAnyOf("wasn't sure", "add a little detail", "able to answer");
    }
}
