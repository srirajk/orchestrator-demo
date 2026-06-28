package ai.meridian.gateway.domain.manifest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Targeted unit tests for {@link EffectiveManifest#merge} covering the three
 * critical contract assertions used by the coverage pipeline.
 */
class EffectiveManifestTest {

    // (a) resource_scoped=true sub-domain
    @Test
    @DisplayName("merge with resource_scoped=true: resourceScoped, coverage, requiresRelationship all correct")
    void merge_resourceScopedTrue_fullContractHolds() {
        var coverage = new DomainManifest.Coverage(
                "http://wealth-coverage:8086/coverage/{principal_id}",
                "http://wealth-coverage:8086/coverage/{principal_id}/resources/{id}",
                "http://wealth-coverage:8086/entities/resolve",
                300);
        var domain = new DomainManifest(
                "wealth-management", "Wealth Management", coverage,
                new DomainManifest.MemoryCompaction(
                        List.of("relationship_id", "client_name"),
                        List.of("raw_agent_outputs")));
        var sub = new SubDomainManifest(
                "private-banking", "Private Banking", "wealth-management",
                List.of("relationship_id"), true,
                Map.of("relationship_id",
                        new ClarificationSchema("Which client?", "principal_book", 1, null)),
                List.of("acme.wealth.holdings"));

        var em = EffectiveManifest.merge(domain, sub, "acme.wealth.holdings");

        assertThat(em.resourceScoped()).isTrue();
        assertThat(em.coverage()).isNotNull();
        assertThat(em.requiresRelationship()).isTrue();
        assertThat(em.coverage().discoverUrl()).contains("coverage");
        assertThat(em.coverage().checkUrl()).contains("resources");
        assertThat(em.coverage().resolveUrl()).contains("resolve");
    }

    // (b) resource_scoped=false sub-domain
    @Test
    @DisplayName("merge with resource_scoped=false: resourceScoped is false")
    void merge_resourceScopedFalse_notResourceScoped() {
        var domain = new DomainManifest("asset-servicing", "Asset Servicing", null, null);
        var sub = new SubDomainManifest(
                "nav", "NAV", "asset-servicing",
                List.of(), false, Map.of(), List.of("acme.servicing.nav"));

        var em = EffectiveManifest.merge(domain, sub, "acme.servicing.nav");

        assertThat(em.resourceScoped()).isFalse();
        assertThat(em.requiresRelationship()).isFalse();
    }

    // (c) null domain: no NPE, coverage is null
    @Test
    @DisplayName("merge with null domain: no NullPointerException and coverage is null")
    void merge_nullDomain_noPeAndNullCoverage() {
        var sub = new SubDomainManifest(
                "private-banking", "Private Banking", null,
                List.of("relationship_id"), true, Map.of(), List.of());

        assertThatNoException().isThrownBy(() -> {
            var em = EffectiveManifest.merge(null, sub, "test-agent");
            assertThat(em.coverage()).isNull();
            assertThat(em.resourceScoped()).isTrue();
            assertThat(em.requiresRelationship()).isTrue();
        });
    }
}
