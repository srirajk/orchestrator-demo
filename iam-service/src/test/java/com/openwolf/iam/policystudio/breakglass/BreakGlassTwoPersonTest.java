package com.openwolf.iam.policystudio.breakglass;

import com.openwolf.iam.policystudio.CanonicalPolicyWriter;
import com.openwolf.iam.policystudio.GeneratedPolicyValidator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C6.4 — the expedited break-glass path still enforces two-person control (the SAME SoD invariant C1's
 * {@code policy_approval} meta-authz enforces): the requester may not approve their own grant
 * (author≠approver), and the approver must hold the studio approver role (a superuser is not an
 * auto-approver). Emergency access is faster, never single-person.
 */
class BreakGlassTwoPersonTest {

    private final BreakGlassAuthoringService authoring = new BreakGlassAuthoringService(
            new BreakGlassValidator(60),
            new BreakGlassPolicyCompiler(),
            new GeneratedPolicyValidator(),
            new CanonicalPolicyWriter());

    private BreakGlassApprovalService approvals() {
        return new BreakGlassApprovalService(
                new PersistentBreakGlassAuditPartition(BreakGlassFixtures.auditRepo()),
                "studio_policy_approver");
    }

    private BreakGlassArtifact admissibleArtifact(String requestedBy) {
        BreakGlassArtifact art = authoring.author(
                BreakGlassFixtures.grant("acme", 900, requestedBy),
                BreakGlassFixtures.allowlist(),
                BreakGlassFixtures.request("acme"));
        assertThat(art.admissible()).isTrue();
        return art;
    }

    @Test
    void requesterCannotApproveOwnGrant() {
        BreakGlassArtifact art = admissibleArtifact("alice");
        assertThatThrownBy(() -> approvals().approveAndIssue(
                art, "alice", Set.of("studio_policy_approver"), "corr-1"))
                .isInstanceOf(BreakGlassSodException.class)
                .hasMessageContaining("author≠approver");
    }

    @Test
    void approverMustHoldApproverRole() {
        BreakGlassArtifact art = admissibleArtifact("alice");
        // A different human, but WITHOUT the studio approver role (e.g. a platform superuser).
        assertThatThrownBy(() -> approvals().approveAndIssue(
                art, "bob", Set.of("platform_admin"), "corr-2"))
                .isInstanceOf(BreakGlassSodException.class)
                .hasMessageContaining("must hold the studio approver role");
    }

    @Test
    void distinctApproverWithRoleSucceeds() {
        BreakGlassArtifact art = admissibleArtifact("alice");
        PersistentBreakGlassAuditPartition partition =
                new PersistentBreakGlassAuditPartition(BreakGlassFixtures.auditRepo());
        BreakGlassApprovalService svc = new BreakGlassApprovalService(partition, "studio_policy_approver");

        svc.approveAndIssue(art, "carol", Set.of("studio_policy_approver"), "corr-3");

        // Issuance landed in the tenant partition (fully audited — see BreakGlassAuditedTest).
        assertThat(partition.partition("acme")).hasSize(1);
        assertThat(partition.partition("acme").get(0).getAction()).isEqualTo("break_glass.granted");
    }

    @Test
    void inadmissibleArtifactIsNeverApproved() {
        // Cross-tenant → C2-inadmissible → refuse to approve regardless of a valid two-person pair.
        BreakGlassArtifact crossTenant = authoring.author(
                BreakGlassFixtures.grant("beta", 900, "alice"),
                BreakGlassFixtures.allowlist(),
                BreakGlassFixtures.request("acme"));
        assertThat(crossTenant.admissible()).isFalse();
        assertThatThrownBy(() -> approvals().approveAndIssue(
                crossTenant, "carol", Set.of("studio_policy_approver"), "corr-4"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inadmissible");
    }
}
