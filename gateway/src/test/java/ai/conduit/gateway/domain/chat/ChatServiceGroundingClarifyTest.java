package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.domain.auth.CerbosEntitlementAdapter;
import ai.conduit.gateway.domain.auth.JwksClient;
import ai.conduit.gateway.domain.coverage.CoverageCheckResult;
import ai.conduit.gateway.domain.coverage.CoverageClient;
import ai.conduit.gateway.domain.coverage.CoverageResolveResult;
import ai.conduit.gateway.domain.coverage.CoverageResource;
import ai.conduit.gateway.domain.intent.Intent;
import ai.conduit.gateway.domain.intent.IntentClassifier;
import ai.conduit.gateway.domain.intent.IntentResult;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.RoutingCandidate;
import ai.conduit.gateway.resolver.model.ResolverResult;
import ai.conduit.gateway.resolver.service.AgentResolver;
import ai.conduit.gateway.synthesis.input.EntityBag;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end (full-context) tests for the clarify-routing decouple through the real {@link ChatService}
 * orchestration and the real {@link ai.conduit.gateway.domain.manifest.DomainManifestStore} loaded from
 * the test manifests. The LLM (intent), router, coverage service and Cerbos PDP are mocked so the three
 * dispositions the decouple guarantees are observable deterministically:
 *
 * <ul>
 *   <li><b>A2</b> — a possessive, out-of-book named reference DENIES via Stage-1 grounding, before
 *       routing, and never clarifies.</li>
 *   <li><b>A6</b> — a vague-but-in-domain ask with no entity CLARIFIES with the caller's in-book
 *       options (the abstain triage), not a bare "no service".</li>
 *   <li><b>off-topic</b> — nothing groundable and no routable capability yields the clean no-service
 *       message, never a false clarify or deny.</li>
 * </ul>
 *
 * <p>Requests use {@code stream:false} so the controller returns the aggregated answer as a single
 * JSON body, which the assertions read directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "conduit.registry.readiness.enabled=false",
        "conduit.embedding.provider=hash"
})
class ChatServiceGroundingClarifyTest extends RedisContainerTest {

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

    // A resource-scoped wealth agent whose id maps (via the sub-domain agents[] list in
    // private-banking.json) to the private-banking sub-domain → getEffective resolves it without
    // needing sub_domain on the manifest.
    private static final AgentManifest HOLDINGS = new AgentManifest(
            "meridian.wealth.holdings", "Holdings", "Portfolio holdings", "1.0.0", null,
            "wealth-management", null, null, null, "http",
            null, new AgentManifest.Capabilities(false, false), List.of(),
            new AgentManifest.Constraints("read", "internal", 5_000),
            null, null, null, null, true, null);

    @BeforeEach
    void wire() {
        when(jwksClient.getPublicKey("k1")).thenReturn((RSAPublicKey) keyPair.getPublic());
        when(jwksClient.getPublicKey(argThat(k -> !"k1".equals(k)))).thenReturn(null);
        // Cerbos allows every agent invocation (structural gate not under test here).
        when(cerbosAdapter.checkAgents(any(), any())).thenAnswer(inv -> {
            List<AgentManifest> ms = inv.getArgument(1);
            Map<String, Boolean> decisions = new HashMap<>();
            if (ms != null) ms.forEach(m -> decisions.put(m.agentId(), true));
            return new CerbosEntitlementAdapter.BatchResult(decisions, "cerbos");
        });
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

    private String ask(String content) throws Exception {
        String body = """
                { "model": "conduit-assistant", "stream": false,
                  "messages": [{"role": "user", "content": "%s"}] }
                """.formatted(content);
        MvcResult res = mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + mintToken())
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return res.getResponse().getContentAsString();
    }

    // ── A2: possessive, out-of-book named reference → terminal DENY (never clarifies) ───────────

    @Test
    void a2_possessiveOutOfBookReference_deniesBeforeRouting() throws Exception {
        when(intentClassifier.classify(any())).thenReturn(new IntentResult(
                Intent.FETCH_DATA, 0.9, "fetch",
                EntityBag.of(Map.of("relationship_reference", "Okafor"), Map.of())));
        // RESOLVE is principal-agnostic; it resolves the name to a canonical id...
        when(coverageClient.resolve(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new CoverageResolveResult(true, "REL-00188", "Okafor Family Trust", List.of()));
        // ...and the coverage CHECK denies it (out of rm_jane's book).
        when(coverageClient.check(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(CoverageCheckResult.denied("not-covered"));

        String body = ask("what's in Okafor's portfolio");

        assertThat(body).contains("not in your coverage");            // private-banking not-covered copy
        assertThat(body).doesNotContain("Which client relationship");  // never a clarify
        org.mockito.Mockito.verifyNoInteractions(resolver);            // deny never transits the router
    }

    // ── A6: vague-but-in-domain ask, no entity → CLARIFY with the caller's in-book options ──────

    @Test
    void a6_vagueInDomainAsk_clarifiesWithInBookOptions() throws Exception {
        when(intentClassifier.classify(any())).thenReturn(new IntentResult(
                Intent.FETCH_DATA, 0.9, "fetch", EntityBag.empty()));
        // Routing abstains, but a resource-scoped holdings candidate is in the broad (skipped) set.
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(new ResolverResult(
                List.of(), List.of(new RoutingCandidate(HOLDINGS, 0.42)), true, 0.42, "q", 0.0, false));
        when(coverageClient.discover(anyString(), anyString(), any(), any())).thenReturn(List.of(
                new CoverageResource("REL-00042", "Whitman Family Office", "private-banking"),
                new CoverageResource("REL-00777", "Calderon Trust", "private-banking")));

        String body = ask("show me the holdings");

        assertThat(body).contains("Which client relationship");   // manifest clarification question
        assertThat(body).contains("Whitman Family Office");        // in-book options listed
        assertThat(body).contains("Calderon Trust");
        assertThat(body).doesNotContain("not in your coverage");   // not a denial
    }

    // ── off-topic: nothing groundable, nothing routable → clean no-service ──────────────────────

    @Test
    void offTopic_noGroundNoRoute_cleanNoService() throws Exception {
        when(intentClassifier.classify(any())).thenReturn(new IntentResult(
                Intent.FETCH_DATA, 0.9, "fetch", EntityBag.empty()));
        // Routing abstains and the broad set is empty → the triage has no candidate to clarify with.
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(new ResolverResult(
                List.of(), List.of(), true, 0.1, "q", 0.0, false));

        String body = ask("what is the meaning of life");

        assertThat(body).doesNotContain("Which client relationship");   // not a clarify
        assertThat(body).doesNotContain("not in your coverage");        // not a deny
        assertThat(body).doesNotContain("Whitman Family Office");        // no book leaked
        // The no-service message owns the limitation (manifest copy or the neutral fallback).
        assertThat(body.toLowerCase()).containsAnyOf("wasn't sure", "add a little detail", "able to answer");
    }
}
