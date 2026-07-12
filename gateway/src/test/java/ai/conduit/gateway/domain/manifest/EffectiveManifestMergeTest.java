package ai.conduit.gateway.domain.manifest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveManifestMergeTest {

    @Test
    void resourceScopedTrueWhenSubDomainSetsIt() {
        var domain = new DomainManifest("wealth-management", "Wealth",
            new DomainManifest.Coverage(
                "http://coverage/discover/{principal_id}",
                "http://coverage/check/{principal_id}/{id}",
                "http://coverage/resolve",
                300),
            null, null, null, null);
        var sub = new SubDomainManifest("private-banking", "Private Banking",
            "wealth-management", List.of("relationship_id"), true, Map.of(), List.of());

        var effective = EffectiveManifest.merge(domain, sub, "meridian.wealth.holdings");

        assertThat(effective.resourceScoped()).isTrue();
        assertThat(effective.requiresContext()).isTrue();
    }

    @Test
    void coverageComesFromParentDomain() {
        var coverage = new DomainManifest.Coverage(
            "http://coverage/discover/{principal_id}",
            "http://coverage/check/{principal_id}/{id}",
            "http://coverage/resolve",
            60);
        var domain = new DomainManifest("wealth-management", "Wealth", coverage, null, null, null, null);
        var sub = new SubDomainManifest("private-banking", "PB", "wealth-management",
            List.of("relationship_id"), true, Map.of(), List.of());

        var effective = EffectiveManifest.merge(domain, sub, "meridian.wealth.holdings");

        assertThat(effective.coverage()).isNotNull();
        assertThat(effective.coverage().discoverUrl()).isEqualTo("http://coverage/discover/{principal_id}");
        assertThat(effective.coverage().checkUrl()).isEqualTo("http://coverage/check/{principal_id}/{id}");
    }

    @Test
    void mustPreserveFromDomainMemoryCompaction() {
        var domain = new DomainManifest("wealth-management", "Wealth", null,
            new DomainManifest.MemoryCompaction(
                List.of("relationship_id", "client_name"),
                List.of("raw_agent_outputs")), null, null, null);
        var sub = new SubDomainManifest("private-banking", "PB", "wealth-management",
            List.of("relationship_id"), false, Map.of(), List.of());

        var effective = EffectiveManifest.merge(domain, sub, "meridian.wealth.holdings");

        assertThat(effective.mustPreserve()).contains("relationship_id", "client_name");
        assertThat(effective.canDrop()).contains("raw_agent_outputs");
    }

    @Test
    void requiredContextFromSubDomain() {
        var domain = new DomainManifest("wealth-management", "Wealth", null, null, null, null, null);
        var sub = new SubDomainManifest("private-banking", "PB", "wealth-management",
            List.of("relationship_id", "time_period"), false,
            Map.of(), List.of());

        var effective = EffectiveManifest.merge(domain, sub, "meridian.wealth.holdings");

        assertThat(effective.requiredContext()).containsExactly("relationship_id", "time_period");
    }

    @Test
    void clarificationSchemaFromSubDomain() {
        var schema = new ClarificationSchema(
            "Which client relationship?", "principal_book", 1, null);
        var domain = new DomainManifest("wealth-management", "Wealth", null, null, null, null, null);
        var sub = new SubDomainManifest("private-banking", "PB", "wealth-management",
            List.of("relationship_id"), false,
            Map.of("relationship_id", schema), List.of());

        var effective = EffectiveManifest.merge(domain, sub, "meridian.wealth.holdings");

        assertThat(effective.clarificationFor("relationship_id")).isNotNull();
        assertThat(effective.clarificationFor("relationship_id").question()).isEqualTo("Which client relationship?");
    }

    @Test
    void nullDomainAndSubDomainProducesEmptyEffective() {
        var effective = EffectiveManifest.merge(null, null, "some-agent");

        assertThat(effective.coverage()).isNull();
        assertThat(effective.resourceScoped()).isFalse();
        assertThat(effective.mustPreserve()).isEmpty();
        assertThat(effective.canDrop()).isEmpty();
        assertThat(effective.requiredContext()).isEmpty();
    }

    @Test
    void nullSubDomainNotResourceScoped() {
        var domain = new DomainManifest("wealth-management", "Wealth",
            new DomainManifest.Coverage("http://x/discover", "http://x/check", "http://x/resolve", 60),
            null, null, null, null);

        var effective = EffectiveManifest.merge(domain, null, "meridian.wealth.holdings");

        assertThat(effective.resourceScoped()).isFalse();
        assertThat(effective.coverage()).isNotNull();
    }
}
