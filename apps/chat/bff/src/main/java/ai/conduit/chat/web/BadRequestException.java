package ai.conduit.chat.web;

/** Thrown for malformed / missing request input (→ HTTP 400). */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
