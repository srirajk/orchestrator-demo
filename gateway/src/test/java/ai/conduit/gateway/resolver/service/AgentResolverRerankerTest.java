package ai.conduit.gateway.resolver.service;

import ai.conduit.gateway.registry.index.VectorIndex;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.RoutingCandidate;
import ai.conduit.gateway.registry.service.AgentRegistry;
import ai.conduit.gateway.resolver.model.ResolverResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentResolverRerankerTest {

    @Test
    void invokesRerankerOnNearTieAndUsesBoundedPick() throws Exception {
        RoutingRerankerClient reranker = mock(RoutingRerankerClient.class);
        var alpha = candidate("cap.alpha", "first capability", 0.600);
        var beta = candidate("cap.beta", "second capability", 0.580);
        AgentResolver resolver = resolver(List.of(alpha, beta), reranker);
        when(reranker.rerank(anyString(), any())).thenReturn(RoutingRerankerClient.Decision.pick("cap.beta", "closer"));

        ResolverResult result = resolver.resolve("choose the second thing");

        assertThat(result.fallback()).isFalse();
        assertThat(result.selected()).isNotEmpty();
        assertThat(result.selected().get(0).manifest().agentId()).isEqualTo("cap.beta");
        verify(reranker).rerank(anyString(), any());
    }

    @Test
    void skipsRerankerForClearEmbeddingWinner() throws Exception {
        RoutingRerankerClient reranker = mock(RoutingRerankerClient.class);
        var alpha = candidate("cap.alpha", "first capability", 0.800);
        var beta = candidate("cap.beta", "second capability", 0.600);
        AgentResolver resolver = resolver(List.of(alpha, beta), reranker);

        ResolverResult result = resolver.resolve("clear winner request");

        assertThat(result.fallback()).isFalse();
        assertThat(result.selected().get(0).manifest().agentId()).isEqualTo("cap.alpha");
        verify(reranker, never()).rerank(anyString(), any());
    }

    @Test
    void nonCandidatePickFallsBackToEmbeddingLeader() throws Exception {
        RoutingRerankerClient reranker = mock(RoutingRerankerClient.class);
        var alpha = candidate("cap.alpha", "first capability", 0.600);
        var beta = candidate("cap.beta", "second capability", 0.590);
        AgentResolver resolver = resolver(List.of(alpha, beta), reranker);
        when(reranker.rerank(anyString(), any())).thenReturn(RoutingRerankerClient.Decision.pick("cap.missing", "invalid"));

        ResolverResult result = resolver.resolve("bounded request");

        assertThat(result.fallback()).isFalse();
        assertThat(result.selected().get(0).manifest().agentId()).isEqualTo("cap.alpha");
    }

    @Test
    void rerankerErrorFallsBackToEmbeddingLeaderWithoutFailingRequest() throws Exception {
        RoutingRerankerClient reranker = mock(RoutingRerankerClient.class);
        var alpha = candidate("cap.alpha", "first capability", 0.600);
        var beta = candidate("cap.beta", "second capability", 0.599);
        AgentResolver resolver = resolver(List.of(alpha, beta), reranker);
        when(reranker.rerank(anyString(), any())).thenThrow(new RuntimeException("offline"));

        ResolverResult result = resolver.resolve("near tie request");

        assertThat(result.fallback()).isFalse();
        assertThat(result.selected().get(0).manifest().agentId()).isEqualTo("cap.alpha");
    }

    @Test
    void rerankerAbstainUsesExistingFallbackPath() throws Exception {
        RoutingRerankerClient reranker = mock(RoutingRerankerClient.class);
        var alpha = candidate("cap.alpha", "first capability", 0.600);
        var beta = candidate("cap.beta", "second capability", 0.590);
        AgentResolver resolver = resolver(List.of(alpha, beta), reranker);
        when(reranker.rerank(anyString(), any())).thenReturn(RoutingRerankerClient.Decision.abstain("none fit"));

        ResolverResult result = resolver.resolve("unclear request");

        assertThat(result.fallback()).isTrue();
        assertThat(result.selected()).isEmpty();
        assertThat(result.skipped()).containsExactly(alpha, beta);
    }

    /**
     * A re-ranker that declines to pick a single winner because the request needs SEVERAL capabilities
     * must not collapse the fan-out to nothing.
     *
     * <p>This is the hero prompt: "a full portfolio review — holdings, YTD performance, risk profile,
     * settlement status, corporate actions". The embedding search found ten strong candidates and a
     * confident leader; the re-ranker replied "no single candidate can provide a full portfolio
     * review"; the gateway threw all ten away and told the user to be more specific. Two different
     * outcomes — "these are indistinguishable" (clarify) and "one agent cannot serve this" (fan out) —
     * were the same boolean.
     */
    @Test
    void rerankerNeedsMultipleKeepsTheCandidatesAndFansOut() throws Exception {
        RoutingRerankerClient reranker = mock(RoutingRerankerClient.class);
        var alpha = candidate("cap.alpha", "first capability", 0.600);
        var beta = candidate("cap.beta", "second capability", 0.590);
        AgentResolver resolver = resolver(List.of(alpha, beta), reranker);
        when(reranker.rerank(anyString(), any())).thenReturn(
                RoutingRerankerClient.Decision.needsMultiple("no single candidate covers all requested elements"));

        ResolverResult result = resolver.resolve("holdings and performance and settlement status");

        assertThat(result.fallback())
                .as("a multi-capability request is not ambiguous — it must not fall back to clarify")
                .isFalse();
        assertThat(result.selected())
                .as("the embedding candidates survive so the request can fan out")
                .isNotEmpty();
        assertThat(result.rerankFired()).isTrue();
    }

    /**
     * A rerank pick whose raw embedding score sits just below the routing floor now ABSTAINS at
     * routing — the pick-score-tolerance band-aid has been retired. The disposition it used to catch
     * (the "Okafor's holdings" 0.398 vs "Okafor holdings" 0.418 phrasing-sensitivity that flipped a
     * would-be coverage denial into "no service") is now handled deterministically BEFORE routing by
     * {@code ReferenceGroundingService}: a grounded out-of-book reference denies at ANY embedding
     * score, so the routing score never sits between the user and a denial. On the debug {@code
     * resolve()} path there is no grounded reference ({@code entityKnown=false}), so a below-floor
     * leader correctly abstains here; the legitimate near-floor in-book case routes on the chat path
     * via {@code entityKnown} instead.
     */
    @Test
    void aRerankPickJustBelowTheFloorAbstainsNowThatGroundingOwnsThatDisposition() throws Exception {
        RoutingRerankerClient reranker = mock(RoutingRerankerClient.class);
        var alpha = candidate("cap.alpha", "holdings capability", 0.398);   // just under the 0.40 floor
        var beta = candidate("cap.beta", "second capability", 0.376);        // near-tie → re-ranker fires
        AgentResolver resolver = resolver(List.of(alpha, beta), reranker);
        when(reranker.rerank(anyString(), any()))
                .thenReturn(RoutingRerankerClient.Decision.pick("cap.alpha", "clear match"));

        ResolverResult result = resolver.resolve("give me the holdings");

        assertThat(result.fallback())
                .as("with the band-aid retired, a below-floor pick abstains; grounding, not routing, owns the deny")
                .isTrue();
    }

    /**
     * A grounded turn ({@code entityKnown=true}, the chat path) whose leader sits just below the
     * routing floor still routes — the subject was RESOLVED + coverage-CHECKED pre-routing, so only
     * the facet is muddy. This is the mechanism that REPLACES the retired pick-score-tolerance for the
     * legitimate near-floor in-book case.
     */
    @Test
    void aGroundedTurnJustBelowTheFloorStillRoutes() throws Exception {
        RoutingRerankerClient reranker = mock(RoutingRerankerClient.class);
        var alpha = candidate("cap.alpha", "holdings capability", 0.398);   // just under the 0.40 floor
        var beta = candidate("cap.beta", "second capability", 0.376);
        AgentResolver resolver = resolver(List.of(alpha, beta), reranker);
        when(reranker.rerank(anyString(), any()))
                .thenReturn(RoutingRerankerClient.Decision.pick("cap.alpha", "clear match"));

        ResolverResult result = resolver.resolveContextual("give me the holdings", true);

        assertThat(result.fallback())
                .as("a grounded (entityKnown) near-floor query must route, not abstain")
                .isFalse();
        assertThat(result.selected().get(0).manifest().agentId()).isEqualTo("cap.alpha");
    }

    /** A leader far below the floor still abstains on the debug path — no grounding relaxation there. */
    @Test
    void aLeaderFarBelowTheFloorStillAbstainsDespiteAPick() throws Exception {
        RoutingRerankerClient reranker = mock(RoutingRerankerClient.class);
        var alpha = candidate("cap.alpha", "weak capability", 0.300);        // well under the 0.40 floor
        var beta = candidate("cap.beta", "second capability", 0.280);
        AgentResolver resolver = resolver(List.of(alpha, beta), reranker);
        when(reranker.rerank(anyString(), any()))
                .thenReturn(RoutingRerankerClient.Decision.pick("cap.alpha", "best of a weak set"));

        ResolverResult result = resolver.resolve("something only weakly related");

        assertThat(result.fallback())
                .as("a genuinely weak leader must still abstain")
                .isTrue();
    }

    @SuppressWarnings("unchecked")
    private static AgentResolver resolver(List<RoutingCandidate> candidates, RoutingRerankerClient reranker) {
        VectorIndex vectorIndex = mock(VectorIndex.class);
        when(vectorIndex.search(anyString(), nullable(String.class), anyInt(), any(Function.class)))
                .thenReturn(candidates);

        AgentResolver resolver = new AgentResolver(
                vectorIndex, mock(AgentRegistry.class), new SimpleMeterRegistry(), reranker);
        ReflectionTestUtils.setField(resolver, "confidenceFloor", 0.30);
        ReflectionTestUtils.setField(resolver, "relativeFloorFactor", 0.65);
        ReflectionTestUtils.setField(resolver, "topK", 10);
        ReflectionTestUtils.setField(resolver, "routingMinScore", 0.40);
        ReflectionTestUtils.setField(resolver, "routingMinMargin", 0.005);
        ReflectionTestUtils.setField(resolver, "rerankEnabled", true);
        ReflectionTestUtils.setField(resolver, "rerankMarginThreshold", 0.08);
        ReflectionTestUtils.setField(resolver, "rerankAbstainAdjacentBand", 0.08);
        ReflectionTestUtils.setField(resolver, "rerankMaxCandidates", 5);
        return resolver;
    }

    private static RoutingCandidate candidate(String id, String description, double score) {
        AgentManifest.Skill skill = new AgentManifest.Skill(
                "skill", "skill", description, List.of(), List.of(description),
                List.of("text"), List.of("json"));
        AgentManifest manifest = new AgentManifest(
                id, id, description, "1.0.0", null, "test-domain", null, null, null, "http",
                null, new AgentManifest.Capabilities(false, false), List.of(skill),
                new AgentManifest.Constraints("read", "internal", 1_000),
                null, null, null, null, true, null);
        return new RoutingCandidate(manifest, score);
    }
}
