package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.domain.auth.CerbosEntitlementAdapter;
import ai.conduit.gateway.domain.auth.JwksClient;
import ai.conduit.gateway.domain.coverage.CoverageClient;
import ai.conduit.gateway.domain.intent.Intent;
import ai.conduit.gateway.domain.intent.IntentClassifier;
import ai.conduit.gateway.domain.intent.IntentResult;
import ai.conduit.gateway.registry.model.AgentManifest;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared full-context harness for the capability-disambiguation clarify tests (task #62). Routing, intent,
 * coverage and Cerbos are mocked so the {@link ChatService} abstain-triage dispositions are observable
 * deterministically; the real Spring context + isolated Redis (via {@link RedisContainerTest}) back the
 * dual-plane store and descriptor lifecycle. Concrete subclasses supply the {@code resolveContextual} stub
 * and the assertions. Not a test class itself (no {@code Test} suffix ⇒ surefire ignores it); the shared
 * {@code @MockBean}/{@code @TestPropertySource} signature lets Spring cache ONE context across subclasses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "conduit.registry.readiness.enabled=false",
        "conduit.embedding.provider=hash"
})
abstract class CapabilityClarifyFixture extends RedisContainerTest {

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

    // Two ENTERPRISE-audience capabilities: open knowledge services with no per-user entity, so the
    // required-entity triage cleanly declines (requiresCoverage=false) and the disposition is a genuine
    // capability ambiguity — WHICH capability, not WHICH entity. name/description are the manifest copy the
    // form must surface (World-B: all offered copy comes from the manifest, never hardcoded in the gateway).
    static AgentManifest enterpriseAgent(String id, String name, String description) {
        return new AgentManifest(
                id, name, description, "1.0.0", null,
                "hr", "enterprise", null, null, "http",
                null, new AgentManifest.Capabilities(false, false), List.of(),
                new AgentManifest.Constraints("read", "internal", 5_000),
                null, null, null, null, true, null);
    }

    static final AgentManifest POLICY = enterpriseAgent(
            "meridian.hr.policy", "HR Policy Assistant", "Answers questions about HR policies and benefits");
    static final AgentManifest PAYROLL = enterpriseAgent(
            "meridian.hr.payroll", "Payroll Assistant", "Explains payroll schedules and pay statements");

    @BeforeEach
    void wireBase() {
        when(jwksClient.getPublicKey("k1")).thenReturn((RSAPublicKey) keyPair.getPublic());
        when(jwksClient.getPublicKey(argThat(k -> !"k1".equals(k)))).thenReturn(null);
        when(intentClassifier.classify(any())).thenReturn(new IntentResult(
                Intent.FETCH_DATA, 0.9, "fetch", EntityBag.empty()));
    }

    /** Cerbos allows every candidate agent. */
    void cerbosAllowAll() {
        when(cerbosAdapter.checkAgents(any(), any())).thenAnswer(inv -> {
            List<AgentManifest> ms = inv.getArgument(1);
            Map<String, Boolean> decisions = new HashMap<>();
            if (ms != null) ms.forEach(m -> decisions.put(m.agentId(), true));
            return new CerbosEntitlementAdapter.BatchResult(decisions, "cerbos");
        });
    }

    String mintToken() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("emp_jane")
                .issuer("http://iam-service:8084")
                .audience(List.of("conduit-gateway", "conduit-gateway@default"))
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
                .issueTime(new Date())
                .claim("roles", List.of("employee"))
                .claim("segments", Map.of("hr", "internal"))
                // A2: single-tenant demo tenant, so the request path's tenant resolution passes
                // (provisioned) instead of failing closed on a tenant-less token.
                .claim("tenant_id", "default")
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return jwt.serialize();
    }

    String ask(String content) throws Exception {
        return perform(content, null, null, null);
    }

    /** Perform a chat turn with optional conversation id + resume headers; returns the JSON body. */
    String perform(String content, String conversationId, String clarifyNonce, String clarifySelection)
            throws Exception {
        String body = """
                { "model": "conduit-assistant", "stream": false,
                  "messages": [{"role": "user", "content": "%s"}] }
                """.formatted(content);
        MockHttpServletRequestBuilder req = post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + mintToken())
                .content(body);
        if (conversationId != null) req = req.header("X-Conversation-Id", conversationId);
        if (clarifyNonce != null) req = req.header("X-Clarify-Nonce", clarifyNonce);
        if (clarifySelection != null) req = req.header("X-Clarify-Selection", clarifySelection);
        MvcResult res = mvc.perform(req).andExpect(status().isOk()).andReturn();
        return res.getResponse().getContentAsString();
    }
}
