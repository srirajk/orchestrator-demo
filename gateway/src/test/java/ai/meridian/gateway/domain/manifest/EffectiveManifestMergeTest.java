package ai.meridian.gateway.domain.manifest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveManifestMergeTest {

    @Test
    void subDomainAuthContractOverridesDomainLevel() {
        var domain = new DomainManifest("wealth-management", "Wealth",
            null,
            new DomainManifest.AuthorizationContract("http://domain/authz", "allowed", 30),
            null);
        var sub = new SubDomainManifest("private-banking", "PB", "wealth-management",
            List.of("relationship_id"), List.of("relationship_id"),
            Map.of(), new DomainManifest.AuthorizationContract("http://sub/authz", "allowed", 60),
            null, List.of());

        var effective = EffectiveManifest.merge(domain, sub, "acme.wealth.holdings");

        assertThat(effective.authorizationContract().urlTemplate()).isEqualTo("http://sub/authz");
    }

    @Test
    void agentWithNoSubDomainInheritsDomain() {
        var domain = new DomainManifest("wealth-management", "Wealth",
            new DomainManifest.EntityRegistry("http://crm/resolve", List.of("relationship"), 300),
            new DomainManifest.AuthorizationContract("http://domain/authz", "allowed", 30),
            null);

        var effective = EffectiveManifest.merge(domain, null, "acme.wealth.holdings");

        assertThat(effective.authorizationContract().urlTemplate()).isEqualTo("http://domain/authz");
        assertThat(effective.entityRegistry().url()).isEqualTo("http://crm/resolve");
    }

    @Test
    void mustPreserveUnionAcrossLevels() {
        var domain = new DomainManifest("wealth-management", "Wealth", null, null,
            new DomainManifest.MemoryCompaction(
                List.of("relationship_id", "client_name"),
                List.of("raw_agent_outputs")));
        var sub = new SubDomainManifest("private-banking", "PB", "wealth-management",
            List.of("relationship_id"), List.of(), Map.of(), null, null, List.of());

        var effective = EffectiveManifest.merge(domain, sub, "acme.wealth.holdings");

        assertThat(effective.mustPreserve()).contains("relationship_id", "client_name");
    }

    @Test
    void requiredContextFromSubDomain() {
        var domain = new DomainManifest("wealth-management", "Wealth", null, null, null);
        var sub = new SubDomainManifest("private-banking", "PB", "wealth-management",
            List.of("relationship_id", "time_period"), List.of("relationship_id"),
            Map.of(), null, null, List.of());

        var effective = EffectiveManifest.merge(domain, sub, "acme.wealth.holdings");

        assertThat(effective.requiredContext()).containsExactly("relationship_id", "time_period");
    }

    @Test
    void nullDomainAndSubDomainProducesEmptyEffective() {
        var effective = EffectiveManifest.merge(null, null, "some-agent");

        assertThat(effective.authorizationContract()).isNull();
        assertThat(effective.entityRegistry()).isNull();
        assertThat(effective.mustPreserve()).isEmpty();
    }
}
