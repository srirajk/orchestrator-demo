package ai.conduit.gateway.adapter.mcp;

import ai.conduit.gateway.adapter.PayloadHandle;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.testsupport.PayloadTestSupport;
import ai.conduit.gateway.testsupport.StubHttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F4 harness test 4 — an MCP {@code resource_link} is fetched EAGERLY inside the adapter invoke, stamped
 * (carrying {@code _verified_sub}) and hashable; a {@code resource_link} WITHOUT a uri THROWS (replacing
 * the old silent {@code .orElse(result)} fall-through), so the harness records a FAILED node. Provenance
 * of the fetched payload is {@code FETCHED} (TOFU).
 */
class McpAdapterResourceLinkTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private McpAdapter adapter() {
        // no-store spiller (spill disabled): the fetched tree stays Inline but keeps FETCHED provenance.
        return new McpAdapter(mapper, PayloadTestSupport.spiller(null, -1L),
                "2025-11-25", "2024-11-05", 5000, 5000);
    }

    @Test
    void resourceLinkIsFetchedStampedAndFetchedProvenance() throws Exception {
        try (StubHttpServer stub = new StubHttpServer()) {
            stub.handle("/mcp", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("X-Conduit-Verified-Sub", "user-42");
                if (body.contains("\"initialize\"")) {
                    exchange.getResponseHeaders().add("Mcp-Session-Id", "sess-1");
                    respondJson(exchange, "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":"
                            + "{\"protocolVersion\":\"2025-11-25\"}}");
                } else {
                    // tools/call → a resource_link the gateway must fetch.
                    String uri = stub.baseUrl() + "/resource";
                    respondJson(exchange, "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"content\":"
                            + "[{\"type\":\"resource_link\",\"uri\":\"" + uri + "\",\"name\":\"r\"}]}}");
                }
            });
            stub.json("/resource", 200, "{\"figure\":100,\"holdings\":[{\"id\":\"X-1\"}]}");

            AgentManifest manifest = McpEndToEndDeadlineTest.streamableAgent(stub.baseUrl() + "/mcp", 5000);

            // invoke(): the fetched content is returned as the stamped tree.
            JsonNode tree = adapter().invoke(manifest, input(), "tok");
            assertThat(tree.path("figure").asInt()).isEqualTo(100);
            assertThat(tree.path("_verified_sub").asText())
                    .as("fetched content is stamped with the verified sub (identity survives materialize)")
                    .isEqualTo("user-42");

            // invokeHandle(): FETCHED provenance (TOFU); spill disabled → Inline over the same tree.
            PayloadHandle handle = adapter().invokeHandle(manifest, input(), "tok");
            assertThat(handle).isInstanceOf(PayloadHandle.Inline.class);
            assertThat(handle.provenance()).isEqualTo(PayloadHandle.Provenance.FETCHED);
            assertThat(handle.tree().path("figure").asInt()).isEqualTo(100);
        }
    }

    @Test
    void resourceLinkWithoutUriThrows() {
        try (StubHttpServer stub = new StubHttpServer()) {
            stub.handle("/mcp", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (body.contains("\"initialize\"")) {
                    exchange.getResponseHeaders().add("Mcp-Session-Id", "sess-1");
                    respondJson(exchange, "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":"
                            + "{\"protocolVersion\":\"2025-11-25\"}}");
                } else {
                    respondJson(exchange, "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"content\":"
                            + "[{\"type\":\"resource_link\",\"name\":\"r-no-uri\"}]}}");
                }
            });

            AgentManifest manifest = McpEndToEndDeadlineTest.streamableAgent(stub.baseUrl() + "/mcp", 5000);
            assertThatThrownBy(() -> adapter().invoke(manifest, input(), "tok"))
                    .as("a resource_link with no uri must throw → FAILED node, not a silent fall-through")
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("no uri");
        }
    }

    private ObjectNode input() {
        return mapper.createObjectNode().put("q", "x");
    }

    private static void respondJson(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var os = exchange.getResponseBody()) { os.write(bytes); }
    }
}
