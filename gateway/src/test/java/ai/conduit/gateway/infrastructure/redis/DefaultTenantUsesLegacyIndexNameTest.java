package ai.conduit.gateway.infrastructure.redis;

import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Demo-preservation guard (Axiom A3).
 *
 * <p>The gateway READS a routing index the registry-service WROTE under the legacy names
 * ({@code intent_idx}, {@code vec:...}). Per-tenant ingestion (the WRITE side) is A4 and does not
 * exist yet, so A3's read-side seam MUST resolve to those exact legacy names for the default /
 * single-tenant case. If a default-tenant routing query resolved {@code intent_idx__default} while
 * the index is named {@code intent_idx}, the demo would die with an empty result set.
 *
 * <p>These tests pin that the seam returns the legacy names for the default tenant and when
 * multi-tenancy is off, and only diverges for a real non-default tenant.
 */
class DefaultTenantUsesLegacyIndexNameTest {

    private static final String LEGACY_INDEX = "intent_idx";

    private static TenantExecutionContext ctx(String tenant) {
        return TenantExecutionContext.of(tenant, tenant, "v1");
    }

    @Test
    void multiTenantOff_alwaysLegacy_evenForARealTenantString() {
        TenantKeyspace ks = new TenantKeyspace(false, "default");

        // Off ⇒ legacy regardless of the context's tenant, so nothing about turning the flag on
        // later can be smuggled in early.
        assertThat(ks.isLegacy(ctx("acme"))).isTrue();
        assertThat(ks.indexName(LEGACY_INDEX, ctx("acme"))).isEqualTo("intent_idx");
        assertThat(ks.key("vec:agent.a:0", ctx("acme"))).isEqualTo("vec:agent.a:0");
        assertThat(ks.keyPrefix(ctx("acme"))).isEmpty();
    }

    @Test
    void multiTenantOn_defaultTenant_isLegacy() {
        TenantKeyspace ks = new TenantKeyspace(true, "default");

        assertThat(ks.isLegacy(ctx("default"))).isTrue();
        assertThat(ks.indexName(LEGACY_INDEX, ctx("default")))
                .isEqualTo("intent_idx")
                .doesNotContain("__");
        assertThat(ks.key("vec:agent.a:0", ctx("default")))
                .isEqualTo("vec:agent.a:0")
                .doesNotStartWith("t:");
    }

    @Test
    void multiTenantOn_nullOrBlankContext_isLegacy() {
        TenantKeyspace ks = new TenantKeyspace(true, "default");

        assertThat(ks.isLegacy(null)).isTrue();
        assertThat(ks.indexName(LEGACY_INDEX, null)).isEqualTo("intent_idx");
        assertThat(ks.key("vec:x", null)).isEqualTo("vec:x");
        assertThat(ks.isLegacy(ctx("  "))).isTrue();
    }

    @Test
    void multiTenantOn_realTenant_getsTenantQualifiedNames() {
        TenantKeyspace ks = new TenantKeyspace(true, "default");

        assertThat(ks.isLegacy(ctx("acme"))).isFalse();
        assertThat(ks.indexName(LEGACY_INDEX, ctx("acme"))).isEqualTo("intent_idx__acme");
        assertThat(ks.key("vec:agent.a:0", ctx("acme"))).isEqualTo("t:acme:vec:agent.a:0");
        assertThat(ks.keyPrefix(ctx("acme"))).isEqualTo("t:acme:");
    }

    @Test
    void aRealTenantSegmentMustBeCanonical_elseFailsClosed() {
        TenantKeyspace ks = new TenantKeyspace(true, "default");

        // A non-canonical tenant must never be allowed to shape a key/index name.
        assertThatThrownBy(() -> ks.indexName(LEGACY_INDEX, ctx("Bad Tenant!")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical grammar");
    }

    @Test
    void configuredDefaultTenantNameIsHonoured() {
        // The default-tenant name is configuration, not a hardcoded literal.
        TenantKeyspace ks = new TenantKeyspace(true, "house");

        assertThat(ks.isLegacy(ctx("house"))).isTrue();
        assertThat(ks.indexName(LEGACY_INDEX, ctx("house"))).isEqualTo("intent_idx");
        assertThat(ks.isLegacy(ctx("default"))).isFalse();
        assertThat(ks.indexName(LEGACY_INDEX, ctx("default"))).isEqualTo("intent_idx__default");
    }
}
