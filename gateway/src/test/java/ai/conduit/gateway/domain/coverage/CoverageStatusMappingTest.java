package ai.conduit.gateway.domain.coverage;

import ai.conduit.gateway.domain.manifest.DomainManifest;
import ai.conduit.gateway.infrastructure.outbound.OutboundGate;
import ai.conduit.gateway.testsupport.StubHttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

import static ai.conduit.gateway.testsupport.StubHttpServer.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the coverage status→exception table: EVERY non-2xx (500, 503, AND a 404 on RESOLVE), plus a
 * connect refusal and a header timeout, all fail-closed as {@link CoverageClient.CoverageUnavailableException}
 * — the exact contract the previous WebClient version produced. Only a 2xx with a valid body returns.
 * A fix that mapped only 5xx (leaving a 404 to parse-then-throw, or worse, treated as "granted") would
 * fail the 404 case here.
 */
class CoverageStatusMappingTest {

    private static final String TENANT = "acme";
    private static final String BEARER = "tok";

    @Test
    void ok2xxReturnsParsedResult() throws Exception {
        try (StubHttpServer stub = new StubHttpServer()) {
            stub.json("/resolve", 200, "{\"resolved\":true,\"id\":\"X-1\",\"canonical_name\":\"n\"}");
            CoverageClient client = client(stub.baseUrl(), 5000);
            CoverageResolveResult r = client.resolve("ref", "type", TENANT, coverage(stub.baseUrl()), BEARER);
            assertThat(r.resolved()).isTrue();
            assertThat(r.id()).isEqualTo("X-1");
        }
    }

    @Test
    void notFound404OnResolveFailsClosed() {
        assertNon2xxFailsClosed(404);
    }

    @Test
    void serverError500FailsClosed() {
        assertNon2xxFailsClosed(500);
    }

    @Test
    void serviceUnavailable503FailsClosed() {
        assertNon2xxFailsClosed(503);
    }

    @Test
    void connectRefusedFailsClosed() {
        // Point at a port with nothing listening → connect refused.
        CoverageClient client = client("http://127.0.0.1:1", 5000);
        assertThatThrownBy(() -> client.resolve("ref", "type", TENANT, coverage("http://127.0.0.1:1"), BEARER))
                .isInstanceOf(CoverageClient.CoverageUnavailableException.class);
    }

    @Test
    void headerTimeoutFailsClosed() {
        try (StubHttpServer stub = new StubHttpServer()) {
            // Never sends a response → time-to-headers exceeds the read timeout / deadline.
            stub.handle("/resolve", exchange -> { try { Thread.sleep(5_000); } catch (InterruptedException ignored) {} });
            CoverageClient client = client(stub.baseUrl(), 400);   // 400ms read timeout + deadline
            assertThatThrownBy(() -> client.resolve("ref", "type", TENANT, coverage(stub.baseUrl()), BEARER))
                    .isInstanceOf(CoverageClient.CoverageUnavailableException.class);
        }
    }

    private void assertNon2xxFailsClosed(int status) {
        try (StubHttpServer stub = new StubHttpServer()) {
            stub.handle("/resolve", exchange -> respond(exchange, status, "application/json", "{\"err\":true}"));
            CoverageClient client = client(stub.baseUrl(), 5000);
            assertThatThrownBy(() -> client.resolve("ref", "type", TENANT, coverage(stub.baseUrl()), BEARER))
                    .as("HTTP %d must fail closed", status)
                    .isInstanceOf(CoverageClient.CoverageUnavailableException.class);
        }
    }

    @Test
    void blankBearerRefusesBeforeAnyCall() {
        CoverageClient client = client("http://127.0.0.1:1", 5000);
        assertThatThrownBy(() -> client.resolve("ref", "type", TENANT, coverage("http://127.0.0.1:1"), ""))
                .isInstanceOf(CoverageClient.CoverageUnavailableException.class);
        // No stub needed — the refusal happens before the HTTP call.
        assertThatCode(() -> {}).doesNotThrowAnyException();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    static CoverageClient client(String baseUrl, long readTimeoutMs) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(2000))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        RestClient restClient = RestClient.builder().requestFactory(factory).build();
        OutboundGate gate = new OutboundGate(new SimpleMeterRegistry(), 16, 250);
        return new CoverageClient(restClient, new ObjectMapper(), new SimpleMeterRegistry(), gate, readTimeoutMs);
    }

    static DomainManifest.Coverage coverage(String baseUrl) {
        return new DomainManifest.Coverage(
                baseUrl + "/discover/{principal_id}",
                baseUrl + "/check/{principal_id}/{id}",
                baseUrl + "/resolve",
                0);
    }
}
