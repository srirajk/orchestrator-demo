package ai.conduit.gateway.adapter.mcp;

import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.testsupport.StubHttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Legacy SSE with a hanging JSON-RPC POST: the SSE channel opens fine (endpoint event arrives), but
 * the session-path POST (initialize) never gets a response. Previously {@code postJson} used an
 * UNTIMED {@code httpClient.send()} that could park past the invoke deadline; now it is bounded by the
 * remaining budget and cancelled on expiry, so the invoke fails inside the SLA.
 */
class McpLegacySsePostHangTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void hangingSessionPostFailsInsideSla() {
        int slaMs = 800;
        try (StubHttpServer stub = new StubHttpServer()) {
            stub.handle("/sse", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                try {
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write("event: endpoint\ndata: /messages\n\n".getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        for (int i = 0; i < 1000; i++) {
                            os.write(":keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                            os.flush();
                            Thread.sleep(100);
                        }
                    }
                } catch (Exception ignored) { }
            });
            // The POST channel HANGS — never responds.
            stub.handle("/messages",
                    exchange -> { try { Thread.sleep(10_000); } catch (InterruptedException ignored) {} });

            McpAdapter adapter = new McpAdapter(mapper, "2025-11-25", "2024-11-05", 10000, 10000);
            AgentManifest manifest = McpSseSilentStreamTest.sseAgent(stub.baseUrl() + "/sse", slaMs);

            long t0 = System.nanoTime();
            assertThatThrownBy(() -> adapter.invoke(manifest, mapper.createObjectNode(), "tok"))
                    .as("a hanging session POST must not park past the deadline");
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            assertThat(elapsedMs).as("fails inside SLA + 800ms slack").isLessThan(slaMs + 800);
        }
    }
}
