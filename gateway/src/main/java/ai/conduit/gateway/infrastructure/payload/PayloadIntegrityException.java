package ai.conduit.gateway.infrastructure.payload;

/**
 * The claim-check integrity guard failed: a materialised object's re-digest did not equal the
 * {@code Ref.sha256} it was fetched under (tamper, corruption, or a key collision). The node fails;
 * a wrong number never reaches synthesis.
 */
public class PayloadIntegrityException extends Exception {
    public PayloadIntegrityException(String message) {
        super(message);
    }
}
