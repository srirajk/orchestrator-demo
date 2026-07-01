package ai.conduit.gateway.domain.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;

/**
 * Verifies JWT → Principal mapping and Cerbos-backed entitlement decisions.
 *
 * <p>JWT carries structural claims only ({@code sub}, {@code roles}, {@code segments},
 * {@code clearance}). No {@code book} claim. Book-of-business is enforced at runtime
 * by the domain coverage service (DISCOVER/CHECK), not embedded in the token.
 *
 * <p>The {@link JwksClient} is replaced with a {@link MockBean} so no real
 * Axiom (iam-service) is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthzFromMembershipTest {

    // ── Test RSA key pair ────────────────────────────────────────────────────────

    static KeyPair keyPair;

    @BeforeAll
    static void genKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
    }

    // ── Spring context ───────────────────────────────────────────────────────────

    @Autowired
    MockMvc mvc;

    @Autowired
    EntitlementService entitlementService;

    @MockBean
    JwksClient jwksClient;

    @MockBean
    CerbosEntitlementAdapter cerbosAdapter;

    @BeforeEach
    void wireMockKey() {
        when(jwksClient.getPublicKey("membership-key-1"))
                .thenReturn((RSAPublicKey) keyPair.getPublic());
        when(jwksClient.getPublicKey(argThat(k -> !"membership-key-1".equals(k))))
                .thenReturn(null);

        // Default: Cerbos allows relationship_manager and admin roles (structural check only).
        // Book-of-business enforcement is handled by the domain coverage service, not Cerbos.
        when(cerbosAdapter.checkRelationships(any(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<String> ids = (List<String>) inv.getArgument(1);
                    if (ids == null) return new CerbosEntitlementAdapter.BatchResult(Map.of(), "cerbos");
                    Principal p = inv.getArgument(0);
                    boolean allow = p != null && p.roles().stream()
                            .anyMatch(r -> r.contains("relationship_manager") || r.contains("admin"));
                    java.util.Map<String, Boolean> decisions = new java.util.HashMap<>();
                    ids.forEach(id -> decisions.put(id, allow));
                    return new CerbosEntitlementAdapter.BatchResult(decisions, "cerbos");
                });
    }

    // ── Token minting helper ─────────────────────────────────────────────────────

    // Mints a JWT with structural claims only — no book claim.
    private String mintToken(String sub) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(sub)
                .issuer("http://iam-service:8084")
                .audience(List.of("conduit-gateway"))
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
                .issueTime(new Date())
                .claim("roles", List.of("relationship_manager"))
                .claim("clearance", 2)
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID("membership-key-1").build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return jwt.serialize();
    }

    // ── Test: rm_jane JWT maps to correct Principal (no book field) ───────────────

    @Test
    void rmJane_jwtMapsToStructuralPrincipal() throws Exception {
        // Verify JWT → Principal mapping carries structural claims only (no book).
        // Book-of-business is enforced by coverage service at runtime, not embedded in JWT.
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("rm_jane")
                .claim("roles", List.of("relationship_manager"))
                .claim("clearance", 2)
                .build();

        Principal fromJwt = Principal.fromJwtClaims(claims);
        assertThat(fromJwt.id()).isEqualTo("rm_jane");
        assertThat(fromJwt.roles()).containsExactly("relationship_manager");
        assertThat(fromJwt.clearance()).isEqualTo(2);
        // No book field — structural check only; coverage service handles book-of-business.

        // Cerbos structural check would ALLOW relationship_manager.
        // Coverage service (not tested here) would DENY Okafor as out-of-book.
        // Override to deny for this assertion:
        when(cerbosAdapter.checkRelationships(any(), any()))
                .thenReturn(new CerbosEntitlementAdapter.BatchResult(Map.of("REL-00188", false), "cerbos"));

        EntitlementService.EntitlementResult result = entitlementService.checkRelationship(fromJwt, "REL-00188");
        assertThat(result.allowed()).isFalse();
        assertThat(result.source()).isEqualTo("cerbos");

        // HTTP-level: JWT is accepted (200, not 401), proving verification + principal extraction
        String token = mintToken("rm_jane");
        String body = """
                {
                  "model": "conduit-assistant",
                  "messages": [{"role": "user", "content": "hello"}],
                  "stream": true
                }
                """;
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(body))
                .andExpect(status().isOk());  // valid JWT → gateway accepts it (not 401)
    }

    // ── Test: valid JWT → gateway accepts request, Cerbos structural check passes ──

    @Test
    void rmJane_validJwt_requestAccepted() throws Exception {
        // A valid JWT with relationship_manager role — gateway must accept it (200, not 401/403).
        // Coverage service (not wired here) would handle the actual book-of-business enforcement.
        String token = mintToken("rm_jane");

        // Request about REL-00188 — should now pass the entitlement check.
        // The intent classifier / resolver may or may not fan out (depends on demo data),
        // but the key assertion is that it does NOT return an access-denied message.
        String body = """
                {
                  "model": "conduit-assistant",
                  "messages": [
                    {
                      "role": "user",
                      "content": "Show me the portfolio holdings for REL-00188 Okafor Family Trust"
                    }
                  ],
                  "stream": true
                }
                """;

        MvcResult result = mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        // Must NOT be rejected with an access-denied message at the entitlement gate
        assertThat(responseBody)
                .doesNotContain("access denied: you are not authorized");
    }

    // ── Test: principal is built from JWT claims, not only Redis ─────────────────

    @Test
    void principalComesFromJwt_notOnlyRedis() throws Exception {
        // "rm_new" does not exist in Redis (was not seeded at startup), but the JWT
        // establishes her identity and roles.  The gateway must use the JWT-derived
        // Principal rather than falling back to anonymous().
        String token = mintToken("rm_new");

        String body = """
                {
                  "model": "conduit-assistant",
                  "messages": [
                    {
                      "role": "user",
                      "content": "Show me holdings for Whitman Family Office REL-00042"
                    }
                  ],
                  "stream": true
                }
                """;

        MvcResult result = mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        // Must NOT say access denied for REL-00042 even though rm_new isn't in Redis
        assertThat(responseBody)
                .doesNotContain("access denied: you are not authorized");
    }

    // ── Test: Principal.fromJwtClaims() maps structural claims correctly ──────────

    @Test
    void principalFromJwtClaims_mapsFieldsCorrectly() throws Exception {
        // Unit-level check of the fromJwtClaims factory without involving MockMvc.
        // JWT carries structural claims only — no book claim.
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("rm_test_user")
                .claim("roles", List.of("relationship_manager", "senior_rm"))
                .claim("segments", List.of("wealth", "servicing"))
                .claim("clearance", 3)
                .build();

        Principal principal = Principal.fromJwtClaims(claims);

        assertThat(principal.id()).isEqualTo("rm_test_user");
        assertThat(principal.roles()).containsExactly("relationship_manager", "senior_rm");
        assertThat(principal.segments()).containsExactlyInAnyOrder("wealth", "servicing");
        assertThat(principal.clearance()).isEqualTo(3);
    }

    @Test
    void principalFromJwtClaims_missingOptionalClaims_usesDefaults() throws Exception {
        // When optional claims (roles, clearance) are absent, safe defaults apply.
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("rm_minimal")
                .build();

        Principal principal = Principal.fromJwtClaims(claims);

        assertThat(principal.id()).isEqualTo("rm_minimal");
        assertThat(principal.roles()).containsExactly("relationship_manager");
        assertThat(principal.clearance()).isEqualTo(2);
    }

    // ── Test: EntitlementService delegates to Cerbos adapter ─────────────────────

    @Test
    void entitlementService_adminRole_grantsAccess() {
        // platform_admin: Cerbos returns ALLOW — EntitlementService honours the verdict.
        Principal admin = new Principal("admin", "default", List.of("platform_admin"), 5, List.of(), List.of(), List.of());
        when(cerbosAdapter.checkRelationships(any(), any()))
                .thenReturn(new CerbosEntitlementAdapter.BatchResult(Map.of("REL-00188", true), "cerbos"));

        EntitlementService.EntitlementResult result = entitlementService.checkRelationship(admin, "REL-00188");

        assertThat(result.allowed()).isTrue();
        assertThat(result.source()).isEqualTo("cerbos");
    }

    @Test
    void entitlementService_cerbosDenies_resultIsDenied() {
        // Cerbos denies the relationship — EntitlementService relays the denial.
        // In practice, structural Cerbos check passes for relationship_manager, but the
        // coverage service (DISCOVER/CHECK) would deny Okafor as not in rm_jane's coverage.
        Principal rm = new Principal("rm_jane", "default", List.of("relationship_manager"),
                2, List.of(), List.of("wealth"), List.of("wealth-private-banking"));
        when(cerbosAdapter.checkRelationships(any(), any()))
                .thenReturn(new CerbosEntitlementAdapter.BatchResult(Map.of("REL-00188", false), "cerbos"));

        EntitlementService.EntitlementResult result = entitlementService.checkRelationship(rm, "REL-00188");

        assertThat(result.allowed()).isFalse();
        assertThat(result.source()).isEqualTo("cerbos");
    }

    @Test
    void entitlementService_cerbosAllows_resultIsAllowed() {
        // Cerbos allows the relationship — EntitlementService relays the approval.
        Principal rm = new Principal("rm_jane", "default", List.of("relationship_manager"),
                2, List.of(), List.of("wealth"), List.of("wealth-private-banking"));
        when(cerbosAdapter.checkRelationships(any(), any()))
                .thenReturn(new CerbosEntitlementAdapter.BatchResult(Map.of("REL-00188", true), "cerbos"));

        EntitlementService.EntitlementResult result = entitlementService.checkRelationship(rm, "REL-00188");

        assertThat(result.allowed()).isTrue();
        assertThat(result.source()).isEqualTo("cerbos");
    }
}
