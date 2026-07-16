package ai.conduit.gateway.adapter.mcp;

import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.AgentManifest.Connection;
import ai.conduit.gateway.registry.model.AgentManifest.Constraints;
import ai.conduit.gateway.testsupport.StubHttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STATED behavior delta (F3): the whole invoke() shares ONE deadline. A slow-healthy agent whose
 * {@code initialize} eats ~0.8× SLA leaves {@code tools/call} only the remainder, so the invoke fails
 * inside the SLA (+500ms slack). Before the fix each of the two sequential legs got the FULL budget,
 * so worst case was ≈2× SLA — this test would then take ~1.6s and the ≤ SLA+500ms bound would fail.
 */
class McpEndToEndDeadlineTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void twoSlowLegsShareOneDeadlineAndFailInsideSla() {
        int slaMs = 1000;
        try (StubHttpServer stub = new StubHttpServer()) {
            stub.handle("/mcp", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                sleep(800);   // ~0.8× SLA on BOTH initialize and tools/call
                if (body.contains("\"initialize\"")) {
                    exchange.getResponseHeaders().add("Mcp-Session-Id", "sess-1");
                    respondJson(exchange, "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":"
                            + "{\"protocolVersion\":\"2025-11-25\"}}");
                } else {
                    respondJson(exchange, "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":"
                            + "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"ok\\\":true}\"}]}}");
                }
            });

            McpAdapter adapter = new McpAdapter(mapper, "2025-11-25", "2024-11-05", 10000, 10000);
            AgentManifest manifest = streamableAgent(stub.baseUrl() + "/mcp", slaMs);

            long t0 = System.nanoTime();
            assertThatThrownBy(() -> adapter.invoke(manifest, input(), "tok"))
                    .as("initialize consumes most of the shared budget → tools/call fails inside SLA");
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            assertThat(elapsedMs)
                    .as("must fail within SLA + 500ms (shared deadline), NOT ~2× SLA (per-leg budget)")
                    .isLessThan(slaMs + 500);
        }
    }

    private ObjectNode input() {
        return mapper.createObjectNode().put("q", "x");
    }

    static AgentManifest streamableAgent(String serverUrl, int slaMs) {
        return new AgentManifest("a1", "a1", null, null, null, null, null, null, null, "mcp",
                new Connection(null, null, serverUrl, "the_tool", "streamable", null),
                null, null, new Constraints("read", "internal", slaMs),
                null, null, null, null, true, null);
    }

    static void respondJson(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var os = exchange.getResponseBody()) { os.write(bytes); }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
