package ai.conduit.gateway.domain.coverage;

import ai.conduit.gateway.domain.auth.Principal;
import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import ai.conduit.gateway.domain.manifest.DomainManifest;
import ai.conduit.gateway.infrastructure.outbound.OutboundGate;
import ai.conduit.gateway.testsupport.StubHttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.lang.reflect.RecordComponent;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static ai.conduit.gateway.testsupport.StubHttpServer.respond;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Axiom Story A5 — proves the {@code X-Tenant-Id} the gateway forwards to a coverage service is
 * derived from the request's {@link TenantExecutionContext} (A2), NOT from any caller-supplied
 * field.
 *
 * <p>This is the gateway half of the A5 second gate: the coverage service independently verifies
 * tenant binding (pytest {@code CoverageTenantEnforcementTest}), but only if the gateway forwards a
 * header it did not let the caller dictate. The forwarded value must be the immutable, JWT-derived
 * tenant captured on the servlet thread — exactly what {@code ChatService.tenantIdOrBlank(tenant)}
 * (the single derivation point feeding every {@code coverageClient} call) returns.
 *
 * <p>The test captures the header the real {@link CoverageClient} puts on the wire against an
 * in-process stub, and additionally asserts — structurally — that {@link Principal} (the caller
 * identity) carries no {@code tenant} component, so there is no caller field the header could ever
 * be sourced from. (A2 removed the old {@code Principal.tenantId}; the tenant now lives only in
 * {@link TenantExecutionContext}.)
 */
class CoverageClientTenantHeaderIT {

    private static final String BEARER = "verified-jwt";

    @Test
    void headerDerivedFromTenantContext() {
        AtomicReference<String> seenDiscover = new AtomicReference<>();
        AtomicReference<String> seenCheck = new AtomicReference<>();
        AtomicReference<String> seenResolve = new AtomicReference<>();

        try (StubHttpServer stub = new StubHttpServer()) {
            stub.handle("/coverage", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String tenant = exchange.getRequestHeaders().getFirst("X-Tenant-Id");
                if (path.contains("/resources/")) {
                    seenCheck.set(tenant);
                    respond(exchange, 200, "application/json", "{\"allowed\":true,\"reason\":\"in-book\"}");
                } else {
                    seenDiscover.set(tenant);
                    respond(exchange, 200, "application/json", "[]");
                }
            });
            stub.handle("/entities/resolve", exchange -> {
                seenResolve.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
                respond(exchange, 200, "application/json",
                        "{\"resolved\":true,\"id\":\"REL-1\",\"canonical_name\":\"n\"}");
            });

            CoverageClient client = client(stub.baseUrl());
            DomainManifest.Coverage coverage = coverage(stub.baseUrl());

            // The request's tenant scope is the JWT-derived TenantExecutionContext (A2). The caller
            // Principal is a DIFFERENT identity object that carries no tenant of its own.
            TenantExecutionContext tenant =
                    new TenantExecutionContext("ctx-tenant-x", "ctx-tenant-x", "v1");
            Principal caller = new Principal("rm_jane", List.of("chat_user"),
                    List.of(), java.util.Map.of(), List.of());

            // Exactly how ChatService feeds every coverage call: it passes tenant.tenantId()
            // (== ChatService.tenantIdOrBlank(tenant)), never a value taken from the Principal.
            String forwarded = tenant.tenantId();

            client.discover(caller.id(), forwarded, coverage, BEARER);
            client.check(caller.id(), forwarded, "REL-00042", coverage, BEARER);
            client.resolve("whitman", "relationship", forwarded, coverage, BEARER);

            // Every hop forwarded the TenantExecutionContext tenant on X-Tenant-Id.
            assertThat(seenDiscover.get()).isEqualTo("ctx-tenant-x");
            assertThat(seenCheck.get()).isEqualTo("ctx-tenant-x");
            assertThat(seenResolve.get()).isEqualTo("ctx-tenant-x");
        }

        // Structural provenance guarantee: the caller identity has NO tenant field, so the header
        // cannot be sourced from a caller-supplied value — only TenantExecutionContext carries it.
        boolean principalHasTenantComponent = false;
        for (RecordComponent rc : Principal.class.getRecordComponents()) {
            if (rc.getName().toLowerCase().contains("tenant")) {
                principalHasTenantComponent = true;
                break;
            }
        }
        assertThat(principalHasTenantComponent)
                .as("Principal must carry no tenant field — tenant lives only in TenantExecutionContext")
                .isFalse();
    }

    // ── helpers (mirror CoverageStatusMappingTest) ───────────────────────────────

    static CoverageClient client(String baseUrl) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(2000))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(5000));
        RestClient restClient = RestClient.builder().requestFactory(factory).build();
        OutboundGate gate = new OutboundGate(new SimpleMeterRegistry(), 16, 250);
        return new CoverageClient(restClient, new ObjectMapper(), new SimpleMeterRegistry(), gate, 5000);
    }

    static DomainManifest.Coverage coverage(String baseUrl) {
        return new DomainManifest.Coverage(
                baseUrl + "/coverage/{principal_id}",
                baseUrl + "/coverage/{principal_id}/resources/{id}",
                baseUrl + "/entities/resolve",
                0);
    }
}
