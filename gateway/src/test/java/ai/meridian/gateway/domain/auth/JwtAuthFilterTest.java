package ai.meridian.gateway.domain.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
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

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that {@link JwtAuthFilter} correctly accepts and rejects RS256 JWTs.
 *
 * A throwaway 2048-bit RSA key pair is generated in {@code @BeforeAll}. The
 * {@link JwksClient} is replaced with a {@link MockBean} that returns the test
 * public key for {@code kid="test-key-1"} and {@code null} for any other kid,
 * so the test suite never requires the real user-mgmt service to be running.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthFilterTest {

    // ── Test RSA key pair (generated once per JVM run) ──────────────────────────

    static KeyPair keyPair;
    static RSAKey testRsaKey;

    @BeforeAll
    static void genKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        testRsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("test-key-1")
                .build();
    }

    // ── Spring context ───────────────────────────────────────────────────────────

    @Autowired
    MockMvc mvc;

    @MockBean
    JwksClient jwksClient;

    @BeforeEach
    void wireMockKey() {
        when(jwksClient.getPublicKey("test-key-1"))
                .thenReturn((RSAPublicKey) keyPair.getPublic());
        // Any kid that isn't "test-key-1" → null (unknown key)
        when(jwksClient.getPublicKey(argThat(k -> !"test-key-1".equals(k))))
                .thenReturn(null);
    }

    // ── Token minting helper ─────────────────────────────────────────────────────

    /**
     * Mints a signed RS256 JWT.
     *
     * @param sub           subject (userId)
     * @param expOffsetSecs seconds from now until expiry; negative → already expired
     * @param iss           issuer claim
     * @param aud           audience list
     * @param kid           key ID in JWS header
     * @param signingKey    RSA private key used to sign
     * @param book          relationship IDs for the {@code book} custom claim
     * @return serialised JWT string
     */
    private String mintToken(String sub, long expOffsetSecs, String iss, List<String> aud,
                             String kid, RSAPrivateKey signingKey, List<String> book) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(sub)
                .issuer(iss)
                .audience(aud)
                .expirationTime(new Date(System.currentTimeMillis() + expOffsetSecs * 1_000L))
                .issueTime(new Date())
                .claim("roles", List.of("relationship_manager"))
                .claim("book", book)
                .claim("clearance", 2)
                .build();

        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    /** Convenience overload — uses the default test key and kid. */
    private String validToken(String sub, List<String> book) throws Exception {
        return mintToken(sub, 3600, "meridian-user-mgmt", List.of("meridian-gateway"),
                "test-key-1", (RSAPrivateKey) keyPair.getPrivate(), book);
    }

    // ── /v1/models is excluded from JWT enforcement ──────────────────────────────

    @Test
    void noToken_modelsEndpoint_passesThrough() throws Exception {
        mvc.perform(get("/v1/models").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // ── Happy path ────────────────────────────────────────────────────────────────

    @Test
    void noAuthHeader_chatEndpoint_passesThrough() throws Exception {
        // No Authorization header → JwtAuthFilter sets verified=false and passes through.
        // ChatService may or may not return 200 depending on LLM availability; we only
        // assert that it is NOT a 401 (which would mean the filter rejected it).
        String body = """
                {"model":"meridian-assistant","messages":[{"role":"user","content":"hello"}],"stream":true}
                """;
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "anonymous")
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void validToken_accepted() throws Exception {
        String token = validToken("rm_jane", List.of("REL-00042", "REL-00099"));
        String body = """
                {"model":"meridian-assistant","messages":[{"role":"user","content":"hello"}],"stream":true}
                """;
        // A well-formed, signed, non-expired token with correct iss+aud → not 401
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(body))
                .andExpect(status().isOk());
    }

    // ── Rejection cases ───────────────────────────────────────────────────────────

    @Test
    void expiredToken_rejected() throws Exception {
        // expOffsetSecs = -3600 → expiry is 1 hour in the past
        String token = mintToken("rm_jane", -3600, "meridian-user-mgmt",
                List.of("meridian-gateway"), "test-key-1",
                (RSAPrivateKey) keyPair.getPrivate(), List.of("REL-00042"));
        String body = """
                {"model":"meridian-assistant","messages":[{"role":"user","content":"hello"}],"stream":true}
                """;
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"))
                .andExpect(jsonPath("$.reason").value(containsString("expired")));
    }

    @Test
    void wrongIss_rejected() throws Exception {
        String token = mintToken("rm_jane", 3600, "evil-idp",
                List.of("meridian-gateway"), "test-key-1",
                (RSAPrivateKey) keyPair.getPrivate(), List.of());
        String body = """
                {"model":"meridian-assistant","messages":[{"role":"user","content":"hello"}],"stream":true}
                """;
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value(containsString("invalid iss")));
    }

    @Test
    void wrongAud_rejected() throws Exception {
        String token = mintToken("rm_jane", 3600, "meridian-user-mgmt",
                List.of("wrong-service"), "test-key-1",
                (RSAPrivateKey) keyPair.getPrivate(), List.of());
        String body = """
                {"model":"meridian-assistant","messages":[{"role":"user","content":"hello"}],"stream":true}
                """;
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value(containsString("invalid aud")));
    }

    @Test
    void unknownKid_rejected() throws Exception {
        String token = mintToken("rm_jane", 3600, "meridian-user-mgmt",
                List.of("meridian-gateway"), "unknown-key-99",
                (RSAPrivateKey) keyPair.getPrivate(), List.of());
        String body = """
                {"model":"meridian-assistant","messages":[{"role":"user","content":"hello"}],"stream":true}
                """;
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value(containsString("unknown kid")));
    }

    @Test
    void wrongSignature_rejected() throws Exception {
        // Generate a completely separate key pair — sign with the attacker key but present
        // kid="test-key-1" so the filter looks up the legitimate public key, which doesn't
        // match the signature.
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair attackerPair = gen.generateKeyPair();

        String token = mintToken("rm_jane", 3600, "meridian-user-mgmt",
                List.of("meridian-gateway"), "test-key-1",
                (RSAPrivateKey) attackerPair.getPrivate(), List.of());
        String body = """
                {"model":"meridian-assistant","messages":[{"role":"user","content":"hello"}],"stream":true}
                """;
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value(containsString("signature verification failed")));
    }

    @Test
    void tamperedPayload_rejected() throws Exception {
        // Mint a valid token, then base64-decode the payload, mutate the sub, re-encode,
        // and rejoin — the signature no longer matches the new payload.
        String original = validToken("rm_jane", List.of("REL-00042"));
        String[] parts = original.split("\\.");

        // Decode the payload (URL-safe base64, no padding)
        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        String payload = new String(payloadBytes, java.nio.charset.StandardCharsets.UTF_8);

        // Replace "rm_jane" with "admin" to escalate privileges
        String mutated = payload.replace("rm_jane", "admin");
        String reencoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mutated.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Reassemble the JWT with the original header and signature but mutated payload
        String tampered = parts[0] + "." + reencoded + "." + parts[2];

        String body = """
                {"model":"meridian-assistant","messages":[{"role":"user","content":"hello"}],"stream":true}
                """;
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + tampered)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reason").value(containsString("signature verification failed")));
    }

    // ── Algorithm-confusion attacks ────────────────────────────────────────────

    @Test
    void algNone_rejected() throws Exception {
        // Craft a JWT with alg=none manually (PlainJWT) — a gateway that doesn't
        // check the algorithm header would accept any claims the attacker provides.
        // Our decoder calls SignedJWT.parse() which throws on a plain JWT → 401.
        String header  = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"kid\":\"test-key-1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("attacker").issuer("meridian-user-mgmt")
                .audience(List.of("meridian-gateway"))
                .expirationTime(new java.util.Date(System.currentTimeMillis() + 3_600_000L))
                .claim("roles", List.of("platform_admin")).build();
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(claims.toJSONObject().toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // alg=none tokens have an empty signature part
        String algNoneToken = header + "." + payload + ".";

        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + algNoneToken)
                        .content("{\"model\":\"meridian-assistant\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"stream\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void algHS256_rejected() throws Exception {
        // A JWT signed with HMAC-SHA256 — an attacker could forge this if they guessed
        // the key. Our decoder checks algorithm == RS256 and rejects everything else.
        byte[] secret = "32-byte-test-secret-do-not-use!!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("attacker").issuer("meridian-user-mgmt")
                .audience(List.of("meridian-gateway"))
                .expirationTime(new java.util.Date(System.currentTimeMillis() + 3_600_000L))
                .claim("roles", List.of("platform_admin")).build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("test-key-1").build();
        SignedJWT jwt = new SignedJWT(header, claims);
        jwt.sign(new MACSigner(secret));
        String algHs256Token = jwt.serialize();

        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + algHs256Token)
                        .content("{\"model\":\"meridian-assistant\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"stream\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingAuthHeader_chatEndpoint_passesThrough() throws Exception {
        // Explicitly no Authorization header — filter must not reject
        String body = """
                {"model":"meridian-assistant","messages":[{"role":"user","content":"hello"}],"stream":true}
                """;
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
