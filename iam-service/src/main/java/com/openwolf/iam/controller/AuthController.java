package com.openwolf.iam.controller;

import com.openwolf.iam.auth.TenantClaims;
import com.openwolf.iam.dto.DelegationRequest;
import com.openwolf.iam.dto.LoginResponse;
import com.openwolf.iam.dto.ImpersonateRequest;
import com.openwolf.iam.dto.TokenRequest;
import com.openwolf.iam.dto.UserResponse;
import com.openwolf.iam.entity.Principal;
import com.openwolf.iam.entity.Role;
import com.openwolf.iam.exception.EntityNotFoundException;
import com.openwolf.iam.repository.PrincipalRepository;
import com.openwolf.iam.service.AuditService;
import com.openwolf.iam.service.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    @Value("${spring.security.oauth2.authorizationserver.issuer:http://localhost:8084}")
    private String issuerUrl;

    @Value("${iam.oauth2.gateway.audience:conduit-gateway}")
    private String gatewayAudienceRaw;

    @Value("${iam.oauth2.coverage.audience:conduit-coverage}")
    private String coverageAudienceRaw;

    @Value("${iam.auth.token-ttl-seconds:3600}")
    private long tokenTtlSeconds;

    @Value("${iam.auth.impersonation-ttl-seconds:900}")
    private long impersonationTtlSeconds;

    // Cross-tenant admin delegation (contract #7). Short-lived and hard-capped at 15 minutes;
    // a configured value above the cap is clamped, never honored.
    @Value("${iam.auth.delegation-ttl-seconds:900}")
    private long delegationTtlSeconds;

    // Admin-only base audience for delegation tokens. Deliberately NOT the gateway resource
    // audience, so ordinary chat/data endpoints reject a delegation token on audience grounds
    // alone (defence in depth on top of the typ check).
    @Value("${iam.oauth2.delegation.audience:conduit-admin}")
    private String delegationAudienceRaw;

    /** Maximum delegation-token lifetime (contract #7: ≤15 minutes). */
    private static final long DELEGATION_TTL_CAP_SECONDS = 900L;

    /** JOSE {@code typ} that types a cross-tenant delegation token (rejected by data endpoints). */
    private static final String DELEGATION_TOKEN_TYPE = "tenant-delegation+jwt";

    private final AuthenticationManager authenticationManager;
    private final JwtEncoder jwtEncoder;
    private final PrincipalRepository principalRepository;
    private final AuditService auditService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtEncoder jwtEncoder,
                          PrincipalRepository principalRepository,
                          AuditService auditService,
                          UserService userService,
                          ObjectMapper objectMapper) {
        this.authenticationManager = authenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.principalRepository = principalRepository;
        this.auditService = auditService;
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
     * {@code POST /auth/impersonate} — admin-only, short-lived persona token exchange for
     * Workbench demos. The returned token is a real Axiom JWT for the selected subject;
     * the gateway must receive it as Bearer auth and must never rely on act-as headers.
     */
    @PostMapping("/auth/impersonate")
    @PreAuthorize("hasRole('platform_admin')")
    @Transactional
    public ResponseEntity<LoginResponse> impersonate(
            @Valid @RequestBody ImpersonateRequest req,
            HttpServletRequest httpReq) {
        Principal subject = principalRepository.findById(req.userId())
                .or(() -> principalRepository.findByUsername(req.userId()))
                .orElseThrow(() -> EntityNotFoundException.forId("User", req.userId()));

        if (!subject.isActive()) {
            throw new IllegalArgumentException("Cannot impersonate inactive user: " + subject.getId());
        }

        ResponseEntity<LoginResponse> response = issueTokenForPrincipal(subject, impersonationTtlSeconds, "impersonation");
        auditService.log("default", auditService.currentActor(),
                "IMPERSONATE_USER", "user", subject.getId(), null,
                Map.of("subject", userService.toUserResponse(subject), "expiresIn", impersonationTtlSeconds),
                httpReq);
        return response;
    }

    /**
     * {@code POST /auth/delegation} — mints a cross-tenant admin <b>delegation token</b>
     * (contract #7). Admin-only and audited. The token is <em>separately typed</em>
     * ({@code typ=tenant-delegation+jwt}) and carries an <b>admin-only audience</b>
     * ({@code conduit-admin@<subject-tenant>}), so ordinary chat/data endpoints reject it — it is
     * NOT a general bearer and grants no wildcard cross-tenant bypass.
     *
     * <p>There is no caller-supplied tenant: the delegation's {@code tenant_id} is derived from the
     * target subject's home tenant. The actor (admin) identity + home tenant come from the caller's
     * own verified token. TTL is hard-capped at 15 minutes.
     */
    @PostMapping("/auth/delegation")
    @PreAuthorize("hasRole('platform_admin')")
    @Transactional
    public ResponseEntity<LoginResponse> delegate(
            @Valid @RequestBody DelegationRequest req,
            HttpServletRequest httpReq) {
        Principal subject = principalRepository.findById(req.subjectUserId())
                .or(() -> principalRepository.findByUsername(req.subjectUserId()))
                .orElseThrow(() -> EntityNotFoundException.forId("User", req.subjectUserId()));

        // Tenant of the subject being administered — mandatory + canonical, no default.
        String subjectTenant = TenantClaims.requireTenant(subject.getTenantId());

        // Actor = the calling admin, resolved from the verified security context (never the body).
        String actorSub = auditService.currentActor();
        Principal actor = principalRepository.findById(actorSub)
                .or(() -> principalRepository.findByUsername(actorSub))
                .orElseThrow(() -> EntityNotFoundException.forId("User", actorSub));
        String actorTenant = TenantClaims.requireTenant(actor.getTenantId());

        long ttl = Math.min(delegationTtlSeconds, DELEGATION_TTL_CAP_SECONDS);
        Instant now = Instant.now();
        String delegationId = UUID.randomUUID().toString();

        // Typed header so the token is self-describing on the wire; verifiers reject this typ on
        // the data path.
        JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256)
                .type(DELEGATION_TOKEN_TYPE)
                .build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuerUrl)
                .subject(subject.getId())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(ttl))
                .audience(TenantClaims.gatewayAudiences(delegationAudiences(), subjectTenant))
                .claim("typ", DELEGATION_TOKEN_TYPE)
                .claim("tenant_id", subjectTenant)
                .claim("actor_tenant_id", actorTenant)
                .claim("actor_sub", actorSub)
                .claim("delegation_id", delegationId)
                .claim("purpose", req.purpose())
                .build();

        String tokenValue = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();

        auditService.log(subjectTenant, actorSub,
                "TENANT_DELEGATION_ISSUED", "tenant", subjectTenant, null,
                Map.of("delegationId", delegationId, "actorTenant", actorTenant,
                        "subject", subject.getId(), "purpose", req.purpose(), "expiresIn", ttl),
                httpReq);

        log.info("Issued tenant-delegation token id={} actor={}@{} subjectTenant={} ttl={}s",
                delegationId, actorSub, actorTenant, subjectTenant, ttl);

        // No UserResponse body — a delegation token is an admin credential, not a login.
        return ResponseEntity.ok(new LoginResponse(tokenValue, "Bearer", ttl, null));
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

        return issueTokenForPrincipal(principal, tokenTtlSeconds, "login");
    }

    private ResponseEntity<LoginResponse> issueTokenForPrincipal(Principal principal, long ttlSeconds, String purpose) {
        // A1: tenant_id is mandatory and non-defaultable. Resolve it up front — a principal with a
        // null/blank home tenant is un-mintable (requireTenant throws → 500), never coerced to a
        // default. The tenant claim and the tenant-qualified audience both derive from this one
        // value, so a token can never assert one tenant while being audienced for another.
        String tenantId = TenantClaims.requireTenant(principal.getTenantId());

        // Build JWT claims
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(ttlSeconds);

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

        String classification = extractString(principal.getAttributes(), "classification", "internal");
        // segments is a per-segment classification MAP {segment -> tier}. Numeric clearance is
        // dropped — the per-segment tier is the ceiling. Legacy array attrs auto-migrate to
        // {segment -> global classification} so an un-reseeded principal keeps its ceiling.
        Map<String, String> segments = extractSegmentMap(principal.getAttributes(), classification);

        UserResponse userResponse = userService.toUserResponse(principal);
        List<String> adminDomains = userResponse.adminDomains() != null ? userResponse.adminDomains() : List.of();

        // JWT carries identity + structural claims only. No book claim.
        // Book-of-business is enforced at runtime by the domain coverage service, not embedded in the token.
        JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuerUrl)
                .subject(principal.getId())
                .issuedAt(now)
                .expiresAt(expiry)
                .audience(TenantClaims.gatewayAudiences(accessAudiences(), tenantId))
                .claim("username", principal.getUsername())
                .claim("email", principal.getEmail())
                .claim("roles", roles)
                .claim("permissions", permissions)
                .claim("segments", segments)
                .claim("classification", classification)
                .claim("admin_domains", adminDomains)
                .claim("tenant_id", tenantId)
                .build();

        String tokenValue = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();

        log.info("Issued {} JWT for user id={} username={}", purpose, principal.getId(), principal.getUsername());

        return ResponseEntity.ok(new LoginResponse(tokenValue, "Bearer", ttlSeconds, userResponse));
    }

    private List<String> gatewayAudiences() {
        return Arrays.stream(gatewayAudienceRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private List<String> accessAudiences() {
        List<String> all = new ArrayList<>(gatewayAudiences());
        Arrays.stream(coverageAudienceRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .forEach(all::add);
        return List.copyOf(all);
    }

    private List<String> delegationAudiences() {
        return Arrays.stream(delegationAudienceRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private String extractString(String attributesJson, String key, String fallback) {
        if (attributesJson == null || attributesJson.isBlank()) return fallback;
        try {
            Map<String, Object> attrs = objectMapper.readValue(
                    attributesJson, new TypeReference<Map<String, Object>>() {});
            Object val = attrs.get(key);
            return val instanceof String s && !s.isBlank() ? s : fallback;
        } catch (Exception ex) {
            log.warn("Failed to extract {} from principal attributes: {}", key, ex.getMessage());
            return fallback;
        }
    }

    /**
     * Builds the per-segment classification map claim {@code {segment -> tier}} from the
     * principal's JSON attributes. Preferred: {@code segments} stored as a JSON object.
     * Legacy tolerance: a JSON array maps each segment to {@code fallbackTier} (the global
     * classification), so an un-reseeded principal keeps an equivalent ceiling rather than
     * losing access. Returns an empty map if absent or parsing fails.
     */
    private Map<String, String> extractSegmentMap(String attributesJson, String fallbackTier) {
        Map<String, String> out = new LinkedHashMap<>();
        if (attributesJson == null || attributesJson.isBlank()) return out;
        try {
            Map<String, Object> attrs = objectMapper.readValue(
                    attributesJson, new TypeReference<Map<String, Object>>() {});
            Object val = attrs.get("segments");
            if (val instanceof Map<?, ?> m) {
                m.forEach((k, v) -> {
                    if (k != null && v != null) out.put(k.toString(), v.toString());
                });
            } else if (val instanceof List<?> list) {
                String tier = (fallbackTier == null || fallbackTier.isBlank()) ? "internal" : fallbackTier;
                for (Object s : list) {
                    if (s != null) out.put(s.toString(), tier);
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to extract segments from principal attributes: {}", ex.getMessage());
        }
        return out;
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
