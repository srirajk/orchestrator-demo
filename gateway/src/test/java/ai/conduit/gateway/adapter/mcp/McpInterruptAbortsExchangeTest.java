package ai.conduit.gateway.adapter.mcp;

import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.testsupport.StubHttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cancellation must be REAL end-to-end: when the harness cancels an in-flight invoke (Future.cancel →
 * interrupt), {@code sendWithTimeout} aborts the exchange, and the STUB SERVER observes the connection
 * reset — proven server-side (a latch the server counts down when its write to the dropped client
 * fails), not by the local future's state. A fix that only unblocked the caller while leaking the
 * socket would never trip the server-side latch.
 */
class McpInterruptAbortsExchangeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void cancellingInvokeResetsTheServerConnection() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch resetObserved = new CountDownLatch(1);

        try (StubHttpServer stub = new StubHttpServer()) {
            stub.handle("/mcp", exchange -> {
                requestReceived.countDown();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                try {
                    exchange.sendResponseHeaders(200, 0);   // chunked; we dribble forever
                    try (OutputStream os = exchange.getResponseBody()) {
                        for (int i = 0; i < 1000; i++) {
                            os.write(' ');
                            os.flush();                     // throws once the client aborts
                            Thread.sleep(100);
                        }
                    }
                } catch (Exception clientGone) {
                    resetObserved.countDown();              // server saw the reset
                }
            });

            McpAdapter adapter = new McpAdapter(mapper, "2025-11-25", "2024-11-05", 30000, 10000);
            AgentManifest manifest = McpEndToEndDeadlineTest.streamableAgent(stub.baseUrl() + "/mcp", 30000);

            ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
            Future<JsonNode> f = exec.submit(() -> adapter.invoke(manifest, mapper.createObjectNode(), "tok"));

            assertThat(requestReceived.await(3, TimeUnit.SECONDS)).as("server received the request").isTrue();
            f.cancel(true);   // the harness's SLA-cancellation, simulated

            assertThat(resetObserved.await(1, TimeUnit.SECONDS))
                    .as("stub server must observe the connection reset within 1s of cancel")
                    .isTrue();
            exec.shutdownNow();
        }
    }
}
