package ai.meridian.gateway.domain.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;

/**
 * Verifies RS256 JWTs on every inbound request that supplies an Authorization: Bearer header.
 *
 * Verification:
 *   1. Signature — RS256 via JWKS public key (kid lookup)
 *   2. exp — token must not be expired
 *   3. iss — must be "meridian-user-mgmt"
 *   4. aud — must contain "meridian-gateway"
 *
 * If verification succeeds, stores the claims in a request attribute so
 * RequestCorrelationFilter can extract the verified sub without re-parsing.
 *
 * If no Bearer token is present, the filter passes through (trusted internal hop / health).
 * If a Bearer token is present but INVALID, returns HTTP 401 immediately.
 */
@Component
@Order(0)
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_JWT_CLAIMS   = "meridian.jwt.claims";
    public static final String ATTR_JWT_VERIFIED = "meridian.jwt.verified";

    private static final String EXPECTED_ISSUER   = "meridian-user-mgmt";
    private static final String EXPECTED_AUDIENCE  = "meridian-gateway";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwksClient jwksClient;

    public JwtAuthFilter(JwksClient jwksClient) {
        this.jwksClient = jwksClient;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Always pass health and model listing (no auth needed for readiness probes)
        return path.startsWith("/actuator") || path.equals("/v1/models");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // No JWT — trusted internal hop (LibreChat → gateway with X-User-Id)
            request.setAttribute(ATTR_JWT_VERIFIED, false);
            chain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7).trim();
        try {
            JWTClaimsSet claims = verifyToken(token);
            request.setAttribute(ATTR_JWT_CLAIMS, claims);
            request.setAttribute(ATTR_JWT_VERIFIED, true);
            chain.doFilter(request, response);
        } catch (JwtVerificationException e) {
            log.warn("JWT rejected: {}", e.getMessage());
            reject401(response, e.getMessage());
        }
    }

    private JWTClaimsSet verifyToken(String token) throws JwtVerificationException {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            JWSHeader header = jwt.getHeader();

            if (header.getAlgorithm() != JWSAlgorithm.RS256) {
                throw new JwtVerificationException("algorithm must be RS256, got " + header.getAlgorithm());
            }

            String kid = header.getKeyID();
            RSAPublicKey publicKey = jwksClient.getPublicKey(kid);
            if (publicKey == null) {
                throw new JwtVerificationException("unknown kid: " + kid);
            }

            RSASSAVerifier verifier = new RSASSAVerifier(publicKey);
            if (!jwt.verify(verifier)) {
                throw new JwtVerificationException("signature verification failed");
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();

            // exp
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.before(new Date())) {
                throw new JwtVerificationException("token expired");
            }

            // iss
            if (!EXPECTED_ISSUER.equals(claims.getIssuer())) {
                throw new JwtVerificationException("invalid iss: " + claims.getIssuer());
            }

            // aud
            List<String> aud = claims.getAudience();
            if (aud == null || !aud.contains(EXPECTED_AUDIENCE)) {
                throw new JwtVerificationException("invalid aud: " + aud);
            }

            return claims;
        } catch (JwtVerificationException e) {
            throw e;
        } catch (Exception e) {
            throw new JwtVerificationException("parse/verify error: " + e.getMessage());
        }
    }

    private void reject401(HttpServletResponse response, String reason) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"unauthorized\",\"reason\":\"" + reason + "\"}");
    }

    static class JwtVerificationException extends Exception {
        JwtVerificationException(String msg) { super(msg); }
    }
}
