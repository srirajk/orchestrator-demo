package ai.conduit.gateway.domain.auth;

import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.testsupport.StubHttpServer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ai.conduit.gateway.domain.auth.CerbosTimeoutFailClosedTest.adapter;
import static ai.conduit.gateway.domain.auth.CerbosTimeoutFailClosedTest.agent;
import static ai.conduit.gateway.domain.auth.CerbosTimeoutFailClosedTest.principal;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The STATED behavior delta (F3): a slow-but-ALIVE PDP that answers ALLOW after the deadline now
 * fail-closes deterministically, where it previously blocked-then-succeeded. The delta is purely the
 * deadline — the SAME slow-alive PDP, given a generous deadline, still returns its ALLOW. Both halves
 * are asserted here so the change is pinned as a deliberate, bounded trade (correct fail-closed authz),
 * not a silent regression.
 */
class CerbosSlowAliveTest {

    /** A PDP that sleeps {@code delayMs}, then returns EFFECT_ALLOW for invoke on the given agents. */
    private static StubHttpServer slowAllowPdp(long delayMs, String... agentIds) {
        StringBuilder results = new StringBuilder("[");
        for (int i = 0; i < agentIds.length; i++) {
            if (i > 0) results.append(",");
            results.append("{\"resource\":{\"id\":\"").append(agentIds[i])
                   .append("\"},\"actions\":{\"invoke\":\"EFFECT_ALLOW\"}}");
        }
        results.append("]");
        String body = "{\"results\":" + results + "}";
        StubHttpServer stub = new StubHttpServer();
        stub.handle("/api/check/resources", exchange -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
            StubHttpServer.respond(exchange, 200, "application/json", body);
        });
        return stub;
    }

    @Test
    void slowAlivePdpBeyondDeadlineFailsClosed() {
        try (StubHttpServer stub = slowAllowPdp(800, "agent-1")) {
            CerbosEntitlementAdapter cerbos = adapter(stub, /*deadlineMs*/ 300);   // 300 < 800
            List<AgentManifest> agents = List.of(agent("agent-1"));

            CerbosEntitlementAdapter.BatchResult result = cerbos.checkAgents(principal(), agents);
            assertThat(result.isAllowed("agent-1"))
                    .as("a PDP slower than the deadline must fail closed, even though it would allow")
                    .isFalse();
            assertThat(result.source()).isEqualTo("local-fallback-closed");
        }
    }

    @Test
    void sameSlowPdpAllowsWhenGivenAGenerousDeadline() {
        try (StubHttpServer stub = slowAllowPdp(800, "agent-1")) {
            CerbosEntitlementAdapter cerbos = adapter(stub, /*deadlineMs*/ 3000);  // 3000 > 800
            List<AgentManifest> agents = List.of(agent("agent-1"));

            CerbosEntitlementAdapter.BatchResult result = cerbos.checkAgents(principal(), agents);
            assertThat(result.isAllowed("agent-1"))
                    .as("with headroom the same PDP still returns its ALLOW — the delta is the deadline")
                    .isTrue();
            assertThat(result.source()).isEqualTo("cerbos");
        }
    }
}
