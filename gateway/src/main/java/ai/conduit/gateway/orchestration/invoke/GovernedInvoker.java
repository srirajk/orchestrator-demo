package ai.conduit.gateway.orchestration.invoke;

import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import ai.conduit.gateway.infrastructure.telemetry.TraceEventPublisher;
import ai.conduit.gateway.infrastructure.telemetry.event.AgentInvocationData;
import ai.conduit.gateway.infrastructure.telemetry.event.CheckDeniedData;
import ai.conduit.gateway.orchestration.harness.AgentHarness;
import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.orchestration.model.PlanNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;

/**
 * The single, fail-closed governance checkpoint every agent invocation flows through — flat, DAG,
 * map.over, and (later) {@code /v1/steps}. It funnels the {@link AgentHarness} so no path can dispatch
 * an agent without passing the checkpoint, an invariant locked by ArchUnit
 * ({@code ArchitectureFunnelTest}).
 *
 * <p>Phases, in order: <b>identity</b> (re-check the caller token as defense-in-depth — the harness
 * checks it too and stays the enforcing copy) → <b>authorize</b> (an {@link InvocationAuthorizer},
 * fail-closed: no fresh matching grant ⇒ DENIED, and an authorizer that throws ⇒ DENIED) →
 * <b>mint</b>/<b>validate</b> (identity seams for future stories) → <b>invoke</b> (delegate to the
 * harness) → <b>audit</b> (exactly one {@code agent_invocation} verdict event per call, including
 * DENIED / TIMEOUT). A denied hop NEVER reaches the harness/adapter, yet still flows to results as a
 * {@link NodeResult.Status#DENIED} result so synthesis reports it as missing data.
 */
@Service
public class GovernedInvoker {

    private static final Logger log = LoggerFactory.getLogger(GovernedInvoker.class);

    /** Deny stage label for the invocation checkpoint (distinct from structural/coverage/entitlement). */
    static final String STAGE_INVOCATION = "invocation";

    private final InvocationAuthorizer authorizer;
    private final AgentHarness harness;
    private final TraceEventPublisher tracePublisher;   // nullable (unit tests / no-telemetry contexts)

    public GovernedInvoker(InvocationAuthorizer authorizer, AgentHarness harness,
                           TraceEventPublisher tracePublisher) {
        this.authorizer = authorizer;
        this.harness = harness;
        this.tracePublisher = tracePublisher;
    }

    /**
     * Run one governed hop. Never throws — every outcome (denied, expired, or a harness terminal
     * status) is returned as a {@link NodeResult} and audited with one {@code agent_invocation} event.
     */
    public NodeResult invoke(InvocationContext ctx, PlanNode node, ExecutorService exec) {
        String boundResource = ctx == null ? null : ctx.boundResourceId(node.nodeId());
        boolean resourceScoped = boundResource != null;

        // ── identity (defense-in-depth) ────────────────────────────────────────────
        String callerToken = ctx == null ? null : ctx.callerToken();
        String identityProblem = AgentHarness.identityProblem(callerToken);
        if (identityProblem != null) {
            NodeResult r = terminal(node, NodeResult.Status.AUTH_EXPIRED,
                    "Caller identity " + identityProblem);
            audit(ctx, node, AgentInvocationData.VERDICT_DENIED, r.status(), "identity",
                    "identity-" + identityProblem, resourceScoped);
            return r;
        }

        // ── tenant integrity (A2, defense-in-depth) ────────────────────────────────
        // The authoritative missing/unknown-tenant fail-closed happens at the request filter, before
        // any I/O. Here we refuse a hop whose ATTACHED tenant envelope is present but not fully resolved
        // (no tenant id or no captured policy version) — a tenant claim that reached the invoker in a
        // half-populated state must never authorize or invoke. A context that carries no tenant at all
        // (legacy executor fallback / unit-test envelope) defers to the filter's guarantee.
        var tenant = ctx == null ? null : ctx.tenant();
        if (tenant != null && !tenant.isResolved()) {
            publishCheckDenied(ctx, boundResource, "tenant-unresolved", "tenant");
            NodeResult r = terminal(node, NodeResult.Status.DENIED, "invocation denied: tenant-unresolved");
            audit(ctx, node, AgentInvocationData.VERDICT_DENIED, r.status(), "tenant",
                    "tenant-unresolved", resourceScoped);
            return r;
        }

        // ── authorize (fail-closed; a throwing authorizer denies) ──────────────────
        InvocationAuthorizer.AuthorizationDecision decision;
        try {
            decision = authorizer.authorize(ctx, node);
        } catch (RuntimeException e) {
            log.warn("Invocation authorizer threw for node {} (agent={}) — failing closed: {}",
                    node.nodeId(), node.agent().agentId(), e.getMessage());
            decision = InvocationAuthorizer.AuthorizationDecision.deny("authorizer-error", "grant");
        }

        if (decision == null || !decision.allowed()) {
            String reason = decision == null ? "no-decision" : decision.reason();
            String source = decision == null ? "grant" : decision.source();
            publishCheckDenied(ctx, boundResource, reason, source);
            NodeResult r = terminal(node, NodeResult.Status.DENIED,
                    "invocation denied: " + reason);
            audit(ctx, node, AgentInvocationData.VERDICT_DENIED, r.status(), source, reason, resourceScoped);
            return r;
        }

        // ── mint / validate — identity seams reserved for later stories ────────────
        // ── invoke (the harness is the single agent-call chokepoint) ───────────────
        NodeResult result = harness.execute(node, exec, callerToken);

        // ── audit: one verdict event per governed hop ──────────────────────────────
        audit(ctx, node, AgentInvocationData.VERDICT_ALLOWED, result.status(),
                decision.source(), null, resourceScoped);
        return result;
    }

    private NodeResult terminal(PlanNode node, NodeResult.Status status, String message) {
        return new NodeResult(node.nodeId(), node.agent().agentId(), node.agent().protocol(),
                status, null, 0, message);
    }

    private void publishCheckDenied(InvocationContext ctx, String boundResource, String reason, String source) {
        if (tracePublisher == null || ctx == null || ctx.requestId() == null) return;
        tracePublisher.publish(TraceEvent.of("check_denied", ctx.requestId(), ctx.conversationId(),
                new CheckDeniedData(STAGE_INVOCATION, boundResource, ctx.principalId(), reason, source)));
    }

    private void audit(InvocationContext ctx, PlanNode node, String verdict,
                       NodeResult.Status status, String source, String reason, boolean resourceScoped) {
        if (tracePublisher == null || ctx == null || ctx.requestId() == null) return;
        tracePublisher.publish(TraceEvent.of("agent_invocation", ctx.requestId(), ctx.conversationId(),
                new AgentInvocationData(node.nodeId(), node.agent().agentId(), verdict,
                        status.name(), source, reason, resourceScoped)));
    }
}
