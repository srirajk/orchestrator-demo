package ai.conduit.gateway.adapter.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Streamable HTTP responds to a POST with EITHER a plain {@code application/json} body OR an
 * SSE-upgraded {@code text/event-stream} body carrying the JSON-RPC message as its {@code data:}
 * lines. The adapter must read the JSON-RPC payload out of both, and the error semantics
 * (protocol error vs. tool-execution {@code isError}) must hold identically regardless of framing.
 */
class McpAdapterStreamableTest {

    private final McpAdapter adapter = new McpAdapter(new ObjectMapper());

    @Test
    void extractsJsonPayloadFromSingleSseEvent() {
        String sse = "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"protocolVersion\":\"2025-11-25\"}}\n"
                + "\n";
        assertThat(McpAdapter.extractSseJson(sse))
                .isEqualTo("{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"protocolVersion\":\"2025-11-25\"}}");
    }

    @Test
    void extractsLastEventWhenStreamHasSeveral() {
        String sse = "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n"
                + "\n"
                + "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":\"9\",\"result\":{\"content\":[]}}\n"
                + "\n";
        assertThat(McpAdapter.extractSseJson(sse)).contains("\"id\":\"9\"");
    }

    @Test
    void extractsFinalEventWithoutTrailingBlankLine() {
        String sse = "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"ok\":true}}";
        assertThat(McpAdapter.extractSseJson(sse)).contains("\"ok\":true");
    }

    @Test
    void sseWithNoDataPayloadIsAnError() {
        assertThatThrownBy(() -> McpAdapter.extractSseJson("event: ping\n\n"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("no data payload");
    }

    @Test
    void toolResultParsesIdenticallyViaSseFraming() throws Exception {
        // The same successful tool result, delivered SSE-framed, must parse to the same data.
        String sse = "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"content\":[{\"type\":\"text\","
                + "\"text\":\"{\\\"nav\\\":128.45,\\\"currency\\\":\\\"USD\\\"}\"}]}}\n"
                + "\n";
        ObjectMapper m = new ObjectMapper();
        JsonNode root = m.readTree(McpAdapter.extractSseJson(sse));
        JsonNode data = adapter.extractResult(root);
        assertThat(data.path("nav").asDouble()).isEqualTo(128.45);
        assertThat(data.path("currency").asText()).isEqualTo("USD");
    }

    @Test
    void toolExecutionErrorViaSseFramingStillThrows() {
        // isError=true delivered over SSE framing is still a failure, not data (hard rule c).
        String sse = "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"content\":[{\"type\":\"text\","
                + "\"text\":\"llm_unavailable\"}],\"isError\":true}}\n"
                + "\n";
        assertThatThrownBy(() -> adapter.extractResult(new ObjectMapper().readTree(McpAdapter.extractSseJson(sse))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("MCP tool error")
                .hasMessageContaining("llm_unavailable");
    }
}
