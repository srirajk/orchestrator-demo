package ai.conduit.chat.web;

/**
 * Consistent JSON error body. The {@code error} field preserves parity with the
 * reference Node BFF (which returned {@code {"error": "..."}}); {@code message}
 * carries a safe, human-readable detail. Stack traces are never exposed.
 */
public record ErrorResponse(String error, String message) {
}
