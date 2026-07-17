package ai.conduit.gateway.api.v1.admin;

import ai.conduit.gateway.domain.auth.CerbosEntitlementAdapter;
import ai.conduit.gateway.domain.auth.JwksClient;
import ai.conduit.gateway.domain.chat.PreparedRoute;
import ai.conduit.gateway.domain.chat.RoutePreparer;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedReferenceSet;
import ai.conduit.gateway.domain.intent.Intent;
import ai.conduit.gateway.domain.intent.IntentClassifier;
import ai.conduit.gateway.domain.intent.IntentResult;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.RoutingCandidate;
import ai.conduit.gateway.resolver.model.ResolverResult;
import ai.conduit.gateway.resolver.service.AgentResolver;
import ai.conduit.gateway.synthesis.input.EntityBag;
import ai.conduit.gateway.testsupport.RedisContainerTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context tests for the Piece-6 production-path decision endpoint ({@code POST /debug/route},
 * {@link RouteDecisionController}). The route preparer + resolver are mocked so the projection is
 * deterministic, but the endpoint runs the REAL {@link ai.conduit.gateway.domain.chat.ChatService#decideRoute}
 * wiring — proving it (a) requires authentication, (b) accepts a NON-admin persona token, (c) stamps
 * {@code path == "production"} + the configured masking version, (d) hands the resolver the SAME masked
 * routing text the preparer produced (no forked masking), and (e) surfaces below-floor candidates + the
 * requested groups.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "conduit.registry.readiness.enabled=false",
        "conduit.embedding.provider=hash",
        "conduit.debug.route-decision.enabled=true",
        "conduit.routing.preparation-version=route-prep-test-v9"
})
class RouteDecisionControllerTest extends RedisContainerTest {

    static KeyPair keyPair;

    @BeforeAll
    static void genKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @MockBean JwksClient jwksClient;
    @MockBean CerbosEntitlementAdapter cerbosAdapter;
    @MockBean IntentClassifier intentClassifier;
    @MockBean AgentResolver resolver;
    @MockBean RoutePreparer routePreparer;

    private static final String MASKED = "risk tolerance for the subject";

    private static AgentManifest agent(String id, String domain, String subDomain) {
        AgentManifest.Skill skill = new AgentManifest.Skill(
                "skill", "skill", "d", List.of(), List.of("d"), List.of("text"), List.of("json"));
        return new AgentManifest(
                id, id, "d", "1.0.0", null, domain, null, subDomain, null, "http",
                null, new AgentManifest.Capabilities(false, false), List.of(skill),
                new AgentManifest.Constraints("read", "internal", 1_000),
                null, null, null, null, true, null);
    }

    @BeforeEach
    void wire() {
        when(jwksClient.getPublicKey("k1")).thenReturn((RSAPublicKey) keyPair.getPublic());
        when(jwksClient.getPublicKey(argThat(k -> !"k1".equals(k)))).thenReturn(null);

        when(intentClassifier.classify(any())).thenReturn(new IntentResult(
                Intent.FETCH_DATA, 0.9, "fetch", EntityBag.empty()));

        // The production preparer is mocked to a known masked text + empty grounded set (focal NONE);
        // the endpoint MUST hand THIS masked text to the resolver (no forked masking).
        PreparedRoute prepared = new PreparedRoute(
                MASKED, GroundedReferenceSet.none(), true,
                PreparedRoute.ResidualClass.HAS_ACTION,
                new PreparedRoute.MaskDiagnostics("masked-base", 1, 1, 0, "HAS_ACTION", "aa", "bb"));
        when(routePreparer.prepare(any(), anyString(), any(), anyBoolean(), anyBoolean(),
                any(), anyString(), any())).thenReturn(prepared);

        // One selected (above-floor) candidate + one skipped (below-floor) candidate.
        AgentManifest chosen = agent("meridian.wealth.risk_profile", "wealth-management", "private-banking");
        AgentManifest tail = agent("meridian.wealth.performance", "wealth-management", "private-banking");
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(new ResolverResult(
                List.of(new RoutingCandidate(chosen, 0.71)),
                List.of(new RoutingCandidate(tail, 0.22)),
                false, 0.71, MASKED, 0.30, false));

        // Cerbos allows the routed capability → SERVED disposition.
        when(cerbosAdapter.checkAgents(any(), any())).thenAnswer(inv -> {
            List<AgentManifest> ms = inv.getArgument(1);
            Map<String, Boolean> decisions = new HashMap<>();
            if (ms != null) ms.forEach(m -> decisions.put(m.agentId(), true));
            return new CerbosEntitlementAdapter.BatchResult(decisions, "cerbos");
        });
        // S1c: mirror the stub for the ctx-aware overload the PRIMARY filterAgents gate now calls.
        when(cerbosAdapter.checkAgents(any(), any(), any())).thenAnswer(inv -> {
            List<AgentManifest> ms = inv.getArgument(1);
            Map<String, Boolean> decisions = new HashMap<>();
            if (ms != null) ms.forEach(m -> decisions.put(m.agentId(), true));
            return new CerbosEntitlementAdapter.BatchResult(decisions, "cerbos");
        });
        when(cerbosAdapter.checkAgentMembership(any(), any())).thenAnswer(inv -> {
            List<AgentManifest> ms = inv.getArgument(1);
            Map<String, Boolean> decisions = new HashMap<>();
            if (ms != null) ms.forEach(m -> decisions.put(m.agentId(), true));
            return decisions;
        });
    }

    private String mintToken(String subject, List<String> roles) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("http://iam-service:8084")
                .audience(List.of("conduit-gateway", "conduit-gateway@default"))
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
                .issueTime(new Date())
                .claim("roles", roles)
                .claim("segments", Map.of("wealth", "confidential-pii"))
                .claim("tenant_id", "default")
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return jwt.serialize();
    }

    private static final String BODY = """
            { "model": "conduit-assistant", "stream": false,
              "messages": [{"role": "user", "content": "risk tolerance for Whitman"}] }
            """;

    @Test
    void requiresAuthentication() throws Exception {
        // No bearer token → the security rule denies before the controller runs.
        mvc.perform(post("/debug/route")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminPersonaTokenIsAccepted_andReturnsProductionDecision() throws Exception {
        // A NON-admin persona token (relationship_manager) — proves this path is not admin-gated.
        MvcResult res = mvc.perform(post("/debug/route")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + mintToken("rm_jane", List.of("relationship_manager")))
                        .content(BODY))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode d = mapper.readTree(res.getResponse().getContentAsString());

        // (a) production witness + masking version
        assertThat(d.get("path").asText()).isEqualTo("production");
        assertThat(d.get("preparationVersion").asText()).isEqualTo("route-prep-test-v9");
        assertThat(d.get("maskMode").asText()).isEqualTo("masked-base");

        // (b) masking applied — the endpoint returns the SAME masked text the preparer produced, and
        // fed it to the resolver (no forked masking).
        assertThat(d.get("maskedRoutingText").asText()).isEqualTo(MASKED);
        verify(resolver).resolveContextual(eq(MASKED), eq(true));

        // (c) ALL candidates incl. below-floor with their scores + selected flag.
        JsonNode cands = d.get("candidates");
        assertThat(cands).hasSize(2);
        Map<String, Boolean> selectedById = new HashMap<>();
        Map<String, Double> scoreById = new HashMap<>();
        cands.forEach(c -> {
            selectedById.put(c.get("agentId").asText(), c.get("selected").asBoolean());
            scoreById.put(c.get("agentId").asText(), c.get("score").asDouble());
        });
        assertThat(selectedById.get("meridian.wealth.risk_profile")).isTrue();
        assertThat(selectedById.get("meridian.wealth.performance")).isFalse();  // below floor, still surfaced
        assertThat(scoreById.get("meridian.wealth.performance")).isEqualTo(0.22);

        // (d) requested groups + per-group post-auth disposition (structural authz allowed → SERVED).
        assertThat(d.get("requestedGroups")).hasSize(1);
        JsonNode grp = d.get("requestedGroups").get(0);
        assertThat(grp.get("requestedCapabilityIds").get(0).asText()).isEqualTo("meridian.wealth.risk_profile");
        assertThat(grp.get("kind").asText()).isEqualTo("FLAT");

        JsonNode disp = d.get("disposition").get(0);
        assertThat(disp.get("disposition").asText()).isEqualTo("SERVED");
        assertThat(d.get("overallDisposition").asText()).isEqualTo("SERVED");
    }
}
