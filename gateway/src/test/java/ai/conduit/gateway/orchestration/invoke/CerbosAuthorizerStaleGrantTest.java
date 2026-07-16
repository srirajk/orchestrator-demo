package ai.conduit.gateway.orchestration.invoke;

import ai.conduit.gateway.orchestration.invoke.InvocationAuthorizer.AuthorizationDecision;
import ai.conduit.gateway.orchestration.model.PlanNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 §b harness test 4 — a stale (or wrong-resource) grant is RE-VERIFIED on-path, never trusted; and
 * a fresh matching grant is trusted with ZERO PDP round-trips (the green path costs nothing extra).
 */
class CerbosAuthorizerStaleGrantTest {

    private static final long TTL = 120_000L;

    @Test
    void staleGrantIsReVerifiedNotTrusted() {
        CerbosInvocationAuthorizer authz = new CerbosInvocationAuthorizer(TTL);
        AtomicInteger pdpCalls = new AtomicInteger();
        PlanNode node = InvokerTestSupport.node("a1");

        AuthorizationGrant stale = new AuthorizationGrant("p1", "a1", null, "structural", "cerbos",
                "req", System.currentTimeMillis() - 10 * TTL);   // decided long ago → not fresh
        InvocationContext ctx = InvocationContext.of("p1", "conv", "req", null, List.of(stale))
                .withReverifier(n -> { pdpCalls.incrementAndGet(); return true; });

        AuthorizationDecision d = authz.authorize(ctx, node);

        assertThat(pdpCalls.get()).as("a stale grant must trigger an on-path PDP re-check").isEqualTo(1);
        assertThat(d.allowed()).isTrue();
        assertThat(d.source()).isEqualTo("cerbos");
    }

    @Test
    void reVerifiedDenyIsHonoured() {
        CerbosInvocationAuthorizer authz = new CerbosInvocationAuthorizer(TTL);
        PlanNode node = InvokerTestSupport.node("a1");
        AuthorizationGrant stale = new AuthorizationGrant("p1", "a1", null, "structural", "cerbos",
                "req", System.currentTimeMillis() - 10 * TTL);
        InvocationContext ctx = InvocationContext.of("p1", "conv", "req", null, List.of(stale))
                .withReverifier(n -> false);   // PDP now denies

        AuthorizationDecision d = authz.authorize(ctx, node);

        assertThat(d.allowed()).isFalse();
        assertThat(d.source()).isEqualTo("cerbos");
    }

    @Test
    void freshGrantIsTrustedWithoutAnyPdpCall() {
        CerbosInvocationAuthorizer authz = new CerbosInvocationAuthorizer(TTL);
        AtomicInteger pdpCalls = new AtomicInteger();
        PlanNode node = InvokerTestSupport.node("a1");

        InvocationContext ctx = InvocationContext.of("p1", "conv", "req", null,
                        List.of(AuthorizationGrant.structural("p1", "a1", "cerbos", "req")))
                .withReverifier(n -> { pdpCalls.incrementAndGet(); return true; });

        AuthorizationDecision d = authz.authorize(ctx, node);

        assertThat(d.allowed()).isTrue();
        assertThat(d.source()).isEqualTo("grant");
        assertThat(pdpCalls.get()).as("the green path must not re-hit the PDP").isZero();
    }

    @Test
    void missingReVerifierFailsClosed() {
        CerbosInvocationAuthorizer authz = new CerbosInvocationAuthorizer(TTL);
        PlanNode node = InvokerTestSupport.node("a1");
        InvocationContext ctx = InvocationContext.of("p1", "conv", "req", null, List.of());  // no grant, no re-check

        AuthorizationDecision d = authz.authorize(ctx, node);

        assertThat(d.allowed()).isFalse();
    }
}
