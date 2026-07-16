package ai.conduit.gateway.infrastructure.payload;

import ai.conduit.gateway.infrastructure.objectstore.ObjectStorePayloadStore;

import ai.conduit.gateway.adapter.PayloadHandle;
import ai.conduit.gateway.testsupport.StubHttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4 harness test 7 — a bounded connection pool with a bounded acquisition wait. With {@code maxConnections=1}
 * against a store that holds its one connection open (a stub that never responds within the socket timeout),
 * the SECOND concurrent materialize cannot acquire a connection and fails within the bounded acquisition
 * wait — it does NOT hang. Both callers return within their bounds (deadline honoured, no runaway).
 *
 * <p>Uses a slow in-process HTTP endpoint instead of the (offline-unavailable) Testcontainers toxiproxy
 * module — the property under test is the CLIENT's bounded pool/acquire behaviour, which needs only a
 * stalled server.
 */
class PayloadStorePoolSaturationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void secondConcurrentMaterializeRejectsWithinAcquireWait() throws Exception {
        try (StubHttpServer stalled = new StubHttpServer()) {
            // Accept the connection, then hold it (sleep past the socket timeout) so caller #1 keeps the
            // single pooled connection leased while caller #2 tries to acquire.
            stalled.handle("/", exchange -> {
                try { Thread.sleep(5_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                StubHttpServer.respond(exchange, 200, "application/json", "{}");
            });

            long socketTimeoutMs = 2_000;
            long acquireWaitMs = 400;
            ObjectStorePayloadStore store = new ObjectStorePayloadStore(
                    mapper, "sat-bucket", stalled.baseUrl(), "us-east-1", true, "k", "s",
                    30_000, 30_000, /*maxConnections*/ 1, acquireWaitMs,
                    500, socketTimeoutMs, 8_388_608);
            store.init();

            PayloadHandle.Ref ref = new PayloadHandle.Ref(URI.create("s3://sat-bucket/x"),
                    "x".repeat(64), 10, "application/json");

            ExecutorService pool = Executors.newFixedThreadPool(2);
            AtomicLong secondElapsed = new AtomicLong(-1);

            // Caller #1 leases the only connection and stays blocked on the stalled read.
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> {
                try { store.materialize(ref, null, MaterializeContext.withDeadline(0)); }
                catch (Exception ignored) { /* expected: socket timeout */ }
            }, pool);

            Thread.sleep(200);   // let caller #1 acquire the connection first

            long t0 = System.nanoTime();
            CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
                try { store.materialize(ref, null, MaterializeContext.withDeadline(0)); }
                catch (Exception ignored) { /* expected: connection acquisition timeout */ }
                finally { secondElapsed.set((System.nanoTime() - t0) / 1_000_000L); }
            }, pool);

            second.get(5, TimeUnit.SECONDS);   // MUST return — the whole point is "does not hang"
            first.get(6, TimeUnit.SECONDS);
            pool.shutdownNow();

            assertThat(secondElapsed.get())
                    .as("caller #2 rejects within the bounded acquire wait, not after the 5s stall")
                    .isGreaterThanOrEqualTo(0)
                    .isLessThan(acquireWaitMs + 1_500);
        }
    }
}
