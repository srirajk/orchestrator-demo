package com.openwolf.iam.policystudio.breakglass;

import com.openwolf.iam.policystudio.BaseCeiling;
import com.openwolf.iam.policystudio.GeneratedPolicyValidator;
import com.openwolf.iam.policystudio.PolicyIR;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Lowers a bounds-validated {@link BreakGlassGrant} into a typed {@link PolicyIR} — a tenant-scoped
 * Cerbos restriction child (Axiom Story C6) that still passes the C2 {@link GeneratedPolicyValidator}
 * unchanged (shape, segment-wise scope containment, grounding, no-wildcard, totality).
 *
 * <p><b>The self-limiting idiom (the whole point):</b> the granted {@code (action, role)} tuple is
 * expressed as a COMPLEMENTARY PAIR of time-conditioned rules on the SAME tuple —
 * <pre>
 *   ALLOW  action  role   when  now() &gt;= timestamp("&lt;issuedAt&gt;") &amp;&amp; now() &lt; timestamp("&lt;expiresAt&gt;")
 *   DENY   action  role   when  now() &lt;  timestamp("&lt;issuedAt&gt;") || now() &gt;= timestamp("&lt;expiresAt&gt;")
 * </pre>
 * The ALLOW carries BOTH bounds (H1): the LOWER bound {@code now() >= timestamp(issuedAt)} makes a
 * future-dated grant inert in the PDP (it cannot fire until {@code issuedAt}), matching the bounds
 * gate's server-clock rejection.
 * A lone conditional ALLOW would be UNSAFE under {@code REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS}: once
 * its condition lapses the rule becomes silence, and silence inherits the parent ALLOW (the proven
 * fall-through trap — {@code infra/cerbos/templates/tenant-deny-all.yaml}). The complementary DENY
 * makes expiry fail CLOSED: after {@code expiresAt} the explicit DENY fires (and DENY beats ALLOW in
 * Cerbos), so the grant expires <em>inside the PDP</em> with no external revocation.
 *
 * <p><b>S4 — MERGE, not replace (the fix).</b> A break-glass child must be the tenant's CURRENT active
 * grants PLUS the one time-boxed emergency tuple — never a standalone policy that DENIES every other
 * tuple. The old deny-all-else baseline was correct for totality but, once promoted into the serving
 * PDP (S1), would have <em>revoked the tenant's normal access</em> for the life of the grant. The merge
 * takes the tenant's current restriction child ({@code currentChild}; {@code null} = the tenant purely
 * inherits the base ceiling) and, for every ceiling {@code (action, role)} tuple EXCEPT the granted
 * tuple, re-emits the effect the current bundle already decides — preserving each normal ALLOW and each
 * deliberate DENY. Only the granted tuple is governed by the complementary time-conditioned pair, so the
 * emergency ALLOW is additive and, on expiry, collapses to its DENY while the rest of the bundle stays
 * intact. Totality over the ceiling is still complete, so nothing falls through to the parent ALLOW.
 *
 * <p>Tenant restriction children in this system are authored as unconditional per-role ALLOW/DENY rules
 * (the base ceiling carries the CEL conditions — classification ladder, tenant-equality backstop); the
 * merge therefore reproduces each tuple's effect unconditionally, exactly as {@code tenant_default_agent}
 * and the C5 bodies are written.
 */
@Component
public class BreakGlassPolicyCompiler {

    /** Build the CEL predicate for {@code now() < timestamp(expiresAt)} (seconds precision, RFC3339). */
    static String beforeExpiry(BreakGlassGrant grant) {
        return "now() < timestamp(\"" + isoSeconds(grant.expiresAt()) + "\")";
    }

    /** Build the CEL predicate for {@code now() >= timestamp(expiresAt)} — the upper self-limit. */
    static String afterExpiry(BreakGlassGrant grant) {
        return "now() >= timestamp(\"" + isoSeconds(grant.expiresAt()) + "\")";
    }

    /** Build the CEL predicate for {@code now() >= timestamp(issuedAt)} — the LOWER bound (H1). */
    static String afterIssued(BreakGlassGrant grant) {
        return "now() >= timestamp(\"" + isoSeconds(grant.issuedAt()) + "\")";
    }

    /** Build the CEL predicate for {@code now() < timestamp(issuedAt)} — before the grant is live. */
    static String beforeIssued(BreakGlassGrant grant) {
        return "now() < timestamp(\"" + isoSeconds(grant.issuedAt()) + "\")";
    }

    /**
     * The ALLOW window: live ONLY inside {@code [issuedAt, expiresAt)}. Carries BOTH bounds so a
     * future-dated grant is inert in the PDP (the lower bound fails until {@code issuedAt}) exactly as
     * it is rejected by the bounds gate (H1).
     */
    static String activeWindow(BreakGlassGrant grant) {
        return afterIssued(grant) + " && " + beforeExpiry(grant);
    }

    /** The complementary DENY: fires whenever now() is OUTSIDE {@code [issuedAt, expiresAt)}. */
    static String outsideWindow(BreakGlassGrant grant) {
        return beforeIssued(grant) + " || " + afterExpiry(grant);
    }

    private static String isoSeconds(java.time.Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static Object matchCondition(String expr) {
        return Map.of("match", Map.of("expr", expr));
    }

    /**
     * Compile the grant against the tenant's inherited base ceiling ({@code currentChild = null}) — the
     * tenant has no restriction child yet, so every ceiling tuple is preserved (ALLOWed) and only the
     * granted tuple is time-boxed. Convenience overload used by the C6 authoring/bounds harness.
     */
    public PolicyIR compile(BreakGlassGrant grant, BaseCeiling ceiling) {
        return compile(grant, ceiling, null);
    }

    /**
     * Compile the grant into a tenant restriction child that MERGES the temporary emergency tuple with the
     * tenant's CURRENT active grants (S4). The caller runs the produced IR through the C2
     * {@link GeneratedPolicyValidator} to confirm admissibility (see {@code BreakGlassAuthoringService}).
     *
     * @param grant        the bounds-validated emergency grant
     * @param ceiling      the immutable base ceiling (the totality target)
     * @param currentChild the tenant's current active restriction child ({@code null} = inherits the base
     *                     ceiling directly); its per-tuple effects are preserved, so normal access survives
     */
    public PolicyIR compile(BreakGlassGrant grant, BaseCeiling ceiling, PolicyIR currentChild) {
        List<PolicyIR.Rule> rules = new ArrayList<>();

        // ── The break-glass grant: a complementary time-conditioned ALLOW/DENY on the granted tuple.
        //    Emitted FIRST so the granted tuple is governed solely by the time pair (and so a scan for the
        //    emergency ALLOW/DENY finds the time-conditioned rule, not a merged baseline rule). ──
        rules.add(new PolicyIR.Rule(
                List.of(grant.action()), "EFFECT_ALLOW", List.of(grant.role()), List.of(),
                matchCondition(activeWindow(grant))));
        rules.add(new PolicyIR.Rule(
                List.of(grant.action()), "EFFECT_DENY", List.of(grant.role()), List.of(),
                matchCondition(outsideWindow(grant))));

        // ── MERGE baseline: for every remaining ceiling tuple, re-emit the effect the tenant's CURRENT
        //    bundle already decides — preserving normal ALLOWs (so break-glass is additive, not a revoke)
        //    and deliberate DENYs. Grouped by effect + role for deterministic, total coverage. ──
        Map<String, TreeSet<String>> allowByRole = new TreeMap<>();
        Map<String, TreeSet<String>> denyByRole = new TreeMap<>();
        for (BaseCeiling.Tuple t : ceiling.tuples()) {
            if (t.role().equals(grant.role()) && t.action().equals(grant.action())) {
                continue; // governed by the time-conditioned pair above
            }
            Map<String, TreeSet<String>> target = currentlyAllows(currentChild, t) ? allowByRole : denyByRole;
            target.computeIfAbsent(t.role(), r -> new TreeSet<>()).add(t.action());
        }
        // Deterministic rule order: ALLOWs then DENYs, roles sorted, actions sorted.
        for (Map.Entry<String, TreeSet<String>> e : allowByRole.entrySet()) {
            rules.add(new PolicyIR.Rule(
                    List.copyOf(e.getValue()), "EFFECT_ALLOW", List.of(e.getKey()), List.of(), null));
        }
        for (Map.Entry<String, TreeSet<String>> e : denyByRole.entrySet()) {
            rules.add(new PolicyIR.Rule(
                    List.copyOf(e.getValue()), "EFFECT_DENY", List.of(e.getKey()), List.of(), null));
        }

        return new PolicyIR(
                GeneratedPolicyValidator.API_VERSION,
                "default",
                grant.resourceKind(),
                grant.scope().value(),
                GeneratedPolicyValidator.TENANT_CHILD_POSTURE,
                List.of(),
                rules);
    }

    /**
     * The effect the tenant's current bundle assigns a ceiling tuple. A {@code null} current child means
     * the tenant inherits the base ceiling directly, so every tuple is preserved (ALLOW). Otherwise the
     * child's own rules decide: DENY beats ALLOW (Cerbos semantics), and an uncovered tuple fails CLOSED
     * (DENY) — which is exactly how the base bundle already treats it under parental consent.
     */
    private static boolean currentlyAllows(PolicyIR currentChild, BaseCeiling.Tuple tuple) {
        if (currentChild == null) {
            return true;
        }
        boolean allowed = false;
        for (PolicyIR.Rule rule : currentChild.rules()) {
            boolean actionMatch = rule.actions().contains(tuple.action());
            boolean roleMatch = rule.roles().contains(tuple.role())
                    || rule.derivedRoles().contains(tuple.role());
            if (actionMatch && roleMatch) {
                if (!rule.isAllow()) {
                    return false; // an explicit DENY on the tuple wins
                }
                allowed = true;
            }
        }
        return allowed;
    }
}
