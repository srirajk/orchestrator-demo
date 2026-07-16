package ai.conduit.gateway.infrastructure.payload;

import ai.conduit.gateway.infrastructure.objectstore.ObjectStorePayloadStore;

import ai.conduit.gateway.adapter.PayloadHandle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F4 harness test 8 (Tier-A) — the materialize heap guard. An object larger than
 * {@code max-materialize-bytes} with NO projecting select is refused with {@link PayloadTooLargeException}
 * BEFORE any store I/O (so this needs no container — the store is never contacted). A select present
 * means the caller bounded the result, so the cap does not trip.
 */
class MaterializeCapTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Construct the store with a tiny cap; do NOT call init() — the cap guard runs before touching S3. */
    private ObjectStorePayloadStore storeWithCap(long capBytes) {
        return new ObjectStorePayloadStore(
                mapper, "conduit-payload", "", "us-east-1", true, "", "",
                8000, 4000, 8, 2000, 2000, 4000, capBytes);
    }

    @Test
    void overCapWithoutSelectThrows() {
        ObjectStorePayloadStore store = storeWithCap(1024);
        PayloadHandle.Ref big = new PayloadHandle.Ref(URI.create("s3://b/x"), "sha-x", 2048, "application/json");

        assertThatThrownBy(() -> store.materialize(big, null, MaterializeContext.withDeadline(0)))
                .isInstanceOf(PayloadTooLargeException.class)
                .hasMessageContaining("exceeds max-materialize");
    }

    @Test
    void underCapDoesNotTripTheGuard() {
        ObjectStorePayloadStore store = storeWithCap(1_000_000);
        PayloadHandle.Ref small = new PayloadHandle.Ref(URI.create("s3://b/x"), "sha-x", 512, "application/json");

        // The cap guard must NOT be the thing that fails here (there is no store, so an I/O failure will
        // follow — but it is NOT a PayloadTooLargeException).
        assertThatThrownBy(() -> store.materialize(small, null, MaterializeContext.withDeadline(0)))
                .isNotInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void nullRefRejected() {
        ObjectStorePayloadStore store = storeWithCap(1024);
        assertThat(store).isNotNull();
        assertThatThrownBy(() -> store.materialize(null, null, MaterializeContext.withDeadline(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
