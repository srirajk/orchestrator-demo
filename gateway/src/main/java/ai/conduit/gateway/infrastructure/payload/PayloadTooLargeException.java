package ai.conduit.gateway.infrastructure.payload;

/**
 * A full materialisation was refused because the object exceeds {@code conduit.payload.max-materialize-bytes}
 * and no projecting {@code select} was supplied to bound the in-memory result. With a {@code select}, the
 * store may stream/project instead of loading the whole object; without one, refusing protects the heap.
 */
public class PayloadTooLargeException extends Exception {
    public PayloadTooLargeException(String message) {
        super(message);
    }
}
