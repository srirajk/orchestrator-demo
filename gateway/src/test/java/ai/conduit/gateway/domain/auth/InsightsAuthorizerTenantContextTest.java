package ai.conduit.gateway.domain.auth;

import ai.conduit.gateway.domain.insights.InsightsAuthorizer;
import ai.conduit.gateway.testsupport.StubHttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class InsightsAuthorizerTenantContextTest {

    @Test
    void insightsUsesAvailableTenantContextForScopedCerbosSelection() throws Exception {
        try (StubHttpServer stub = new StubHttpServer()) {
            AtomicReference<String> captured = new AtomicReference<>();
            stub.handle("/api/check/resources", exchange -> {
                captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                StubHttpServer.respond(exchange, 200, "application/json", """
                        {"results":[{"resource":{"id":"insights"},
                                      "actions":{"read":"EFFECT_ALLOW"}}]}
                        """);
            });

            InsightsAuthorizer authorizer = new InsightsAuthorizer(
                    CerbosEntitlementAdapterPolicyVersionTest.adapter(stub),
                    new SimpleMeterRegistry(), "insights", "read", "insights");

            boolean allowed = authorizer.canRead(
                    CerbosEntitlementAdapterPolicyVersionTest.principal(),
                    TenantExecutionContext.of("acme", "acme", "b_insights"));

            assertThat(allowed).isTrue();
            JsonNode sent = new ObjectMapper().readTree(captured.get());
            JsonNode resource = sent.path("resources").path(0).path("resource");
            assertThat(resource.path("policyVersion").asText()).isEqualTo("b_insights");
            assertThat(resource.path("scope").asText()).isEqualTo("acme");
            assertThat(resource.path("attr").path("tenant_id").asText()).isEqualTo("acme");
            assertThat(sent.path("principal").path("attr").path("tenant_id").asText()).isEqualTo("acme");
        }
    }

    @Test
    void insightsFailsClosedWhenTenantContextIsAbsentOrUnresolved() {
        try (StubHttpServer stub = new StubHttpServer()) {
            AtomicReference<String> captured = new AtomicReference<>();
            stub.handle("/api/check/resources", exchange -> {
                captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                StubHttpServer.respond(exchange, 200, "application/json", "{\"results\":[]}");
            });
            InsightsAuthorizer authorizer = new InsightsAuthorizer(
                    CerbosEntitlementAdapterPolicyVersionTest.adapter(stub),
                    new SimpleMeterRegistry(), "insights", "read", "insights");

            assertThat(authorizer.canRead(CerbosEntitlementAdapterPolicyVersionTest.principal())).isFalse();
            assertThat(authorizer.canRead(CerbosEntitlementAdapterPolicyVersionTest.principal(),
                    new TenantExecutionContext("acme", "acme", " "))).isFalse();
            assertThat(captured.get()).as("protected Insights never posts an unscoped request").isNull();
        }
    }
}
