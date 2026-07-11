package ai.conduit.gateway.domain.manifest;

import ai.conduit.gateway.domain.manifest.DomainManifestStore.IdentifiedReference;
import ai.conduit.gateway.synthesis.input.EntityBag;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the real test-resource manifests (wealth + insurance + asset-servicing) into a plain
 * {@link DomainManifestStore} — no Spring context, no Redis — and verifies
 * {@link DomainManifestStore#identifyByReference} sources a grounding reference from the correct
 * sub-domain purely from manifest data (entity_types × required_context × resource_scoped), across a
 * multi-domain registry. This is the manifest-driven wiring the Stage-1 grounder relies on.
 */
class DomainManifestStoreReferenceTest {

    private DomainManifestStore store;

    @BeforeEach
    void load() throws Exception {
        store = new DomainManifestStore(new ObjectMapper(), new StandardEnvironment(), "classpath:");
        store.load();
    }

    private static EntityBag bag(String extractAs, String value) {
        return EntityBag.of(Map.of(extractAs, value), Map.of());
    }

    @Test
    void extractedWealthName_groundsToPrivateBankingRelationship() {
        Optional<IdentifiedReference> ref =
                store.identifyByReference(bag("relationship_reference", "Whitman Family Office"), "…");

        assertThat(ref).isPresent();
        assertThat(ref.get().id()).isEqualTo("Whitman Family Office");
        assertThat(ref.get().entityType().key()).isEqualTo("relationship_id");
        assertThat(ref.get().subDomain().subDomainId()).isEqualTo("private-banking");
        assertThat(ref.get().coverage()).isNotNull();
    }

    @Test
    void extractedInsuranceName_groundsToClaimsServicingPolicy() {
        Optional<IdentifiedReference> ref =
                store.identifyByReference(bag("policy_reference", "the Acme fleet policy"), "…");

        assertThat(ref).isPresent();
        assertThat(ref.get().entityType().key()).isEqualTo("policy_id");
        assertThat(ref.get().subDomain().subDomainId()).isEqualTo("claims-servicing");
        assertThat(ref.get().coverage()).isNotNull();
    }

    @Test
    void nonRequiredEntityReference_isNotGrounded() {
        // fund_reference is a resolvable entity but NOT in any sub-domain's required_context, so it is
        // not a grounding subject — only required resolvable entities gate coverage.
        assertThat(store.identifyByReference(bag("fund_reference", "the growth fund"), "…")).isEmpty();
    }

    @Test
    void emptyOrNullBag_yieldsNoReference() {
        assertThat(store.identifyByReference(EntityBag.empty(), "…")).isEmpty();
        assertThat(store.identifyByReference(null, "…")).isEmpty();
    }

    // ── Piece 2: return-all + deterministic ordering ─────────────────────────────────────────────

    @Test
    void identifyAllByReference_returnsEligibleInterpretations_deterministically() {
        List<IdentifiedReference> refs =
                store.identifyAllByReference(bag("relationship_reference", "Whitman Family Office"), "…");

        assertThat(refs).isNotEmpty();
        assertThat(refs).extracting(r -> r.subDomain().subDomainId()).contains("private-banking");
        // Deterministic: a second identical call yields the identical ordered list.
        List<IdentifiedReference> again =
                store.identifyAllByReference(bag("relationship_reference", "Whitman Family Office"), "…");
        assertThat(again).extracting(r -> r.subDomain().subDomainId())
                .isEqualTo(refs.stream().map(r -> r.subDomain().subDomainId()).toList());
        // identifyByReference is the deterministic first-of-order compat view.
        assertThat(store.identifyByReference(bag("relationship_reference", "Whitman Family Office"), "…"))
                .get().extracting(r -> r.subDomain().subDomainId()).isEqualTo(refs.get(0).subDomain().subDomainId());
    }

    @Test
    void interpretationsForReference_scopesToRequiredResolvableSlot() {
        // A required, resolvable, resource-scoped slot grounds…
        assertThat(store.interpretationsForReference("relationship_reference", "Whitman"))
                .extracting(r -> r.subDomain().subDomainId()).containsExactly("private-banking");
        assertThat(store.interpretationsForReference("policy_reference", "the Acme fleet policy"))
                .extracting(r -> r.subDomain().subDomainId()).containsExactly("claims-servicing");
        // …a resolvable slot that is NOT any resource-scoped sub-domain's required entity does not.
        assertThat(store.interpretationsForReference("fund_reference", "the growth fund")).isEmpty();
        assertThat(store.interpretationsForReference("relationship_reference", " ")).isEmpty();
    }

    // ── Piece 2 / V2.1 #4: resolvable-but-non-coverage-scoped id_pattern flag ─────────────────────

    @Test
    void matchesNonCoverageScopedResolvableId_flagsFundPatternOnly() {
        // fund_id (FND-…) is resolvable on resource_scoped=false servicing sub-domains → flagged.
        assertThat(store.matchesNonCoverageScopedResolvableId("FND-000123")).isTrue();
        // relationship_id (REL-…) is resolvable only on a resource_scoped=true sub-domain → not flagged.
        assertThat(store.matchesNonCoverageScopedResolvableId("REL-00042")).isFalse();
        assertThat(store.matchesNonCoverageScopedResolvableId("just some prose")).isFalse();
        assertThat(store.matchesNonCoverageScopedResolvableId(null)).isFalse();
    }
}
