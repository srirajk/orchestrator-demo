package ai.conduit.chat.web;

/** Thrown when the Conduit gateway is unreachable or returns a non-2xx status (→ HTTP 502). */
public class GatewayException extends RuntimeException {
    public GatewayException(String message) {
        super(message);
    }

    public GatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
