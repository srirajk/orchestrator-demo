package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.domain.auth.CerbosEntitlementAdapter;
import ai.conduit.gateway.domain.auth.JwksClient;
import ai.conduit.gateway.domain.coverage.CoverageCheckResult;
import ai.conduit.gateway.domain.coverage.CoverageClient;
import ai.conduit.gateway.domain.coverage.CoverageResolveResult;
import ai.conduit.gateway.domain.intent.Intent;
import ai.conduit.gateway.domain.intent.IntentClassifier;
import ai.conduit.gateway.domain.intent.IntentResult;
import ai.conduit.gateway.resolver.model.ResolverResult;
import ai.conduit.gateway.resolver.service.AgentResolver;
import ai.conduit.gateway.synthesis.input.EntityBag;
import ai.conduit.gateway.synthesis.input.Mention;
import ai.conduit.gateway.synthesis.input.MentionAligner;
import ai.conduit.gateway.synthesis.input.MentionSet;
import ai.conduit.gateway.synthesis.input.MentionSource;
import ai.conduit.gateway.synthesis.input.MentionSpan;
import ai.conduit.gateway.testsupport.RedisContainerTest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context wiring tests for the Piece-3 shared preparation pipeline as it reaches the router.
 * The router is mocked so the EXACT routing text + relaxation flag {@link RoutePreparer} hands to
 * {@code resolveContextual} are captured and asserted — proving, on the production path, that:
 *
 * <ul>
 *   <li><b>empty-residual widen</b> — a bare, in-book named ask ("Calderon Trust ?") is masked to a
 *       near-empty residual, so routing widens to the full masked window (which carries the prior
 *       turn's routable vocabulary) rather than clarifying — the mt_05 regression guard;</li>
 *   <li><b>#4 relaxation</b> — a terse non-coverage-scoped id follow-up ("and for FND-12345?") earns
 *       the bias-to-fetch relaxation deterministically, without any RESOLVED_ALLOWED focal;</li>
 *   <li><b>no unmasked probe</b> — a FOLLOW_UP fetch-fallthrough routes on the MASKED text, never the
 *       raw entity name, and grounding/masking runs once (the prepared memo is threaded through).</li>
 * </ul>
 *
 * <p>The router returns an abstain so the request terminates deterministically without invoking any
 * agent; the captured routing text is the assertion. World-B: the mask token is the config default.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "conduit.registry.readiness.enabled=false",
        "conduit.embedding.provider=hash"
})
class RoutePreparedRoutingTest extends RedisContainerTest {

    static KeyPair keyPair;

    @BeforeAll
    static void genKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
    }

    @Autowired MockMvc mvc;

    @MockBean JwksClient jwksClient;
    @MockBean CerbosEntitlementAdapter cerbosAdapter;
    @MockBean IntentClassifier intentClassifier;
    @MockBean AgentResolver resolver;
    @MockBean CoverageClient coverageClient;

    @BeforeEach
    void wire() {
        when(jwksClient.getPublicKey("k1")).thenReturn((RSAPublicKey) keyPair.getPublic());
        when(jwksClient.getPublicKey(argThat(k -> !"k1".equals(k)))).thenReturn(null);
        when(cerbosAdapter.checkAgents(any(), any())).thenAnswer(inv -> {
            List<ai.conduit.gateway.registry.model.AgentManifest> ms = inv.getArgument(1);
            Map<String, Boolean> decisions = new HashMap<>();
            if (ms != null) ms.forEach(m -> decisions.put(m.agentId(), true));
            return new CerbosEntitlementAdapter.BatchResult(decisions, "cerbos");
        });
        // S1c: mirror the stub for the ctx-aware overload the PRIMARY filterAgents gate now calls.
        when(cerbosAdapter.checkAgents(any(), any(), any())).thenAnswer(inv -> {
            List<ai.conduit.gateway.registry.model.AgentManifest> ms = inv.getArgument(1);
            Map<String, Boolean> decisions = new HashMap<>();
            if (ms != null) ms.forEach(m -> decisions.put(m.agentId(), true));
            return new CerbosEntitlementAdapter.BatchResult(decisions, "cerbos");
        });
        // Router abstains → the request ends deterministically; we assert the CAPTURED routing text.
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(
                new ResolverResult(List.of(), List.of(), true, 0.1, "q", 0.0, false));
    }

    private String mintToken() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("rm_jane")
                .issuer("http://iam-service:8084")
                .audience(List.of("conduit-gateway", "conduit-gateway@default"))
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
                .issueTime(new Date())
                .claim("roles", List.of("relationship_manager"))
                .claim("segments", Map.of("wealth", "confidential-pii"))
                .claim("tenant_id", "default")
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return jwt.serialize();
    }

    /** Sends a two-user-turn conversation (prior, then latest). */
    private void ask(String prior, String latest) throws Exception {
        String body = """
                { "model": "conduit-assistant", "stream": false,
                  "messages": [
                    {"role": "user", "content": "%s"},
                    {"role": "user", "content": "%s"}
                  ] }
                """.formatted(prior, latest);
        mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + mintToken())
                        .content(body))
                .andExpect(status().isOk());
    }

    /** An extracted bag naming one relationship reference, with a span aligned in the latest turn. */
    private static EntityBag bagWithRelationship(String verbatim, String latestContent, int latestIdx) {
        MentionSpan span = MentionAligner.align(latestContent, verbatim);
        Mention m = new Mention("relationship", "relationship_reference", verbatim, latestIdx, span,
                MentionSource.EXPLICIT);
        return EntityBag.of(Map.of("relationship_reference", verbatim), Map.of(), new MentionSet(List.of(m)));
    }

    private ArgumentCaptor<String> captureRoutingText() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(resolver, atLeastOnce()).resolveContextual(text.capture(), anyBoolean());
        return text;
    }

    private ArgumentCaptor<Boolean> captureRelaxation() {
        ArgumentCaptor<Boolean> flag = ArgumentCaptor.forClass(Boolean.class);
        verify(resolver, atLeastOnce()).resolveContextual(anyString(), flag.capture());
        return flag;
    }

    // ── mt_05: empty masked residual widens to the full masked window (does not clarify) ──────────

    @Test
    void bareInBookName_masksThenWidensToPriorTurnVocabulary() throws Exception {
        String prior = "show me the holdings";
        String latest = "Calderon Trust ?";
        when(intentClassifier.classify(any())).thenReturn(new IntentResult(
                Intent.FETCH_DATA, 0.9, "fetch", bagWithRelationship("Calderon Trust", latest, 1)));
        when(coverageClient.resolve(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new CoverageResolveResult(true, "REL-00099", "Calderon Trust", List.of()));
        when(coverageClient.check(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(CoverageCheckResult.ofAllowed());

        ask(prior, latest);

        String routed = captureRoutingText().getValue();
        assertThat(routed).contains("holdings");        // widened to the prior turn's routable vocab
        assertThat(routed).doesNotContain("Calderon");  // the entity name is masked, never routed on
        assertThat(captureRelaxation().getValue()).isTrue();   // RESOLVED_ALLOWED + masked → relaxed
    }

    // ── #4: a terse non-coverage-scoped id follow-up earns relaxation deterministically ───────────

    @Test
    void terseNonCoverageScopedId_earnsRelaxationViaDeterministicIdPattern() throws Exception {
        // corporate-actions (test manifest) is resource_scoped:false with fund_id id_pattern FND-\w+,
        // so this can never RESOLVED_ALLOWED-ground, yet the deterministic pattern match earns relaxation.
        when(intentClassifier.classify(any())).thenReturn(new IntentResult(
                Intent.FETCH_DATA, 0.9, "fetch", EntityBag.empty()));

        ask("what is the corporate action", "and for FND-12345?");

        assertThat(captureRelaxation().getValue()).isTrue();   // V2.1 #4 — deterministic, not presence-trust
        assertThat(captureRoutingText().getValue()).doesNotContain("Calderon");
    }

    // ── FOLLOW_UP: fetch-fallthrough routes on the masked text (no separate unmasked probe) ───────

    @Test
    void followUpFetchFallthrough_routesOnMaskedText() throws Exception {
        String prior = "show me the holdings";
        String latest = "how is Calderon Trust doing?";
        when(intentClassifier.classify(any())).thenReturn(new IntentResult(
                Intent.FOLLOW_UP, 0.9, "followup", bagWithRelationship("Calderon Trust", latest, 1)));
        when(coverageClient.resolve(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new CoverageResolveResult(true, "REL-00099", "Calderon Trust", List.of()));
        when(coverageClient.check(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(CoverageCheckResult.ofAllowed());

        ask(prior, latest);

        // The FOLLOW_UP probe must route on the MASKED text — no raw entity name in any routing call.
        for (String routed : captureRoutingText().getAllValues()) {
            assertThat(routed).doesNotContain("Calderon");
        }
    }
}
