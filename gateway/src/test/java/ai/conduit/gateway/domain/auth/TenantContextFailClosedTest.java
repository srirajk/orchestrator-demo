package ai.conduit.gateway.domain.auth;

import ai.conduit.gateway.infrastructure.telemetry.RequestCorrelationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A2.3 — a verified principal whose tenant is missing or unknown dies fail-closed at the request
 * filter, BEFORE the controller and BEFORE any outbound I/O.
 *
 * <p>The proof of "before I/O" is that the downstream {@link FilterChain} is never entered — the
 * chain is what would reach the controller, and through it Redis / Cerbos / the coverage service /
 * the LLM. We stand a real {@link RequestCorrelationFilter} over a real {@link TenantContextResolver}
 * backed by a spied {@link ProvisionedTenantDirectory}, put a verified (but tenant-less / unknown)
 * JWT on the {@link SecurityContextHolder}, and assert the deny status + zero chain interactions.
 */
class TenantContextFailClosedTest {

    private final ProvisionedTenantDirectory directory = spy(new StubDirectory());
    private final RequestCorrelationFilter filter =
            new RequestCorrelationFilter(new TenantContextResolver(directory));

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingTenantDiesBeforeIO() throws Exception {
        authenticateWith(jwt(null));   // verified principal, but no tenant_id claim

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        // The chain (→ controller → Redis/Cerbos/coverage/LLM) is never entered.
        verifyNoInteractions(chain);
        // The resolver threw on the claim check BEFORE ever consulting the tenant directory.
        verify(directory, never()).find(org.mockito.ArgumentMatchers.anyString());
        // No tenant context leaked into the request holder.
        assertThat(RequestContext.getTenant()).isNull();
    }

    @Test
    void unknownTenantIsDenied() throws Exception {
        authenticateWith(jwt("ghost-tenant"));   // verified, tenant not in the provisioned set

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(chain);
        // The directory was consulted (and returned empty) — but nothing downstream ran.
        verify(directory).find("ghost-tenant");
        assertThat(RequestContext.getTenant()).isNull();
    }

    @Test
    void provisionedTenantPassesThrough() throws Exception {
        authenticateWith(jwt("default"));   // the single-tenant demo tenant — provisioned

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        // The happy path proceeds into the chain exactly once, and the holder was cleared after.
        assertThat(mockingDetails(chain).getInvocations()).hasSize(1);
        assertThat(RequestContext.getTenant()).isNull();   // cleared in the filter's finally
    }

    // ── helpers ────────────────────────────────────────────────────────────────
    private static void authenticateWith(Jwt jwt) {
        AbstractAuthenticationToken auth = new JwtAuthenticationToken(jwt);
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static Jwt jwt(String tenantId) {
        Jwt.Builder b = Jwt.withTokenValue("verified-token")
                .header("alg", "RS256")
                .subject("rm_jane")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (tenantId != null) b.claim("tenant_id", tenantId);
        return b.build();
    }

    /** A directory provisioning only {@code default} — matching the single-tenant demo snapshot. */
    private static final class StubDirectory implements ProvisionedTenantDirectory {
        @Override public Optional<ProvisionedTenant> find(String tenantId) {
            return "default".equals(tenantId)
                    ? Optional.of(new ProvisionedTenant("default", "config-v1"))
                    : Optional.empty();
        }
        @Override public boolean hasSnapshot() { return true; }
        @Override public long snapshotVersion() { return 1L; }
    }
}
