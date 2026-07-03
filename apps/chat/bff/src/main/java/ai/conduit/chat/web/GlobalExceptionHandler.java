package ai.conduit.chat.web;

import ai.conduit.chat.files.StorageException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Central mapping of exceptions to consistent JSON error responses. Never leaks
 * stack traces to the client; logs at a level appropriate to the failure.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("Not found", ex.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("Bad request", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("Bad request", detail));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("Payload too large", "Uploaded file exceeds the maximum allowed size"));
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ErrorResponse> handleStorage(StorageException ex) {
        log.error("[storage] {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("Storage error", "File storage is unavailable"));
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGateway(GatewayException ex, HttpServletResponse response) {
        if (response.isCommitted()) {
            // Failure surfaced after the SSE response was already committed — writing a JSON
            // body now would only produce an HttpMessageNotWritableException. Swallow.
            log.debug("[gateway] after response committed (stream in progress): {}", ex.getMessage());
            return null;
        }
        log.error("[gateway] {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("Gateway error", ex.getMessage()));
    }

    /**
     * The client disconnected mid-stream (aborted SSE). This is normal user behaviour
     * (pressing "stop"); log at debug and swallow.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException ex) {
        log.debug("[stream] client disconnected: {}", ex.getMessage());
    }

    /**
     * The async (SSE) request timed out or its body could no longer be written — the response
     * is already a committed {@code text/event-stream}. There is nothing safe to write back, so
     * log at debug and swallow rather than letting Spring attempt a JSON error body.
     */
    @ExceptionHandler({AsyncRequestTimeoutException.class, HttpMessageNotWritableException.class})
    public void handleStreamWriteFailure(Exception ex) {
        log.debug("[stream] write/timeout after commit: {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletResponse response) {
        if (response.isCommitted()) {
            // Never attempt to serialize a JSON ErrorResponse into an already-committed
            // text/event-stream response (would throw HttpMessageNotWritableException). Swallow.
            log.debug("[unexpected] after response committed (stream in progress): {}", ex.getMessage());
            return null;
        }
        log.error("[unexpected] {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Internal error", "An unexpected error occurred"));
    }
}
