package com.openwolf.iam.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Axiom A1 — the tenant binding every mint path (login, refresh, service, seeded-admin, OIDC)
 * routes through. These are the deterministic guards behind acceptance A1.1 (cross-tenant
 * un-mintable) and A1.2 (tenant_id mandatory, no default).
 */
class TenantTokenMintTest {

    /**
     * A1.1 — a principal whose home tenant is A can only ever be minted into tenant A. There is no
     * caller-supplied tenant on any token endpoint, and the derived audiences never reference any
     * tenant but the principal's home: a token asserting tenant B is un-mintable.
     */
    @Test
    void cannotMintCrossTenantToken() {
        String homeTenant = "tenant-a";

        // The tenant claim is derived solely from the principal's home tenant — never overridable.
        String tenantClaim = TenantClaims.requireTenant(homeTenant);
        List<String> audiences = TenantClaims.gatewayAudiences(List.of("conduit-gateway"), homeTenant);

        assertThat(tenantClaim).isEqualTo("tenant-a");
        assertThat(audiences).containsExactly("conduit-gateway", "conduit-gateway@tenant-a");
        // No path produces an audience for a foreign tenant — cross-tenant assertion is impossible.
        assertThat(audiences).noneMatch(a -> a.contains("tenant-b"));
        assertThat(audiences).doesNotContain("conduit-gateway@tenant-b");
    }

    /**
     * A1.2 — tenant_id is mandatory and has no default. A null/blank home tenant makes the
     * principal un-mintable (the mint throws) rather than silently minting a {@code default} token.
     */
    @Test
    void tenantClaimIsMandatory() {
        assertThatThrownBy(() -> TenantClaims.requireTenant(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mandatory");
        assertThatThrownBy(() -> TenantClaims.requireTenant("  "))
                .isInstanceOf(IllegalStateException.class);
        // Building the audience list is likewise un-mintable without a tenant.
        assertThatThrownBy(() -> TenantClaims.gatewayAudiences(List.of("conduit-gateway"), null))
                .isInstanceOf(IllegalStateException.class);
    }

    /** A non-canonical tenant id is rejected at mint (grammar {@code [a-z0-9][a-z0-9-]{0,62}}). */
    @Test
    void nonCanonicalTenantIsRejected() {
        assertThatThrownBy(() -> TenantClaims.requireTenant("Tenant_A"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantClaims.requireTenant("-leading-hyphen"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TenantClaims.requireTenant("default")).isEqualTo("default");
    }

    /** A service (client_credentials) subject with no explicit tenant binding is un-mintable. */
    @Test
    void unboundServiceSubjectIsUnmintable() {
        ServiceTenantProperties props = new ServiceTenantProperties();
        assertThat(props.tenantFor("gateway-client")).isNull();
        assertThatThrownBy(() -> TenantClaims.requireTenant(props.tenantFor("gateway-client")))
                .isInstanceOf(IllegalStateException.class);

        props.getServiceTenants().put("gateway-client", "default");
        assertThat(TenantClaims.requireTenant(props.tenantFor("gateway-client"))).isEqualTo("default");
    }
}
