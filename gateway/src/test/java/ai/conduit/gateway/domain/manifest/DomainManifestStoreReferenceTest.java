package ai.conduit.gateway.domain.manifest;

import ai.conduit.gateway.domain.manifest.DomainManifestStore.IdentifiedReference;
import ai.conduit.gateway.synthesis.input.EntityBag;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

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
}
