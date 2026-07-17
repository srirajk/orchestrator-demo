package ai.conduit.gateway.domain.auth;

import ai.conduit.gateway.infrastructure.outbound.OutboundGate;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.AgentManifest.Constraints;
import ai.conduit.gateway.testsupport.StubHttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S1c — the PRIMARY routing entitlement path carries the tenant's active bundle version.
 *
 * <p>S1b wired the adapter and the on-path invoke re-verifier; this proves the remaining seam:
 * {@link EntitlementService#filterAgents(Principal, List, TenantExecutionContext)} — the MAIN
 * structural routing gate the chat request path calls — forwards the request's
 * {@link TenantExecutionContext} all the way to the Cerbos {@code CheckResources} wire body. A
 * promoted tenant is therefore evaluated against its own promoted bundle on the primary gate, not
 * only on the re-verifier. A {@code null}/unresolved context still sends {@code "default"} — the
 * single-tenant demo is byte-identical to before.
 *
 * <p>The assertion is on the actual POST body the adapter emits when driven <em>through
 * filterAgents</em> (not the adapter in isolation, and not the invoke re-verifier), so it proves the
 * end-to-end primary-path threading.
 */
class EntitlementServicePolicyVersionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void primaryFilterAgents_promotedTenant_stampsItsActiveBundleVersion() {
        try (StubHttpServer stub = new StubHttpServer()) {
            AtomicReference<String> body = capturePost(stub);
            EntitlementService svc = service(stub);

            TenantExecutionContext promoted = TenantExecutionContext.of("acme", "acme", "b_xyz");
            svc.filterAgents(principal(), List.of(agent("agent-1")), promoted);

            JsonNode sent = read(body.get());
            assertThat(sent.path("principal").path("policyVersion").asText())
                    .as("primary filterAgents must stamp principal.policyVersion = the tenant's active bundle")
                    .isEqualTo("b_xyz");
            assertThat(sent.path("resources").path(0).path("resource").path("policyVersion").asText())
                    .as("primary filterAgents must stamp resource.policyVersion = the tenant's active bundle")
                    .isEqualTo("b_xyz");
        }
    }

    @Test
    void primaryFilterAgents_nullContext_stampsDefault() {
        try (StubHttpServer stub = new StubHttpServer()) {
            AtomicReference<String> body = capturePost(stub);
            EntitlementService svc = service(stub);

            svc.filterAgents(principal(), List.of(agent("agent-1")), null);

            JsonNode sent = read(body.get());
            assertThat(sent.path("principal").path("policyVersion").asText())
                    .as("a null/unresolved ctx on the primary path is byte-identical to before: \"default\"")
                    .isEqualTo("default");
            assertThat(sent.path("resources").path(0).path("resource").path("policyVersion").asText())
                    .isEqualTo("default");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Captures the POST body and answers with an empty, well-formed CheckResources result. */
    private static AtomicReference<String> capturePost(StubHttpServer stub) {
        AtomicReference<String> captured = new AtomicReference<>();
        stub.handle("/api/check/resources", exchange -> {
            String req = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            captured.set(req);
            StubHttpServer.respond(exchange, 200, "application/json", "{\"results\":[]}");
        });
        return captured;
    }

    private static JsonNode read(String body) {
        try {
            assertThat(body).as("filterAgents must have driven the adapter to POST a request body").isNotNull();
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** EntitlementService over a REAL adapter wired to the stub, so the primary path hits the wire. */
    private static EntitlementService service(StubHttpServer stub) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(2000))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(2000));
        RestClient restClient = RestClient.builder().requestFactory(factory).baseUrl(stub.baseUrl()).build();
        OutboundGate gate = new OutboundGate(new SimpleMeterRegistry(), 16, 250);
        CerbosEntitlementAdapter cerbos = new CerbosEntitlementAdapter(restClient, new ObjectMapper(), gate,
                "127.0.0.1", stub.port(), 2000, "closed", "relationship");
        return new EntitlementService(cerbos, new SimpleMeterRegistry(),
                Mockito.mock(RevocationChecker.class));
    }

    private static Principal principal() {
        return new Principal("rm_jane", List.of("relationship_manager"), List.of(), Map.of(), List.of());
    }

    private static AgentManifest agent(String id) {
        return new AgentManifest(id, id, null, null, null, null, null, null, null, "http",
                null, null, null, new Constraints("read", "internal", 5000),
                null, null, null, null, true, null);
    }
}
