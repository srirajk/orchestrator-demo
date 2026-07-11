package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.domain.auth.CerbosEntitlementAdapter;
import ai.conduit.gateway.domain.auth.JwksClient;
import ai.conduit.gateway.domain.intent.Intent;
import ai.conduit.gateway.domain.intent.IntentClassifier;
import ai.conduit.gateway.domain.intent.IntentResult;
import ai.conduit.gateway.orchestration.executor.FlatPlanExecutor;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.orchestration.planner.DagResolution;
import ai.conduit.gateway.orchestration.planner.DagResolver;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.RoutingCandidate;
import ai.conduit.gateway.resolver.model.ResolverResult;
import ai.conduit.gateway.resolver.service.AgentResolver;
import ai.conduit.gateway.synthesis.answer.AnswerSynthesizer;
import ai.conduit.gateway.synthesis.input.EntityBag;
import ai.conduit.gateway.synthesis.input.InputSynthesizer;
import ai.conduit.gateway.synthesis.input.InputSynthesizer.SynthesisResult;
import ai.conduit.gateway.testsupport.RedisContainerTest;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end (full-context) tests for the routing-spec V2 Piece-4 requested-capability-group model and
 * per-group disposition through the real {@link ChatService}. The router is mocked to return a valid
 * multi-facet {@code rerankSelectedIds} (the Piece-5 signal), which is the ONLY trigger for the
 * multi-group path; input synthesis / flat execution / answer synthesis are mocked so the DISPOSITION
 * (which capability groups were served vs withheld, and which agents were actually invoked) is
 * observable deterministically without any live agent or LLM.
 *
 * <p>Assertions are on <b>invoked agent ids + fact absence</b> (never answer text): the served group's
 * agents reach {@link FlatPlanExecutor}; a structurally-denied group's agents NEVER do; and the denied
 * group is withheld honestly (bug-260 scoping preserved). The single-group case falls through to the
 * pre-Piece-4 flat path and is asserted to be behaviourally unchanged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "conduit.registry.readiness.enabled=false",
        "conduit.embedding.provider=hash",
        "conduit.orchestration.dag-enabled=true"
})
class ChatServiceRequestedGroupTest extends RedisContainerTest {

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
    @MockBean InputSynthesizer inputSynthesizer;
    @MockBean FlatPlanExecutor executor;
    @MockBean AnswerSynthesizer answerSynthesizer;
    @MockBean DagResolver dagResolver;

    // Captures the withheldDomains handed to the answer synthesizer (the merge decision) per request.
    private final List<String> capturedWithheld = new ArrayList<>();

    private static AgentManifest agent(String id, String domain, String subDomain, AgentManifest.Io io) {
        AgentManifest.Skill skill = new AgentManifest.Skill(
                "skill", "skill", "d", List.of(), List.of("d"), List.of("text"), List.of("json"));
        return new AgentManifest(
                id, id, "d", "1.0.0", null, domain, null, subDomain, null, "http",
                null, new AgentManifest.Capabilities(false, false), List.of(skill),
                new AgentManifest.Constraints("read", "internal", 1_000),
                io, null, null, null, true, null);
    }

    private static AgentManifest agent(String id, String domain, String subDomain) {
        return agent(id, domain, subDomain, null);
    }

    @BeforeEach
    void wire() {
        capturedWithheld.clear();
        when(jwksClient.getPublicKey("k1")).thenReturn((RSAPublicKey) keyPair.getPublic());
        when(jwksClient.getPublicKey(argThat(k -> !"k1".equals(k)))).thenReturn(null);

        // No groundable reference in any of these turns → the resolver is what drives grouping.
        when(intentClassifier.classify(any())).thenReturn(new IntentResult(
                Intent.FETCH_DATA, 0.9, "fetch", EntityBag.empty()));

        // Input synthesis: bind every manifest handed in (structural authz already decided membership).
        when(inputSynthesizer.synthesize(any(EntityBag.class), anyList())).thenAnswer(inv -> {
            List<AgentManifest> ms = inv.getArgument(1);
            Map<String, com.fasterxml.jackson.databind.JsonNode> inputs = new HashMap<>();
            ms.forEach(m -> inputs.put(m.agentId(), JsonNodeFactory.instance.objectNode()));
            return new SynthesisResult(inputs, List.of(), false, null);
        });
        when(inputSynthesizer.synthesize(anyString(), anyList())).thenAnswer(inv -> {
            List<AgentManifest> ms = inv.getArgument(1);
            Map<String, com.fasterxml.jackson.databind.JsonNode> inputs = new HashMap<>();
            ms.forEach(m -> inputs.put(m.agentId(), JsonNodeFactory.instance.objectNode()));
            return new SynthesisResult(inputs, List.of(), false, null);
        });

        // Flat execution: return one OK NodeResult per plan node (data is opaque; we assert on WHICH nodes).
        when(executor.execute(any(Plan.class), any())).thenAnswer(inv -> {
            Plan plan = inv.getArgument(0);
            List<NodeResult> out = new ArrayList<>();
            for (PlanNode n : plan.nodes()) {
                out.add(new NodeResult(n.nodeId(), n.agent().agentId(), "http",
                        NodeResult.Status.OK, JsonNodeFactory.instance.objectNode().put("v", 1), 5L, null));
            }
            return out;
        });

        // Answer synthesis: capture the withheldDomains (the merge decision) and complete the SSE so the
        // buffering (stream:false) emitter's latch releases. We assert on the captured value, not text.
        when(answerSynthesizer.synthesize(anyList(), anyString(), any(), any(SseEmitter.class),
                any(), anyList(), anyLong())).thenAnswer(inv -> {
            capturedWithheld.addAll(inv.getArgument(5));
            ((SseEmitter) inv.getArgument(3)).complete();
            return "ANSWERED";
        });
    }

    /** Cerbos allows every agent whose id is in {@code allowedIds}; denies the rest. */
    private void allowOnly(String... allowedIds) {
        List<String> allow = List.of(allowedIds);
        when(cerbosAdapter.checkAgents(any(), any())).thenAnswer(inv -> {
            List<AgentManifest> ms = inv.getArgument(1);
            Map<String, Boolean> decisions = new HashMap<>();
            if (ms != null) ms.forEach(m -> decisions.put(m.agentId(), allow.contains(m.agentId())));
            return new CerbosEntitlementAdapter.BatchResult(decisions, "cerbos");
        });
    }

    private ResolverResult twoFacets(AgentManifest a, AgentManifest b) {
        return new ResolverResult(
                List.of(new RoutingCandidate(a, 0.62), new RoutingCandidate(b, 0.58)),
                List.of(), false, 0.62, "q", 0.04, true,
                List.of(a.agentId(), b.agentId()));
    }

    private String mintToken() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("rm_jane")
                .issuer("http://iam-service:8084")
                .audience(List.of("conduit-gateway"))
                .expirationTime(new Date(System.currentTimeMillis() + 3_600_000L))
                .issueTime(new Date())
                .claim("roles", List.of("relationship_manager"))
                .claim("segments", Map.of("wealth", "confidential-pii"))
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

    /** Every agent id that reached the flat executor across all plans this request executed. */
    private List<String> invokedAgentIds() {
        ArgumentCaptor<Plan> plans = ArgumentCaptor.forClass(Plan.class);
        verify(executor, atLeast(1)).execute(plans.capture(), any());
        List<String> ids = new ArrayList<>();
        plans.getAllValues().forEach(p -> p.nodes().forEach(n -> ids.add(n.agent().agentId())));
        return ids;
    }

    // ── (a) single-group == the pre-Piece-4 flat path ───────────────────────────────────────────

    @Test
    void singleSelection_isOneGroup_flatPathUnchanged() throws Exception {
        // A non-resource-scoped capability (custody-operations is resource_scoped:false in the test
        // manifests), so the legacy flat path reaches synthesis+execution without a live coverage hop.
        AgentManifest settlement = agent("meridian.servicing.settlement_status", "asset-servicing", "custody-operations");
        allowOnly("meridian.servicing.settlement_status");
        // No rerankSelectedIds → a single group of the one selection → the legacy flat disposition.
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(new ResolverResult(
                List.of(new RoutingCandidate(settlement, 0.7)), List.of(), false, 0.7, "q", 0.3, false));

        ask("settlement status please");

        assertThat(invokedAgentIds()).containsExactly("meridian.servicing.settlement_status");
        assertThat(capturedWithheld).isEmpty();
    }

    // ── (b) genuine mixed insurance+servicing: entitled served, denied withheld ─────────────────

    @Test
    void mixedGroups_serveEntitled_withholdDenied() throws Exception {
        AgentManifest servicing = agent("meridian.servicing.settlement_status", "asset-servicing", "custody-operations");
        AgentManifest insurance = agent("meridian.insurance.renewal_risk", "insurance", "claims-servicing");
        allowOnly("meridian.servicing.settlement_status");   // insurance group is structurally denied
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(twoFacets(servicing, insurance));

        ask("settlements and the insurance renewal risk");

        List<String> invoked = invokedAgentIds();
        assertThat(invoked).contains("meridian.servicing.settlement_status");     // entitled group served
        assertThat(invoked).doesNotContain("meridian.insurance.renewal_risk");    // denied group NEVER invoked
        assertThat(capturedWithheld).containsExactly("insurance");                // withheld honestly
    }

    // ── (c) same-domain sibling prune: deny the capability, do NOT substitute the sibling ───────

    @Test
    void sameDomainSiblingPrune_deniesCapability_servesSibling_noSubstitution() throws Exception {
        AgentManifest status = agent("meridian.servicing.settlement_status", "asset-servicing", "custody-operations");
        AgentManifest risk = agent("meridian.servicing.settlement_risk", "asset-servicing", "custody-operations");
        allowOnly("meridian.servicing.settlement_status");   // the higher-classification sibling is denied
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(twoFacets(status, risk));

        ask("settlement status and settlement risk");

        List<String> invoked = invokedAgentIds();
        assertThat(invoked).containsExactly("meridian.servicing.settlement_status");  // sibling served, once
        assertThat(invoked).doesNotContain("meridian.servicing.settlement_risk");     // denied capability, no substitute
        // bug-260 scoping: asset-servicing is still served by the sibling → never labelled withheld.
        assertThat(capturedWithheld).doesNotContain("asset-servicing");
    }

    // ── (d) DAG producer denied: deny the DAG group, never a silent flat-fallback ────────────────

    @Test
    void dagProducerDenied_deniesGroup_neverFlatFallback() throws Exception {
        AgentManifest served = agent("meridian.wealth.holdings", "wealth-management", "private-banking");
        AgentManifest goal = agent("meridian.wealth.goal_planning", "wealth-planning", "private-banking",
                new AgentManifest.Io(
                        List.of(new AgentManifest.Consume("balance", "producer_output", null, null)),
                        List.of()));
        AgentManifest producer = agent("meridian.wealth.producer", "wealth-planning", "private-banking");
        // The goal itself is authorized, its required producer is NOT.
        allowOnly("meridian.wealth.holdings", "meridian.wealth.goal_planning");
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(twoFacets(served, goal));
        // The goal resolves to a real producer→consumer DAG; the producer is the pruned dependency.
        Plan dagPlan = new Plan(List.of(
                new PlanNode(producer.agentId(), producer, JsonNodeFactory.instance.objectNode(), List.of()),
                new PlanNode(goal.agentId(), goal, JsonNodeFactory.instance.objectNode(),
                        List.of(producer.agentId()))));
        when(dagResolver.resolve(anyString(), any(), any()))
                .thenReturn(new DagResolution(true, dagPlan, List.of()));

        ask("holdings and my funding plan");

        List<String> invoked = invokedAgentIds();
        assertThat(invoked).contains("meridian.wealth.holdings");            // the flat entitled group is served
        assertThat(invoked).doesNotContain("meridian.wealth.goal_planning"); // DAG goal NOT flat-fallen-back
        assertThat(invoked).doesNotContain("meridian.wealth.producer");      // and its producer never invoked
        assertThat(capturedWithheld).contains("wealth-planning");            // the denied DAG group is withheld
    }

    // ── (e) all groups denied → structural capability-unavailable copy ───────────────────────────

    @Test
    void allGroupsDenied_structuralCapabilityUnavailableCopy() throws Exception {
        AgentManifest servicing = agent("meridian.servicing.settlement_status", "asset-servicing", "custody-operations");
        AgentManifest insurance = agent("meridian.insurance.renewal_risk", "insurance", "claims-servicing");
        allowOnly();   // neither facet is authorized
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(twoFacets(servicing, insurance));

        String body = ask("settlements and the insurance renewal risk");

        // The manifest-declared capability_unavailable copy (custody-operations, the first pre-authz
        // candidate's sub-domain) — not HashMap-order roulette, not the flat first-survivor path.
        assertThat(body).contains("That capability isn't available to you right now.");
        verify(executor, never()).execute(any(Plan.class), any());   // nothing was ever invoked
    }
}
