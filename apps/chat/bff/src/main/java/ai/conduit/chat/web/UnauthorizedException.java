package ai.conduit.chat.web;

/**
 * Thrown when the caller has no valid authentication context or no usable access token
 * (e.g. the session was lost, or the OIDC access token expired and could not be refreshed).
 * Maps to HTTP <b>401</b> — distinct from {@link GatewayException} (502, gateway reachability)
 * — so the SPA, which only re-logins on 401, is driven back through the login flow rather than
 * shown a spurious "gateway error".
 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }

    public UnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
