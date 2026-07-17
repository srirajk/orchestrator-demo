package ai.conduit.gateway.domain.auth;

import ai.conduit.gateway.config.SecurityConfig;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Axiom A1.4 — a cross-tenant delegation token cannot become a general bearer. The ordinary
 * (chat/data) verification path rejects it two ways: its admin-only audience never matches
 * {@code conduit-gateway@<tenant>}, and even a delegation token that (adversarially) carries the
 * gateway audience is still refused by the explicit {@code typ=tenant-delegation+jwt} guard.
 */
class DelegationTokenBoundaryTest {

    private static final String DELEGATION_TYPE = "tenant-delegation+jwt";

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

    private String signDelegation(List<String> aud, boolean typedHeader, boolean typClaim) throws Exception {
        JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("axiom-key-1");
        if (typedHeader) header.type(new JOSEObjectType(DELEGATION_TYPE));
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject("rm_jane")
                .issuer("http://iam-service:8084")
                .audience(aud)
                .expirationTime(new Date(System.currentTimeMillis() + 600_000L))
                .issueTime(new Date())
                .claim("tenant_id", "default")
                .claim("actor_tenant_id", "tenant-a")
                .claim("actor_sub", "admin_root")
                .claim("delegation_id", "deleg-123")
                .claim("purpose", "cross-tenant support");
        if (typClaim) claims.claim("typ", DELEGATION_TYPE);
        SignedJWT jwt = new SignedJWT(header.build(), claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) axiomKeys.getPrivate()));
        return jwt.serialize();
    }

    @Test
    void normalEndpointRejectsDelegationToken() throws Exception {
        // A realistic delegation token: admin-only audience, typed header + claim.
        String delegation = signDelegation(
                List.of("conduit-admin", "conduit-admin@default"), true, true);
        assertThatThrownBy(() -> decoder.decode(delegation))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void delegationTypeRejectedEvenIfItCarriesGatewayAudience() throws Exception {
        // Adversarial: a delegation token that also smuggles the gateway audience. The typ guard
        // (header) must reject it independently of the audience check.
        String smuggled = signDelegation(
                List.of("conduit-gateway", "conduit-gateway@default"), true, false);
        assertThatThrownBy(() -> decoder.decode(smuggled))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("delegation");
    }

    @Test
    void delegationTypClaimRejectedEvenIfItCarriesGatewayAudience() throws Exception {
        // Same, but the delegation marker is the mirrored `typ` claim rather than the JOSE header.
        String smuggled = signDelegation(
                List.of("conduit-gateway", "conduit-gateway@default"), false, true);
        assertThatThrownBy(() -> decoder.decode(smuggled))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("delegation");
    }
}
