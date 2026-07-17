package com.openwolf.iam.policystudio.breakglass;

import com.openwolf.iam.policystudio.BaseCeiling;
import com.openwolf.iam.policystudio.GeneratedPolicyValidator;
import com.openwolf.iam.policystudio.PolicyIR;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Lowers a bounds-validated {@link BreakGlassGrant} into a typed {@link PolicyIR} — a tenant-scoped
 * Cerbos restriction child (Axiom Story C6) that still passes the C2 {@link GeneratedPolicyValidator}
 * unchanged (shape, segment-wise scope containment, grounding, no-wildcard, totality).
 *
 * <p><b>The self-limiting idiom (the whole point):</b> the granted {@code (action, role)} tuple is
 * expressed as a COMPLEMENTARY PAIR of time-conditioned rules on the SAME tuple —
 * <pre>
 *   ALLOW  action  role   when  now() &lt; timestamp("&lt;expiresAt&gt;")
 *   DENY   action  role   when  now() &gt;= timestamp("&lt;expiresAt&gt;")
 * </pre>
 * A lone conditional ALLOW would be UNSAFE under {@code REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS}: once
 * its condition lapses the rule becomes silence, and silence inherits the parent ALLOW (the proven
 * fall-through trap — {@code infra/cerbos/templates/tenant-deny-all.yaml}). The complementary DENY
 * makes expiry fail CLOSED: after {@code expiresAt} the explicit DENY fires (and DENY beats ALLOW in
 * Cerbos), so the grant expires <em>inside the PDP</em> with no external revocation.
 *
 * <p>Every OTHER base-ceiling tuple is denied outright (a deny-all baseline, per the B1 idiom), so the
 * child totally covers the ceiling — no fall-through hole. The result is a break-glass tenant whose
 * only capability is the one time-boxed emergency action.
 */
@Component
public class BreakGlassPolicyCompiler {

    /** Build the CEL predicate for {@code now() < timestamp(expiresAt)} (seconds precision, RFC3339). */
    static String beforeExpiry(BreakGlassGrant grant) {
        return "now() < timestamp(\"" + isoSeconds(grant) + "\")";
    }

    /** Build the CEL predicate for {@code now() >= timestamp(expiresAt)} — the self-limit. */
    static String afterExpiry(BreakGlassGrant grant) {
        return "now() >= timestamp(\"" + isoSeconds(grant) + "\")";
    }

    private static String isoSeconds(BreakGlassGrant grant) {
        return grant.expiresAt().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static Object matchCondition(String expr) {
        return Map.of("match", Map.of("expr", expr));
    }

    /**
     * Compile the grant into the typed IR. The caller runs the produced IR through the C2
     * {@link GeneratedPolicyValidator} to confirm admissibility (see {@code BreakGlassAuthoringService}).
     */
    public PolicyIR compile(BreakGlassGrant grant, BaseCeiling ceiling) {
        List<PolicyIR.Rule> rules = new ArrayList<>();

        // ── The break-glass grant: a complementary time-conditioned ALLOW/DENY on the granted tuple ──
        rules.add(new PolicyIR.Rule(
                List.of(grant.action()), "EFFECT_ALLOW", List.of(grant.role()), List.of(),
                matchCondition(beforeExpiry(grant))));
        rules.add(new PolicyIR.Rule(
                List.of(grant.action()), "EFFECT_DENY", List.of(grant.role()), List.of(),
                matchCondition(afterExpiry(grant))));

        // ── Deny-all baseline: explicit DENY for every remaining ceiling (action, role) tuple ──
        // Group by role; exclude the granted (action, role) so the conditional pair alone governs it.
        Map<String, TreeSet<String>> withheldByRole = new LinkedHashMap<>();
        for (BaseCeiling.Tuple t : ceiling.tuples()) {
            if (t.role().equals(grant.role()) && t.action().equals(grant.action())) {
                continue; // governed by the time-conditioned pair above
            }
            withheldByRole.computeIfAbsent(t.role(), r -> new TreeSet<>()).add(t.action());
        }
        // Deterministic rule order: roles sorted, actions sorted.
        for (Map.Entry<String, TreeSet<String>> e : new TreeSet<>(withheldByRole.keySet()).stream()
                .map(r -> Map.entry(r, withheldByRole.get(r))).toList()) {
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
}
