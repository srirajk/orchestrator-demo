package ai.conduit.gateway.domain.auth;

import ai.conduit.gateway.config.SecurityConfig;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Axiom A1.3 — tenant confusion is rejected at verify. Even a token <b>correctly signed with
 * Axiom's key</b> is refused when its tenant-qualified audience {@code conduit-gateway@A} disagrees
 * with its {@code tenant_id} claim {@code B}. This is the gateway-side half of the tenant binding:
 * no minting bug or side-channel can present one tenant while being accepted for another.
 */
class JwtDecoderTenantConfusionTest {

    private static KeyPair axiomKeys;
    private JwtDecoder decoder;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        axiomKeys = gen.generateKeyPair();
    }

    @BeforeEach
    void buildDecoder() {
        JwksClient jwks = mock(JwksClient.class);
        when(jwks.getPublicKey("axiom-key-1")).thenReturn((RSAPublicKey) axiomKeys.getPublic());

        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(config, "requiredIssuersRaw", "http://iam-service:8084");
        ReflectionTestUtils.setField(config, "expectedAudience", "conduit-gateway");
        decoder = config.jwtDecoder(jwks);
    }

    private String sign(List<String> aud, String tenantId) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject("rm_jane")
                .issuer("http://iam-service:8084")
                .audience(aud)
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
                .issueTime(new Date())
                .claim("roles", List.of("relationship_manager"));
        if (tenantId != null) claims.claim("tenant_id", tenantId);
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("axiom-key-1").build();
        SignedJWT jwt = new SignedJWT(header, claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) axiomKeys.getPrivate()));
        return jwt.serialize();
    }

    @Test
    void audienceTenantMustMatchClaim() throws Exception {
        // aud says tenant A, claim says tenant B — correctly signed, still rejected.
        String confused = sign(List.of("conduit-gateway", "conduit-gateway@tenant-a"), "tenant-b");
        assertThatThrownBy(() -> decoder.decode(confused))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("tenant");
    }

    @Test
    void tokenWithoutTenantClaimIsRejected() throws Exception {
        String noTenant = sign(List.of("conduit-gateway"), null);
        assertThatThrownBy(() -> decoder.decode(noTenant))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void tokenWithoutTenantQualifiedAudienceIsRejected() throws Exception {
        // tenant_id present but only the bare audience — the tenant binding is unverifiable.
        String bare = sign(List.of("conduit-gateway"), "default");
        assertThatThrownBy(() -> decoder.decode(bare))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void matchingAudienceAndClaimDecodes() throws Exception {
        String ok = sign(List.of("conduit-gateway", "conduit-gateway@default"), "default");
        Jwt decoded = decoder.decode(ok);
        assertThat(decoded.getClaimAsString("tenant_id")).isEqualTo("default");
        assertThat(decoded.getAudience()).contains("conduit-gateway@default");
    }
}
