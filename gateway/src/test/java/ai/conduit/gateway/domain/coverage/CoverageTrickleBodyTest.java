package ai.conduit.gateway.domain.coverage;

import ai.conduit.gateway.domain.manifest.DomainManifest;
import ai.conduit.gateway.infrastructure.outbound.OutboundGate;
import ai.conduit.gateway.testsupport.StubHttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The decisive trickle-body case: the server sends 200 headers promptly, then dribbles the body at
 * ~3 B/s. {@code HttpRequest.timeout()} bounds only time-to-headers, so a header-timeout-only fix
 * would wait on the body forever. The {@link OutboundGate} body-phase deadline catches it — the call
 * fails as {@link CoverageClient.CoverageUnavailableException} within the deadline (+1s slack), AND
 * the gate permit is released (inflight gauge back to 0), proving the cancellation path frees permits.
 */
class CoverageTrickleBodyTest {

    @Test
    void trickleBodyFailsClosedWithinDeadlineAndReleasesPermit() {
        long deadlineMs = 700;
        SimpleMeterRegistry gateRegistry = new SimpleMeterRegistry();
        OutboundGate gate = new OutboundGate(gateRegistry, 16, 250);

        try (StubHttpServer stub = new StubHttpServer()) {
            stub.handle("/resolve", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);   // headers now; chunked body follows
                byte[] payload = "{\"resolved\":true,\"id\":\"X-1\"}".getBytes();
                try (OutputStream os = exchange.getResponseBody()) {
                    for (byte b : payload) {
                        os.write(b);
                        os.flush();
                        Thread.sleep(300);              // ~3 B/s → body far exceeds the deadline
                    }
                } catch (Exception ignored) {
                    // client cancelled → the connection closed; expected
                }
            });

            CoverageClient client = client(stub.baseUrl(), deadlineMs, gate);
            DomainManifest.Coverage coverage = new DomainManifest.Coverage(
                    null, null, stub.baseUrl() + "/resolve", 0);

            long t0 = System.nanoTime();
            assertThatThrownBy(() -> client.resolve("ref", "type", "acme", coverage, "tok"))
                    .isInstanceOf(CoverageClient.CoverageUnavailableException.class);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            assertThat(elapsedMs)
                    .as("must fail within deadline + 1s slack (body-phase bound, not header-only)")
                    .isLessThan(deadlineMs + 1_000);

            String key = "http://127.0.0.1:" + stub.port();
            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(gateRegistry.get("conduit.outbound.gate.inflight")
                            .tag("gate", key).gauge().value()).isZero());
        }
    }

    private static CoverageClient client(String baseUrl, long deadlineMs, OutboundGate gate) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(2000))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(deadlineMs));
        RestClient restClient = RestClient.builder().requestFactory(factory).build();
        return new CoverageClient(restClient, new ObjectMapper(), new SimpleMeterRegistry(), gate, deadlineMs);
    }
}
