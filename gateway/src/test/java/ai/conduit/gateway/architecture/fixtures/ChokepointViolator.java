package ai.conduit.gateway.architecture.fixtures;

import ai.conduit.gateway.adapter.ProtocolAdapter;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * DELIBERATE VIOLATOR — committed on purpose (F5 spec §3a-4).
 *
 * <p>Resides outside {@code ..orchestration.harness..} and calls {@link ProtocolAdapter#invoke},
 * so it violates {@code ArchitectureRulesTest.only_harness_invokes_protocol_adapter}. This class is
 * excluded from that rule's scope (DoNotIncludeTests) and is asserted RED by
 * {@link ai.conduit.gateway.architecture.ArchitectureRulesFixtureTest}. It exists so the chokepoint
 * rule's failability is proven by code in every CI run, never by deleted scratch work.
 *
 * <p>It is never executed — only its bytecode (the method call) matters.
 */
public final class ChokepointViolator {

    private ChokepointViolator() {}

    static JsonNode bypassTheHarness(ProtocolAdapter adapter, AgentManifest manifest,
                                     JsonNode input, String token) throws Exception {
        // This is the call the invariant forbids anywhere but AgentHarness.
        return adapter.invoke(manifest, input, token);
    }
}
