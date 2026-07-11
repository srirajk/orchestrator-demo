package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.domain.auth.CerbosEntitlementAdapter;
import ai.conduit.gateway.domain.auth.JwksClient;
import ai.conduit.gateway.domain.coverage.CoverageClient;
import ai.conduit.gateway.domain.coverage.CoverageResource;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundSourceKind;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundStatus;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedInterpretation;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedMention;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedReferenceSet;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundingResult;
import ai.conduit.gateway.domain.intent.Intent;
import ai.conduit.gateway.domain.intent.IntentClassifier;
import ai.conduit.gateway.domain.intent.IntentResult;
import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import ai.conduit.gateway.infrastructure.telemetry.TraceEventPublisher;
import ai.conduit.gateway.infrastructure.telemetry.event.CheckDeniedData;
import ai.conduit.gateway.orchestration.executor.FlatPlanExecutor;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.RoutingCandidate;
import ai.conduit.gateway.resolver.model.ResolverResult;
import ai.conduit.gateway.resolver.service.AgentResolver;
import ai.conduit.gateway.synthesis.answer.AnswerSynthesizer;
import ai.conduit.gateway.synthesis.input.EntityBag;
import ai.conduit.gateway.synthesis.input.InputSynthesizer;
import ai.conduit.gateway.synthesis.input.InputSynthesizer.SynthesisResult;
import ai.conduit.gateway.synthesis.input.Mention;
import ai.conduit.gateway.synthesis.input.MentionSource;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adversarial full-context tests for the entitlement fail-closed behaviour of the routing-spec V2
 * Piece-4 per-group disposition ({@link ChatService#coverageForGroup}). These target the fail-open
 * holes the pre-existing {@link ChatServiceRequestedGroupTest} never exercised (all its manifests are
 * {@code resource_scoped:false} custody agents, so no per-group coverage decision was made):
 *
 * <ul>
 *   <li><b>B1 (UNAVAILABLE → fail closed)</b> — a group whose coverage interpretation is UNAVAILABLE
 *       (coverage service down) is DENIED and its resource-scoped agent is NEVER invoked, while an
 *       independent non-scoped sibling group is still served.</li>
 *   <li><b>S1 (unresolved required entity → CLARIFY, not access-denied)</b> — a resource-scoped group
 *       whose required coverage entity is unresolved is CLARIFIED ("which client?"), reusing the flat
 *       clarify machinery, and its agent is NEVER bound+invoked with an unchecked id-shaped reference.</li>
 *   <li><b>B3 (below-floor id never grouped)</b> — a {@code multiple([ids])} naming a below-floor
 *       (skipped) id does not form a group; with &lt;2 above-floor survivors the request collapses to a
 *       single flat group.</li>
 * </ul>
 *
 * <p>Assertions are on invoked agent ids (via {@link FlatPlanExecutor}), the merge decision
 * (withheld domains), and the deny/clarify trace events — never on synthesized answer text. The router,
 * route-preparer, grounding, coverage service and Cerbos are mocked so the disposition is deterministic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "conduit.registry.readiness.enabled=false",
        "conduit.embedding.provider=hash"
})
class ChatServiceGroupCoverageFailClosedTest extends RedisContainerTest {

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
    @MockBean RoutePreparer routePreparer;
    @MockBean ReferenceGroundingService referenceGrounding;
    @MockBean CoverageClient coverageClient;
    @MockBean TraceEventPublisher tracePublisher;

    private final List<String> capturedWithheld = new ArrayList<>();

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
        capturedWithheld.clear();
        when(jwksClient.getPublicKey("k1")).thenReturn((RSAPublicKey) keyPair.getPublic());
        when(jwksClient.getPublicKey(argThat(k -> !"k1".equals(k)))).thenReturn(null);

        // Default: no groundable reference and no scalar entity. Individual tests override the bag /
        // the prepared grounded set to drive a specific per-group coverage decision.
        when(intentClassifier.classify(any())).thenReturn(new IntentResult(
                Intent.FETCH_DATA, 0.9, "fetch", EntityBag.empty()));
        when(routePreparer.prepare(any(), anyString(), any(), anyBoolean(), anyBoolean(),
                any(), anyString(), any())).thenAnswer(inv -> prepared(GroundedReferenceSet.none()));
        when(referenceGrounding.ground(any(), anyString(), any(), anyString(), any()))
                .thenReturn(GroundingResult.none());

        when(inputSynthesizer.synthesize(any(EntityBag.class), anyList())).thenAnswer(inv -> bindAll(inv.getArgument(1)));
        when(inputSynthesizer.synthesize(anyString(), anyList())).thenAnswer(inv -> bindAll(inv.getArgument(1)));

        when(executor.execute(any(Plan.class), any())).thenAnswer(inv -> {
            Plan plan = inv.getArgument(0);
            List<NodeResult> out = new ArrayList<>();
            for (PlanNode n : plan.nodes()) {
                out.add(new NodeResult(n.nodeId(), n.agent().agentId(), "http",
                        NodeResult.Status.OK, JsonNodeFactory.instance.objectNode().put("v", 1), 5L, null));
            }
            return out;
        });

        when(answerSynthesizer.synthesize(anyList(), anyString(), any(), any(SseEmitter.class),
                any(), anyList(), anyLong())).thenAnswer(inv -> {
            capturedWithheld.addAll(inv.getArgument(5));
            ((SseEmitter) inv.getArgument(3)).complete();
            return "ANSWERED";
        });
    }

    private static SynthesisResult bindAll(List<AgentManifest> ms) {
        Map<String, com.fasterxml.jackson.databind.JsonNode> inputs = new HashMap<>();
        ms.forEach(m -> inputs.put(m.agentId(), JsonNodeFactory.instance.objectNode()));
        return new SynthesisResult(inputs, List.of(), false, null);
    }

    private static PreparedRoute prepared(GroundedReferenceSet gs) {
        return new PreparedRoute("routing text", gs, false, PreparedRoute.ResidualClass.HAS_ACTION,
                new PreparedRoute.MaskDiagnostics("off", 0, 0, 0, "HAS_ACTION", "h", "h"));
    }

    /** A grounded set carrying one interpretation for {@code subDomainId} with {@code status}. */
    private static GroundedReferenceSet groundedSet(String subDomainId, GroundStatus status, String canonicalId) {
        Mention m = new Mention("relationship_id", "relationship_reference", "REL-99999", 0, null,
                MentionSource.EXPLICIT);
        GroundedInterpretation gi = new GroundedInterpretation(
                canonicalId, null, subDomainId, "iid:" + subDomainId, status, null,
                GroundSourceKind.EXTRACTED_REFERENCE, MentionSource.EXPLICIT, "0", 0, null, null, null);
        GroundedMention gm = new GroundedMention(m, List.of(gi), false, false);
        return new GroundedReferenceSet(List.of(gm), GroundingResult.none());
    }

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

    private List<String> invokedAgentIds() {
        ArgumentCaptor<Plan> plans = ArgumentCaptor.forClass(Plan.class);
        verify(executor, atLeast(1)).execute(plans.capture(), any());
        List<String> ids = new ArrayList<>();
        plans.getAllValues().forEach(p -> p.nodes().forEach(n -> ids.add(n.agent().agentId())));
        return ids;
    }

    private List<CheckDeniedData> checkDenials() {
        ArgumentCaptor<TraceEvent> cap = ArgumentCaptor.forClass(TraceEvent.class);
        verify(tracePublisher, atLeast(0)).publish(cap.capture());
        List<CheckDeniedData> out = new ArrayList<>();
        for (TraceEvent e : cap.getAllValues()) {
            if ("check_denied".equals(e.type()) && e.data() instanceof CheckDeniedData d) out.add(d);
        }
        return out;
    }

    // ── B1: a group whose coverage is UNAVAILABLE fails CLOSED — its agent is never invoked ─────────

    @Test
    void groupCoverageUnavailable_failsClosed_agentNeverInvoked() throws Exception {
        AgentManifest holdings = agent("meridian.wealth.holdings", "wealth-management", "private-banking");
        AgentManifest settlement = agent("meridian.servicing.settlement_status", "asset-servicing", "custody-operations");
        allowOnly("meridian.wealth.holdings", "meridian.servicing.settlement_status");  // both structurally allowed
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(twoFacets(holdings, settlement));
        // The wealth (private-banking) coverage interpretation is UNAVAILABLE — the coverage service is down.
        when(routePreparer.prepare(any(), anyString(), any(), anyBoolean(), anyBoolean(), any(), anyString(), any()))
                .thenAnswer(inv -> prepared(groundedSet("private-banking", GroundStatus.UNAVAILABLE, "REL-99999")));

        ask("holdings and settlement status");

        List<String> invoked = invokedAgentIds();
        assertThat(invoked).contains("meridian.servicing.settlement_status");   // non-scoped sibling still served
        assertThat(invoked).doesNotContain("meridian.wealth.holdings");         // UNAVAILABLE → fail closed, never invoked
        assertThat(capturedWithheld).contains("wealth-management");             // withheld honestly
        // The fail-closed decision surfaces as a coverage-unavailable deny (not a silent pass).
        assertThat(checkDenials()).anyMatch(d -> "coverage".equals(d.stage())
                && "coverage-unavailable".equals(d.reason()));
    }

    // ── S1: an entitled caller with an unresolved required entity is CLARIFIED, not access-denied ───

    @Test
    void unresolvedRequiredEntity_clarifiesNotDenied_neverBindsUncheckedId() throws Exception {
        AgentManifest holdings = agent("meridian.wealth.holdings", "wealth-management", "private-banking");
        AgentManifest insurance = agent("meridian.insurance.renewal_risk", "insurance", "claims-servicing");
        // holdings is entitled; the insurance facet is structurally denied so nothing masks the clarify.
        allowOnly("meridian.wealth.holdings");
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(twoFacets(holdings, insurance));
        // The bag carries an id-SHAPED reference that never passed CHECK (out of book / not grounded). No
        // grounded interpretation resolves it (empty grounded set) → the required coverage entity is
        // unresolved. EntityResolver would bind "REL-99999" verbatim — the fail-open hole.
        when(intentClassifier.classify(any())).thenReturn(new IntentResult(
                Intent.FETCH_DATA, 0.9, "fetch",
                EntityBag.of(Map.of("relationship_reference", "REL-99999"), Map.of())));
        // The caller's book is discoverable, so a "which client?" clarify can be formed.
        when(coverageClient.discover(anyString(), anyString(), any(), any())).thenReturn(List.of(
                new CoverageResource("REL-00042", "Whitman Family Office", "private-banking"),
                new CoverageResource("REL-00777", "Calderon Trust", "private-banking")));

        String body = ask("what's in REL-99999");

        // The resource-scoped agent is NEVER bound+invoked with the unchecked id.
        verify(executor, never()).execute(any(Plan.class), any());
        // Clarify machinery engaged (discover the book) — this is a CLARIFY, not a fabricated bind.
        verify(coverageClient, atLeastOnce()).discover(anyString(), anyString(), any(), any());
        // holdings is NOT coverage-denied: an entitled caller is asked which client, never told they lack access.
        assertThat(checkDenials()).noneMatch(d -> "coverage".equals(d.stage()));
        // Deterministic manifest clarify copy (private-banking) — never a denial or a bound answer.
        assertThat(body).contains("Which client relationship");
        assertThat(body).doesNotContain("not in your coverage");
    }

    // ── B3: a multiple([ids]) naming a below-floor id does not form a group ─────────────────────────

    @Test
    void belowFloorFacetId_notGrouped_collapsesToSingleFlatGroup() throws Exception {
        AgentManifest status = agent("meridian.servicing.settlement_status", "asset-servicing", "custody-operations");
        AgentManifest risk = agent("meridian.servicing.settlement_risk", "asset-servicing", "custody-operations");
        allowOnly("meridian.servicing.settlement_status", "meridian.servicing.settlement_risk");
        // status is above the floor (selected); risk is BELOW the floor (skipped). The reranker still
        // named both in its top-5 shortlist (taken before the floor) → multiple([status, risk]).
        when(resolver.resolveContextual(anyString(), anyBoolean())).thenReturn(new ResolverResult(
                List.of(new RoutingCandidate(status, 0.62)),
                List.of(new RoutingCandidate(risk, 0.30)),
                false, 0.62, "q", 0.04, true,
                List.of("meridian.servicing.settlement_status", "meridian.servicing.settlement_risk")));

        ask("settlement status and settlement risk");

        List<String> invoked = invokedAgentIds();
        // Below-floor risk never becomes a group → never invoked. <2 above-floor survivors → single flat group.
        assertThat(invoked).containsExactly("meridian.servicing.settlement_status");
        assertThat(invoked).doesNotContain("meridian.servicing.settlement_risk");
        assertThat(capturedWithheld).isEmpty();
    }
}
