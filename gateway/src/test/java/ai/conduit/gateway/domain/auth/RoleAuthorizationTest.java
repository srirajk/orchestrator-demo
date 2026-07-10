package ai.conduit.gateway.domain.auth;

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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 10 — Role-based endpoint authorization.
 *
 * <p>Verifies the coarse URL-rule matrix from {@link ai.conduit.gateway.config.SecurityConfig}
 * and the fine domain-scope check in {@link AgentAuthorization}.
 *
 * <ul>
 *   <li>No token → /admin/agents → 401</li>
 *   <li>relationship_manager → POST /admin/agents → 403</li>
 *   <li>domain_admin (wealth-private-banking) → register in own domain → 201/400 (valid token, not 403)</li>
 *   <li>domain_admin → register in foreign domain (intl-wealth) → 403</li>
 *   <li>platform_admin → register in any domain → 201/400 (not 403)</li>
 *   <li>relationship_manager → POST /v1/chat/completions → 200 (chat is permitAll)</li>
 *   <li>No token → /v1/chat/completions → 200 (trusted internal hop)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // The bootstrap loader writes the routing index into the resolved Redis, which on a
        // developer machine is the running demo's. A test must never re-index live routing data.
        "conduit.registry.bootstrap.enabled=false",
        // Keeps the context hermetic: no embedding sidecar required, nothing written to its cache.
        "conduit.embedding.provider=hash"
})
class RoleAuthorizationTest {

    static KeyPair keyPair;

    @BeforeAll
    static void genKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
    }

    @Autowired MockMvc mvc;
    @MockBean  JwksClient jwksClient;
    @MockBean  CerbosEntitlementAdapter cerbosAdapter;

    @BeforeEach
    void wireKey() {
        when(jwksClient.getPublicKey("role-test-key"))
                .thenReturn((RSAPublicKey) keyPair.getPublic());
        when(jwksClient.getPublicKey(argThat(k -> !"role-test-key".equals(k))))
                .thenReturn(null);
        // Default stub: Cerbos allows everything in tests focused on role/URL authorization
        when(cerbosAdapter.checkRelationships(any(), any()))
                .thenReturn(new CerbosEntitlementAdapter.BatchResult(Map.of(), "cerbos"));
    }

    // ── Token minting ──────────────────────────────────────────────────────────

    private String mint(String sub, List<String> roles, List<String> adminDomains) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(sub)
                .issuer("http://iam-service:8084")
                .audience(List.of("conduit-gateway"))
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
                .issueTime(new Date())
                .claim("roles", roles)
                .claim("admin_domains", adminDomains)
                .claim("clearance", 2)
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("role-test-key").build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return jwt.serialize();
    }

    // ── Admin endpoint: no token → 401 ────────────────────────────────────────

    @Test
    void noToken_adminEndpoint_returns401() throws Exception {
        mvc.perform(get("/admin/agents").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noToken_adminPost_returns401() throws Exception {
        mvc.perform(post("/admin/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── relationship_manager → admin plane → 403 ──────────────────────────────

    @Test
    void relationshipManager_adminGet_returns403() throws Exception {
        String token = mint("rm_jane", List.of("relationship_manager"), List.of());
        mvc.perform(get("/admin/agents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void relationshipManager_adminPost_returns403() throws Exception {
        String token = mint("rm_jane", List.of("relationship_manager"), List.of());
        mvc.perform(post("/admin/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"test-agent\",\"domain\":\"wealth-private-banking\"}"))
                .andExpect(status().isForbidden());
    }

    // ── domain_admin — own domain → accepted; foreign domain → 403 ───────────

    @Test
    void domainAdmin_ownDomain_notForbidden() throws Exception {
        String token = mint("da_wpb", List.of("domain_admin"),
                List.of("wealth-private-banking"));
        // The agent JSON may be invalid (registry will reject it with 400),
        // but we must NOT get 401/403 — the role+domain check passed.
        mvc.perform(post("/admin/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"test-agent\",\"domain\":\"wealth-private-banking\"}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assert status == 201 || status == 400 :
                            "Expected 201 or 400 (not 401/403), got " + status;
                });
    }

    @Test
    void domainAdmin_foreignDomain_returns403() throws Exception {
        String token = mint("da_wpb", List.of("domain_admin"),
                List.of("wealth-private-banking"));
        // Attempting to register in intl-wealth — not in adminDomains → 403
        mvc.perform(post("/admin/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"test-agent\",\"domain\":\"intl-wealth\"}"))
                .andExpect(status().isForbidden());
    }

    // ── platform_admin → any domain → accepted ────────────────────────────────

    @Test
    void platformAdmin_anyDomain_notForbidden() throws Exception {
        String token = mint("admin", List.of("platform_admin"), List.of());
        // Must pass the role + domain check (registry may reject the payload with 400 — that's ok)
        mvc.perform(post("/admin/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"test-agent\",\"domain\":\"intl-wealth\"}"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assert status == 201 || status == 400 :
                            "Expected 201 or 400 (not 401/403), got " + status;
                });
    }

    // ── Chat + trace — permitAll regardless of role ───────────────────────────

    @Test
    void relationshipManager_chat_returns200() throws Exception {
        String token = mint("rm_jane", List.of("relationship_manager"), List.of());
        mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"conduit-assistant\"," +
                                 "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]," +
                                 "\"stream\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void noToken_chat_returns200() throws Exception {
        // Trusted internal hop — LibreChat doesn't send a Bearer token
        mvc.perform(post("/v1/chat/completions")
                        .header("X-User-Id", "rm_jane")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"conduit-assistant\"," +
                                 "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]," +
                                 "\"stream\":true}"))
                .andExpect(status().isOk());
    }

    // ── BearerTokenResolver: LibreChat apiKey="unused" must NOT cause 401 ─────

    @Test
    void bearerUnused_chat_returns200() throws Exception {
        // LibreChat with apiKey: "unused" sends Authorization: Bearer unused.
        // The custom BearerTokenResolver must return null for this literal value,
        // allowing the request through to the permitAll chat endpoint.
        mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer unused")
                        .header("X-User-Id", "rm_jane")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"conduit-assistant\"," +
                                 "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]," +
                                 "\"stream\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void bearerEmpty_chat_returns200() throws Exception {
        mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer ")
                        .header("X-User-Id", "rm_jane")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"conduit-assistant\"," +
                                 "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]," +
                                 "\"stream\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void bearerNonJwt_chat_returns200() throws Exception {
        // Any non-JWT string (no dots) must also be ignored
        mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer not-a-jwt-at-all")
                        .header("X-User-Id", "rm_jane")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"conduit-assistant\"," +
                                 "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]," +
                                 "\"stream\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void oidcIssuer_validJwt_accepted() throws Exception {
        // Tokens issued by the OIDC endpoint use iss=OIDC_ISSUER ("http://iam-service:8084").
        // The gateway's multi-issuer list must accept them.
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("rm_jane")
                .issuer("http://iam-service:8084")   // OIDC issuer
                .audience(List.of("conduit-gateway"))
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
                .issueTime(new Date())
                .claim("roles", List.of("relationship_manager"))
                .claim("admin_domains", List.of())
                .claim("clearance", 2)
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("role-test-key").build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        String token = jwt.serialize();

        mvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"conduit-assistant\"," +
                                 "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]," +
                                 "\"stream\":true}"))
                .andExpect(status().isOk());
    }
}
