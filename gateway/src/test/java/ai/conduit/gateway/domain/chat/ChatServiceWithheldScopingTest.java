package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.registry.model.AgentManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * bug-260: a domain must be labeled "withheld" (outside your access) ONLY when NONE of its selected
 * agents survived the structural gate. A partial classification prune — where a lower-classification
 * sibling (settlement_status, confidential) survives while the higher one (settlement_risk,
 * confidential-pii) is pruned — must NOT emit a blanket "outside your access" over data that WAS
 * returned for that domain.
 */
class ChatServiceWithheldScopingTest {

    private static AgentManifest agent(String id, String domain) {
        AgentManifest.Skill skill = new AgentManifest.Skill(
                "skill", "skill", "d", List.of(), List.of("d"), List.of("text"), List.of("json"));
        return new AgentManifest(
                id, id, "d", "1.0.0", null, domain, null, null, null, "http",
                null, new AgentManifest.Capabilities(false, false), List.of(skill),
                new AgentManifest.Constraints("read", "internal", 1_000),
                null, null, null, null, true, null);
    }

    @Test
    void partialPruneWithinADomainIsNotWithheld() {
        var status = agent("meridian.servicing.settlement_status", "asset-servicing");
        var risk = agent("meridian.servicing.settlement_risk", "asset-servicing");
        // settlement_status survived the gate → asset-servicing is served.
        Set<String> served = Set.of("asset-servicing");

        assertThat(ChatService.computeWithheldDomains(List.of(status, risk), served))
                .as("a domain still served by an allowed agent must NOT be withheld")
                .isEmpty();
    }

    @Test
    void fullyPrunedDomainIsWithheld() {
        var wealth = agent("meridian.wealth.holdings", "wealth-management");
        var insurance = agent("meridian.insurance.renewal_risk", "insurance");
        // Only wealth survived; insurance had no surviving agent.
        Set<String> served = Set.of("wealth-management");

        assertThat(ChatService.computeWithheldDomains(List.of(wealth, insurance), served))
                .as("a domain with no surviving agent IS withheld")
                .containsExactly("insurance");
    }

    @Test
    void mixedOnlyWithholdsTheFullyPrunedDomain() {
        var status = agent("s.status", "asset-servicing");   // survives
        var risk = agent("s.risk", "asset-servicing");        // pruned, but sibling survives
        var insurance = agent("i.renewal", "insurance");      // fully pruned
        Set<String> served = Set.of("asset-servicing");

        assertThat(ChatService.computeWithheldDomains(List.of(status, risk, insurance), served))
                .as("servicing is served (status survived); only the fully-pruned insurance is withheld")
                .containsExactly("insurance");
    }
}
