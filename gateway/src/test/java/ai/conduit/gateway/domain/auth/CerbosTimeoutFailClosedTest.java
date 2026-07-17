package ai.conduit.gateway.domain.auth;

import ai.conduit.gateway.infrastructure.outbound.OutboundGate;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.AgentManifest.Constraints;
import ai.conduit.gateway.testsupport.StubHttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Cerbos PDP that never answers must not park the request-path authz check: the deadline fires and
 * {@code checkAgents} fail-CLOSES (deny all), tagged {@code local-fallback-closed}. This is the
 * production-safe default — an authz outage denies, it never grants.
 */
class CerbosTimeoutFailClosedTest {

    @Test
    void unreachablePdpDeniesAllAgentsFastAndFailsClosed() {
        try (StubHttpServer stub = new StubHttpServer()) {
            // PDP accepts the connection but never sends a response.
            stub.handle("/api/check/resources",
                    exchange -> { try { Thread.sleep(5_000); } catch (InterruptedException ignored) {} });

            CerbosEntitlementAdapter cerbos = adapter(stub, /*deadlineMs*/ 300);
            List<AgentManifest> agents = List.of(agent("agent-1"), agent("agent-2"));

            long t0 = System.nanoTime();
            CerbosEntitlementAdapter.BatchResult result = cerbos.checkAgents(principal(), agents);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            assertThat(result.isAllowed("agent-1")).isFalse();
            assertThat(result.isAllowed("agent-2")).isFalse();
            assertThat(result.source()).isEqualTo("local-fallback-closed");
            assertThat(elapsedMs).as("must fail fast at the deadline, not hang").isLessThan(3_000);
        }
    }

    static CerbosEntitlementAdapter adapter(StubHttpServer stub, long deadlineMs) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(2000))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(deadlineMs));
        RestClient restClient = RestClient.builder().requestFactory(factory).baseUrl(stub.baseUrl()).build();
        OutboundGate gate = new OutboundGate(new SimpleMeterRegistry(), 16, 250);
        return new CerbosEntitlementAdapter(restClient, new ObjectMapper(), gate,
                "127.0.0.1", stub.port(), deadlineMs, "closed", "relationship");
    }

    static Principal principal() {
        return new Principal("rm_jane", List.of("relationship_manager"),
                List.of(), Map.of(), List.of());
    }

    static AgentManifest agent(String id) {
        return new AgentManifest(id, id, null, null, null, null, null, null, null, "http",
                null, null, null, new Constraints("read", "internal", 5000),
                null, null, null, null, true, null);
    }
}
