package ai.conduit.chat.web;

/** Thrown when a resource does not exist or is not owned by the current user (→ HTTP 404). */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
