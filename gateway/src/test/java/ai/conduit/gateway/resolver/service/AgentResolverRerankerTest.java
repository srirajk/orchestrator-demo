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
