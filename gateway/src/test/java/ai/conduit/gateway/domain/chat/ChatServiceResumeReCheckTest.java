package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.domain.auth.CerbosEntitlementAdapter;
import ai.conduit.gateway.domain.auth.JwksClient;
import ai.conduit.gateway.domain.clarify.ClarificationDescriptor;
import ai.conduit.gateway.domain.clarify.ClarificationDescriptorFactory;
import ai.conduit.gateway.domain.clarify.ClarificationDescriptorStore;
import ai.conduit.gateway.domain.coverage.CoverageCheckResult;
import ai.conduit.gateway.domain.coverage.CoverageClient;
import ai.conduit.gateway.domain.coverage.CoverageResolveResult;
import ai.conduit.gateway.domain.coverage.CoverageResource;
import ai.conduit.gateway.domain.intent.Intent;
import ai.conduit.gateway.domain.intent.IntentClassifier;
import ai.conduit.gateway.domain.intent.IntentResult;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase-2 RESUME end-to-end (full-context) proof: resuming a structured clarification re-drives the WHOLE
 * pipeline, so the entitlement CHECK re-runs at resume and is NEVER trusted from the form. An option that
 * was offered (entitled when the form was drawn) is DENIED at resume when the coverage CHECK now denies it,
 * and the single-use descriptor is burned so a replay finds nothing to consume.
 *
 * <p>The descriptor is seeded directly into the (real, Redis-backed) {@link ClarificationDescriptorStore};
 * the resume rides the normal chat path with {@code X-Clarify-Nonce} + {@code X-Clarify-Selection} headers,
 * exactly as the BFF's {@code /api/clarify/resolve} drives it. The LLM, router, coverage and Cerbos are
 * mocked so the disposition is deterministic. Requests use {@code stream:false} for a single-JSON body.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "conduit.registry.readiness.enabled=false",
        "conduit.embedding.provider=hash"
})
class ChatServiceResumeReCheckTest extends RedisContainerTest {

    static KeyPair keyPair;

    @BeforeAll
    static void genKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
    }

    @Autowired MockMvc mvc;
    @Autowired ClarificationDescriptorStore descriptorStore;
    @Autowired ClarificationDescriptorFactory descriptorFactory;

    @MockBean JwksClient jwksClient;
    @MockBean CerbosEntitlementAdapter cerbosAdapter;
    @MockBean IntentClassifier intentClassifier;
    @MockBean CoverageClient coverageClient;

    private static final String CONV = "conv-resume-recheck";

    @BeforeEach
    void wire() {
        when(jwksClient.getPublicKey("k1")).thenReturn((RSAPublicKey) keyPair.getPublic());
        when(jwksClient.getPublicKey(org.mockito.ArgumentMatchers.argThat(k -> !"k1".equals(k)))).thenReturn(null);
        when(cerbosAdapter.checkAgents(any(), any())).thenAnswer(inv -> {
            List<?> ms = inv.getArgument(1);
            Map<String, Boolean> decisions = new HashMap<>();
            if (ms != null) ms.forEach(m -> decisions.put(
                    ((ai.conduit.gateway.registry.model.AgentManifest) m).agentId(), true));
            return new CerbosEntitlementAdapter.BatchResult(decisions, "cerbos");
        });
        // S1c: mirror the stub for the ctx-aware overload the PRIMARY filterAgents gate now calls.
        when(cerbosAdapter.checkAgents(any(), any(), any())).thenAnswer(inv -> {
            List<?> ms = inv.getArgument(1);
            Map<String, Boolean> decisions = new HashMap<>();
            if (ms != null) ms.forEach(m -> decisions.put(
                    ((ai.conduit.gateway.registry.model.AgentManifest) m).agentId(), true));
            return new CerbosEntitlementAdapter.BatchResult(decisions, "cerbos");
        });
        // The classifier runs on the descriptor's ORIGINAL query — a vague in-domain fetch with no entity;
        // the resume injects the chosen id as the grounded reference.
        when(intentClassifier.classify(any())).thenReturn(new IntentResult(
                Intent.FETCH_DATA, 0.9, "fetch", EntityBag.empty()));
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
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return jwt.serialize();
    }

    /** Seed an outstanding entity clarification whose offered set includes REL-00042 (entitled when drawn). */
    private ClarificationDescriptor seedDescriptor() {
        ClarificationDescriptor d = descriptorFactory.forEntity(
                CONV, "Which client relationship?", "Which client relationship?\n- Whitman (REL-00042)",
                "client relationship", "REL-\\d+",
                List.of(new CoverageResource("REL-00042", "Whitman Family Office", "private-banking")),
                List.of(), "show me the holdings", 1);
        descriptorStore.store(d);
        return d;
    }

    private MvcResult resume(String nonce, String selection) throws Exception {
        String body = """
                { "model": "conduit-assistant", "stream": false,
                  "messages": [{"role": "user", "content": "%s"}] }
                """.formatted(selection);
        return mvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + mintToken())
                        .header("X-Conversation-Id", CONV)
                        .header("X-Clarify-Nonce", nonce)
                        .header("X-Clarify-Selection", selection)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void resume_reRunsCheck_deniedSelectionIsDenied_notTrustedFromForm() throws Exception {
        ClarificationDescriptor d = seedDescriptor();
        // RESOLVE is principal-agnostic; the CHECK now DENIES the offered id (e.g. book changed / TOCTOU).
        when(coverageClient.resolve(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new CoverageResolveResult(true, "REL-00042", "Whitman Family Office", List.of()));
        when(coverageClient.check(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(CoverageCheckResult.denied("not-covered"));

        String out = resume(d.nonce(), "REL-00042").getResponse().getContentAsString();

        // The form is never authorization: the resumed turn re-CHECKs and honours the denial.
        assertThat(out).contains("not in your coverage");
        // Single-use: the descriptor was consumed by the resume, so a replay finds nothing.
        assertThat(descriptorStore.peek(CONV)).isEmpty();
        assertThat(descriptorStore.consume(CONV, d.nonce())).isEmpty();
    }

    @Test
    void resume_replayOfBurnedNonce_hasNoOutstandingDescriptor() throws Exception {
        ClarificationDescriptor d = seedDescriptor();
        when(coverageClient.resolve(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new CoverageResolveResult(true, "REL-00042", "Whitman Family Office", List.of()));
        when(coverageClient.check(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(CoverageCheckResult.denied("not-covered"));

        // First resume consumes (burns) the descriptor.
        resume(d.nonce(), "REL-00042");
        // A replay presents the same, now-burned nonce — nothing to consume (single-use enforced).
        assertThat(descriptorStore.consume(CONV, d.nonce())).isEmpty();
    }
}
