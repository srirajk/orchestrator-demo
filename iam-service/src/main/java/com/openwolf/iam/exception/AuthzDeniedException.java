package com.openwolf.iam.exception;

/**
 * Thrown by {@link com.openwolf.iam.service.CerbosAuthzService} when the Cerbos PDP
 * denies the requested action.
 * Maps to HTTP 403 in {@link GlobalExceptionHandler}.
 */
public class AuthzDeniedException extends RuntimeException {

    public AuthzDeniedException(String message) {
        super(message);
    }

    public AuthzDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
