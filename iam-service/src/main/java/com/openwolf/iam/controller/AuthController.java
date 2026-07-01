package com.openwolf.iam.controller;

import com.openwolf.iam.dto.LoginResponse;
import com.openwolf.iam.dto.TokenRequest;
import com.openwolf.iam.dto.UserResponse;
import com.openwolf.iam.entity.Principal;
import com.openwolf.iam.entity.Role;
import com.openwolf.iam.repository.PrincipalRepository;
import com.openwolf.iam.service.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Custom authentication endpoints — issues RS256 JWTs directly.
 * These endpoints are public (no JWT required to call them).
 * <p>
 * Spring Authorization Server handles the OIDC-standard {@code /oauth/token} endpoint.
 * These endpoints are for clients that want a simpler username/password → token flow.
 * </p>
 */
@RestController
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final long TOKEN_TTL_SECONDS = 3600L;

    @Value("${spring.security.oauth2.authorizationserver.issuer:http://localhost:8084}")
    private String issuerUrl;

    @Value("${iam.oauth2.gateway.audience:conduit-gateway}")
    private String gatewayAudienceRaw;

    private final AuthenticationManager authenticationManager;
    private final JwtEncoder jwtEncoder;
    private final PrincipalRepository principalRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtEncoder jwtEncoder,
                          PrincipalRepository principalRepository,
                          UserService userService,
                          ObjectMapper objectMapper) {
        this.authenticationManager = authenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.principalRepository = principalRepository;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    /**
     * {@code POST /auth/login} — issues an RS256 JWT from username/password credentials.
     */
    @PostMapping("/auth/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody TokenRequest req) {
        return issueToken(req);
    }

    /**
     * {@code POST /auth/token} — identical to /auth/login, preserved for backwards compatibility.
     */
    @PostMapping("/auth/token")
    public ResponseEntity<LoginResponse> token(@Valid @RequestBody TokenRequest req) {
        return issueToken(req);
    }

    /**
     * {@code GET /.well-known/jwks.json} — redirect to Spring AS JWKS endpoint.
     * Spring Authorization Server serves the JWKS directly at /oauth2/jwks.
     */
    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Void> jwksRedirect() {
        return ResponseEntity.status(301)
                .location(URI.create("/oauth2/jwks"))
                .build();
    }

    // -------------------------------------------------------
    // Internal
    // -------------------------------------------------------

    private ResponseEntity<LoginResponse> issueToken(TokenRequest req) {
        // Authenticate — throws AuthenticationException on bad credentials (caught by GlobalExceptionHandler)
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.username(), req.password())
        );

        // The subject in the security context is the principal's ID (set in CustomUserDetailsService)
        String principalId = authentication.getName();

        Principal principal = principalRepository.findById(principalId)
                .or(() -> principalRepository.findByUsername(principalId))
                .orElseThrow(() -> new RuntimeException("Authenticated principal not found in database: " + principalId));

        // Build JWT claims
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(TOKEN_TTL_SECONDS);

        List<String> roles = principal.getRoles().stream()
                .map(Role::getName)
                .sorted()
                .toList();

        Set<String> permSet = new HashSet<>();
        for (Role role : principal.getRoles()) {
            // Permissions are stored as a JSON array string — we do a simple parse here
            // without full JSON deserialization to avoid circular dependencies
            permSet.addAll(parsePermissionsQuick(role.getPermissions()));
        }
        List<String> permissions = new ArrayList<>(permSet);
        Collections.sort(permissions);

        List<String> segments = extractSegments(principal.getAttributes());
        int clearance = extractClearance(principal.getAttributes());

        UserResponse userResponse = userService.toUserResponse(principal);
        List<String> adminDomains = userResponse.adminDomains() != null ? userResponse.adminDomains() : List.of();

        // JWT carries identity + structural claims only. No book claim.
        // Book-of-business is enforced at runtime by the domain coverage service, not embedded in the token.
        JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuerUrl)
                .subject(principalId)
                .issuedAt(now)
                .expiresAt(expiry)
                .audience(gatewayAudiences())
                .claim("username", principal.getUsername())
                .claim("email", principal.getEmail())
                .claim("roles", roles)
                .claim("permissions", permissions)
                .claim("segments", segments)
                .claim("clearance", clearance)
                .claim("admin_domains", adminDomains)
                .claim("tenant_id", "default")
                .build();

        String tokenValue = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();

        log.info("Issued JWT for user id={} username={}", principalId, principal.getUsername());

        return ResponseEntity.ok(new LoginResponse(tokenValue, "Bearer", TOKEN_TTL_SECONDS, userResponse));
    }

    private List<String> gatewayAudiences() {
        return Arrays.stream(gatewayAudienceRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    /**
     * Extracts the {@code clearance} integer from the principal's JSON attributes string.
     * Returns 1 (lowest) as a safe default if the attribute is absent or parsing fails.
     */
    private int extractClearance(String attributesJson) {
        if (attributesJson == null || attributesJson.isBlank()) return 1;
        try {
            Map<String, Object> attrs = objectMapper.readValue(
                    attributesJson, new TypeReference<Map<String, Object>>() {});
            Object val = attrs.get("clearance");
            if (val instanceof Number n) return n.intValue();
            return 1;
        } catch (Exception ex) {
            log.warn("Failed to extract clearance from principal attributes: {}", ex.getMessage());
            return 1;
        }
    }

    /**
     * Extracts the {@code segments} list from the principal's JSON attributes string.
     * Returns an empty list if the attribute is absent or parsing fails.
     */
    @SuppressWarnings("unchecked")
    private List<String> extractSegments(String attributesJson) {
        if (attributesJson == null || attributesJson.isBlank()) return List.of();
        try {
            Map<String, Object> attrs = objectMapper.readValue(
                    attributesJson, new TypeReference<Map<String, Object>>() {});
            Object val = attrs.get("segments");
            if (val instanceof List<?> list) {
                return list.stream()
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .toList();
            }
            return List.of();
        } catch (Exception ex) {
            log.warn("Failed to extract segments from principal attributes: {}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * Quick JSON array string parser — avoids requiring ObjectMapper injection.
     * Parses {@code ["a","b","c"]} style strings.
     */
    private List<String> parsePermissionsQuick(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) return List.of();
        try {
            // Simple approach: strip brackets, split by comma, remove quotes
            String trimmed = json.strip().replaceAll("^\\[|]$", "");
            if (trimmed.isBlank()) return List.of();
            String[] parts = trimmed.split(",");
            List<String> result = new ArrayList<>();
            for (String part : parts) {
                String clean = part.strip().replaceAll("^\"|\"$", "");
                if (!clean.isBlank()) result.add(clean);
            }
            return result;
        } catch (Exception ex) {
            log.warn("Failed to parse permissions JSON '{}': {}", json, ex.getMessage());
            return List.of();
        }
    }
}
