package ai.conduit.gateway.domain.auth;

import ai.conduit.gateway.testsupport.RedisContainerTest;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M15 security requirement: every bad token must be rejected.
 *
 * Tests on the ADMIN endpoint (which requires auth) and the CHAT endpoint
 * (permitAll, but a present+invalid JWT still triggers 401 from the resource server filter).
 *
 * Key invariant: sending a JWT-shaped token (3 dots, valid base64) with any invalid
 * claim causes the BearerTokenAuthenticationFilter to return 401 — even on permitAll
 * endpoints. Only tokens that bypass the resolver (empty, "unused", wrong format) skip
 * verification and proceed as anonymous.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // The gateway refuses to start without an ingested registry. A context test has no
        // ingestion job and must not depend on the developer's live Redis holding one.
        "conduit.registry.readiness.enabled=false",
        // Keeps the context hermetic: no embedding sidecar required, nothing written to its cache.
        "conduit.embedding.provider=hash"
})
class SecurityRejectionIT extends RedisContainerTest {

    static KeyPair validKeyPair;
    static KeyPair attackerKeyPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        validKeyPair = gen.generateKeyPair();
        attackerKeyPair = gen.generateKeyPair();
    }

    @Autowired MockMvc mvc;
    @MockBean  JwksClient jwksClient;
    @MockBean  CerbosEntitlementAdapter cerbosAdapter;

    @BeforeEach
    void wireKeys() {
        when(jwksClient.getPublicKey("test-key-1"))
                .thenReturn((RSAPublicKey) validKeyPair.getPublic());
        when(jwksClient.getPublicKey("attacker-key"))
                .thenReturn(null); // unknown kid → rejected
        when(jwksClient.getPublicKey(null))
                .thenReturn(null);
        when(cerbosAdapter.checkRelationships(any(), any()))
                .thenReturn(new CerbosEntitlementAdapter.BatchResult(Map.of(), "cerbos"));
    }

    // ── Token minting helpers ──────────────────────────────────────────────────

    private String mintValid(String sub) throws Exception {
        return mintWith(sub, "test-key-1", validKeyPair,
                new Date(System.currentTimeMillis() + 3_600_000L),
                "http://iam-service:8084", List.of("conduit-gateway"),
                List.of("relationship_manager"));
    }

    private String mintExpired(String sub) throws Exception {
        return mintWith(sub, "test-key-1", validKeyPair,
                new Date(System.currentTimeMillis() - 1_000L), // expired 1s ago
                "http://iam-service:8084", List.of("conduit-gateway"),
                List.of("relationship_manager"));
    }

    private String mintWrongAudience(String sub) throws Exception {
        return mintWith(sub, "test-key-1", validKeyPair,
                new Date(System.currentTimeMillis() + 3_600_000L),
                "http://iam-service:8084", List.of("wrong-audience"),
                List.of("relationship_manager"));
    }

    private String mintWrongIssuer(String sub) throws Exception {
        return mintWith(sub, "test-key-1", validKeyPair,
                new Date(System.currentTimeMillis() + 3_600_000L),
                "untrusted-issuer", List.of("conduit-gateway"),
                List.of("relationship_manager"));
    }

    private String mintWithAttackerKey(String sub) throws Exception {
        // Signed with attacker's private key — kid doesn't match any known key
        return mintWith(sub, "attacker-key", attackerKeyPair,
                new Date(System.currentTimeMillis() + 3_600_000L),
                "http://iam-service:8084", List.of("conduit-gateway"),
                List.of("relationship_manager"));
    }

    private String mintAndTamper(String sub) throws Exception {
        String valid = mintValid(sub);
        // Corrupt a character 50 positions INTO the signature section (well past the end-padding zone).
        // Changing the last base64url character of an RS256 signature silently passes Nimbus verification
        // because those are padding bits that decoders ignore. Corrupting deep inside the signature
        // changes real data bytes and reliably breaks RSA-SHA256 verification.
        int secondDot = valid.indexOf('.', valid.indexOf('.') + 1);
        int tamperPos = secondDot + 50;
        char orig = valid.charAt(tamperPos);
        char corrupted = (orig == 'A') ? 'B' : 'A';
        return valid.substring(0, tamperPos) + corrupted + valid.substring(tamperPos + 1);
    }

    private String mintWith(String sub, String kid, KeyPair kp,
                             Date exp, String iss, List<String> aud, List<String> roles) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(sub)
                .issuer(iss)
                .audience(aud)
                .expirationTime(exp)
                .issueTime(new Date())
                .claim("roles", roles)
                .claim("clearance", 2)
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) kp.getPrivate()));
        return jwt.serialize();
    }

    private static final String CHAT_BODY = """
            {"model":"conduit-assistant","messages":[{"role":"user","content":"hi"}],"stream":true}
            """;

    // ══════════════════════════════════════════════════════════════════════════
    // PART 1: Admin endpoint — JWT is REQUIRED
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void admin_noToken_returns401() throws Exception {
        mvc.perform(get("/admin/agents").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_expiredToken_returns401() throws Exception {
        mvc.perform(get("/admin/agents")
                        .header("Authorization", "Bearer " + mintExpired("rm_jane")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_wrongAudience_returns401() throws Exception {
        mvc.perform(get("/admin/agents")
                        .header("Authorization", "Bearer " + mintWrongAudience("rm_jane")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_wrongIssuer_returns401() throws Exception {
        mvc.perform(get("/admin/agents")
                        .header("Authorization", "Bearer " + mintWrongIssuer("rm_jane")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_unknownKid_returns401() throws Exception {
        mvc.perform(get("/admin/agents")
                        .header("Authorization", "Bearer " + mintWithAttackerKey("rm_jane")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_tamperedSignature_returns401() throws Exception {
        mvc.perform(get("/admin/agents")
                        .header("Authorization", "Bearer " + mintAndTamper("rm_jane")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_validToken_wrongRole_returns403() throws Exception {
        // relationship_manager has a valid token but lacks admin role → 403
        mvc.perform(get("/admin/agents")
                        .header("Authorization", "Bearer " + mintValid("rm_jane")))
                .andExpect(status().isForbidden());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PART 2: Chat endpoint — JWT-shaped invalid tokens still trigger 401
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void chat_expiredToken_returns401() throws Exception {
        // Even though chat is permitAll, an invalid-but-JWT-shaped token causes 401
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + mintExpired("rm_jane"))
                        .content(CHAT_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chat_wrongAudience_returns401() throws Exception {
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + mintWrongAudience("rm_jane"))
                        .content(CHAT_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chat_wrongIssuer_returns401() throws Exception {
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + mintWrongIssuer("rm_jane"))
                        .content(CHAT_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chat_tamperedSignature_returns401() throws Exception {
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + mintAndTamper("rm_jane"))
                        .content(CHAT_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chat_unknownKid_returns401() throws Exception {
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + mintWithAttackerKey("rm_jane"))
                        .content(CHAT_BODY))
                .andExpect(status().isUnauthorized());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PART 3: Non-JWT strings bypass the decoder (by the custom BearerTokenResolver)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void chat_noToken_returns200() throws Exception {
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "rm_jane")
                        .content(CHAT_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void chat_bearerUnused_returns200() throws Exception {
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer unused")
                        .header("X-User-Id", "rm_jane")
                        .content(CHAT_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void chat_jweFormat_returns200_notRejected() throws Exception {
        // JWE tokens have 4 dots (5 parts). Our BearerTokenResolver returns null for
        // dots != 2, so the request proceeds as anonymous (not rejected).
        String jweShapedToken = "aaa.bbb.ccc.ddd.eee"; // 4 dots = JWE-shaped
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + jweShapedToken)
                        .header("X-User-Id", "rm_jane")
                        .content(CHAT_BODY))
                .andExpect(status().isOk());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PART 4: Valid token is accepted
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    void chat_validToken_returns200() throws Exception {
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + mintValid("rm_jane"))
                        .content(CHAT_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void chat_oidcIssuer_validToken_returns200() throws Exception {
        // OIDC-issued tokens (iss=http://iam-service:8084) must also be accepted
        String oidcToken = mintWith("rm_jane", "test-key-1", validKeyPair,
                new Date(System.currentTimeMillis() + 3_600_000L),
                "http://iam-service:8084", List.of("conduit-gateway"),
                List.of("relationship_manager"));
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + oidcToken)
                        .content(CHAT_BODY))
                .andExpect(status().isOk());
    }
}
