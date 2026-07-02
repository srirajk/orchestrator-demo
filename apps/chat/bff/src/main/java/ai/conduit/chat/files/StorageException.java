package ai.conduit.chat.files;

/** Raised when the object store cannot service a request (→ HTTP 502 via the handler). */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
