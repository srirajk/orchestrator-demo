package com.openwolf.iam.policystudio.breakglass;

import com.openwolf.iam.policystudio.ManifestVocabulary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The deterministic bounds gate for a break-glass grant (Axiom Story C6.5) — the FIRST half of the
 * safety net, run BEFORE {@link BreakGlassPolicyCompiler} ever emits YAML. It reasons over facts,
 * never intent, and collects every violation (no short-circuit). A single violation ⇒ REJECT, so a
 * malformed / over-scoped / too-long grant can never reach compilation or promotion.
 *
 * <p>The bounds (all non-negotiable):
 * <ol>
 *   <li><b>Positive TTL</b> — {@code expiresAt > issuedAt} (a grant that expires before it is issued,
 *       or has a malformed/zero window, is rejected).</li>
 *   <li><b>Maximum TTL</b> — the window may not exceed {@code iam.break-glass.max-ttl-minutes}
 *       (default 60m). Emergency access is minutes, not days.</li>
 *   <li><b>No wildcard</b> — neither the action nor the role may be {@code "*"}.</li>
 *   <li><b>Approved allowlists</b> — the resource kind and action must be in the pre-approved
 *       break-glass allowlist ({@link BreakGlassAllowlist}); the role must be in the manifest
 *       vocabulary.</li>
 *   <li><b>Tenant-scoped</b> — the scope is a concrete tenant, never the root ceiling scope (the
 *       segment-wise containment / cross-tenant check is enforced on the compiled IR by the C2
 *       {@code GeneratedPolicyValidator}).</li>
 *   <li><b>Justification present</b> — emergency access is always accountable.</li>
 * </ol>
 */
@Component
public class BreakGlassValidator {

    private static final String WILDCARD = "*";

    private final Duration maxTtl;

    public BreakGlassValidator(
            @Value("${iam.break-glass.max-ttl-minutes:60}") long maxTtlMinutes) {
        if (maxTtlMinutes <= 0) {
            throw new IllegalArgumentException("iam.break-glass.max-ttl-minutes must be positive");
        }
        this.maxTtl = Duration.ofMinutes(maxTtlMinutes);
    }

    /** The configured maximum grant window (default 60 minutes). */
    public Duration maxTtl() {
        return maxTtl;
    }

    public record Result(boolean accepted, List<String> violations) {
        public Result {
            violations = List.copyOf(violations);
        }
        public static Result accept() {
            return new Result(true, List.of());
        }
        public static Result reject(List<String> violations) {
            return new Result(false, violations);
        }
    }

    public Result validate(BreakGlassGrant grant, BreakGlassAllowlist allowlist, ManifestVocabulary vocab) {
        List<String> v = new ArrayList<>();

        // 1. Positive, well-formed TTL ─────────────────────────────────────────────────────────
        if (!grant.expiresAt().isAfter(grant.issuedAt())) {
            v.add("expiresAt (" + grant.expiresAt() + ") must be strictly after issuedAt ("
                    + grant.issuedAt() + ") — a break-glass grant needs a positive time window");
        } else {
            // 2. Maximum TTL ───────────────────────────────────────────────────────────────────
            Duration ttl = Duration.between(grant.issuedAt(), grant.expiresAt());
            if (ttl.compareTo(maxTtl) > 0) {
                v.add("break-glass TTL " + ttl.toMinutes() + "m exceeds the maximum "
                        + maxTtl.toMinutes() + "m — emergency access must be short-lived");
            }
        }

        // 3. No wildcard ───────────────────────────────────────────────────────────────────────
        if (WILDCARD.equals(grant.action())) {
            v.add("break-glass action must be a single enumerated action, never a wildcard '*'");
        }
        if (WILDCARD.equals(grant.role())) {
            v.add("break-glass role must be a single named role, never a wildcard '*'");
        }

        // 4. Approved allowlists + vocabulary grounding ────────────────────────────────────────
        if (!allowlist.resources().contains(grant.resourceKind())) {
            v.add("resource '" + grant.resourceKind() + "' is not an approved break-glass resource "
                    + allowlist.resources());
        }
        if (!WILDCARD.equals(grant.action()) && !allowlist.actions().contains(grant.action())) {
            v.add("action '" + grant.action() + "' is not an approved break-glass action "
                    + allowlist.actions());
        }
        if (!WILDCARD.equals(grant.role()) && !vocab.roles().contains(grant.role())) {
            v.add("role '" + grant.role() + "' is not in the manifest vocabulary " + vocab.roles());
        }
        if (!vocab.resourceKind().equals(grant.resourceKind())) {
            v.add("grant resource '" + grant.resourceKind() + "' does not match the authoring target '"
                    + vocab.resourceKind() + "'");
        }

        // 5. Tenant-scoped (not the root ceiling) ──────────────────────────────────────────────
        if (grant.scope().isRoot()) {
            v.add("break-glass must target a concrete tenant scope, not the root ceiling scope");
        }

        // 6. Accountable ───────────────────────────────────────────────────────────────────────
        if (grant.justification() == null || grant.justification().isBlank()) {
            v.add("break-glass grant requires a justification (emergency access is always accountable)");
        }

        return v.isEmpty() ? Result.accept() : Result.reject(v);
    }
}
