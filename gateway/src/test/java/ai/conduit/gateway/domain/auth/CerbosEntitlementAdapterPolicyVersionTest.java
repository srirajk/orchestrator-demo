package ai.conduit.gateway.domain.auth;

import ai.conduit.gateway.infrastructure.outbound.OutboundGate;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.AgentManifest.Constraints;
import ai.conduit.gateway.testsupport.StubHttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
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
 * S1b — the gateway half of "promotion reaches enforcement". The adapter must stamp the CheckResources
 * {@code policyVersion} from the request's {@link TenantExecutionContext#activePolicyVersion()}, so a
 * promoted tenant is evaluated against its own bundle. The default tenant (and any null/unresolved
 * context) must still send {@code "default"} — the base bundle — so the single-tenant demo is preserved
 * byte-for-byte.
 */
class CerbosEntitlementAdapterPolicyVersionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void promotedTenantContextStampsItsActiveBundleVersion() {
        try (StubHttpServer stub = new StubHttpServer()) {
            AtomicReference<String> body = capturePost(stub);
            CerbosEntitlementAdapter cerbos = adapter(stub);

            TenantExecutionContext promoted = TenantExecutionContext.of("acme", "acme", "b_xyz");
            cerbos.checkAgents(principal(), List.of(agent("agent-1")), promoted);

            JsonNode sent = read(body.get());
            assertThat(sent.path("principal").path("policyVersion").asText())
                    .as("principal.policyVersion must be the tenant's active bundle version").isEqualTo("b_xyz");
            assertThat(sent.path("resources").path(0).path("resource").path("policyVersion").asText())
                    .as("resource.policyVersion must be the tenant's active bundle version").isEqualTo("b_xyz");
        }
    }

    @Test
    void defaultTenantContextStampsDefault() {
        try (StubHttpServer stub = new StubHttpServer()) {
            AtomicReference<String> body = capturePost(stub);
            CerbosEntitlementAdapter cerbos = adapter(stub);

            TenantExecutionContext defaultTenant = TenantExecutionContext.of("default", "default", "default");
            cerbos.checkAgents(principal(), List.of(agent("agent-1")), defaultTenant);

            assertThat(read(body.get()).path("resources").path(0).path("resource").path("policyVersion").asText())
                    .isEqualTo("default");
        }
    }

    @Test
    void nullContextFallsBackToDefault() {
        try (StubHttpServer stub = new StubHttpServer()) {
            AtomicReference<String> body = capturePost(stub);
            CerbosEntitlementAdapter cerbos = adapter(stub);

            // The no-context overload (existing callers) — must be byte-identical to before: "default".
            cerbos.checkAgents(principal(), List.of(agent("agent-1")));

            JsonNode sent = read(body.get());
            assertThat(sent.path("principal").path("policyVersion").asText()).isEqualTo("default");
            assertThat(sent.path("resources").path(0).path("resource").path("policyVersion").asText())
                    .isEqualTo("default");
        }
    }

    @Test
    void blankVersionContextFallsBackToDefault() {
        try (StubHttpServer stub = new StubHttpServer()) {
            AtomicReference<String> body = capturePost(stub);
            CerbosEntitlementAdapter cerbos = adapter(stub);

            // A half-populated context (tenant set, version blank) must fail safe to the base bundle.
            TenantExecutionContext blank = new TenantExecutionContext("acme", "acme", "  ");
            cerbos.checkRelationships(principal(), List.of("rel-1"), blank);

            assertThat(read(body.get()).path("resources").path(0).path("resource").path("policyVersion").asText())
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
            assertThat(body).as("the adapter must have POSTed a request body").isNotNull();
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static CerbosEntitlementAdapter adapter(StubHttpServer stub) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(2000))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(2000));
        RestClient restClient = RestClient.builder().requestFactory(factory).baseUrl(stub.baseUrl()).build();
        OutboundGate gate = new OutboundGate(new SimpleMeterRegistry(), 16, 250);
        return new CerbosEntitlementAdapter(restClient, new ObjectMapper(), gate,
                "127.0.0.1", stub.port(), 2000, "closed", "relationship");
    }

    static Principal principal() {
        return new Principal("rm_jane", List.of("relationship_manager"), List.of(), Map.of(), List.of());
    }

    static AgentManifest agent(String id) {
        return new AgentManifest(id, id, null, null, null, null, null, null, null, "http",
                null, null, null, new Constraints("read", "internal", 5000),
                null, null, null, null, true, null);
    }
}
