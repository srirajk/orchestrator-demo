package com.openwolf.iam.policystudio.breakglass;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * The two-person gate for issuing a break-glass grant (Axiom Story C6.4). It preserves the SAME
 * separation-of-duties invariant C1 enforces on ordinary policy approval via the
 * {@code policy_approval} Cerbos meta-authz (author≠approver; a superuser is not an auto-approver) —
 * emergency access is expedited, never single-person.
 *
 * <p>On approval the issuance is recorded to the tenant's A6 audit partition
 * ({@link BreakGlassAuditPartition#recordGranted}); each subsequent USE of the grant is recorded via
 * {@link #recordUse}. The grant itself is inert until compiled/promoted — this service authorizes
 * the issuance; the PDP alone enforces the time bound.
 */
@Service
public class BreakGlassApprovalService {

    private final BreakGlassAuditPartition audit;
    private final String approverRole;

    public BreakGlassApprovalService(
            BreakGlassAuditPartition audit,
            @Value("${iam.break-glass.approver-role:studio_policy_approver}") String approverRole) {
        this.audit = audit;
        this.approverRole = approverRole;
    }

    /**
     * Approve and issue a break-glass grant. Fails closed on an inadmissible artifact or an SoD
     * violation; audits the issuance on success.
     *
     * @throws IllegalStateException   if the artifact is not admissible (bounds or C2 gate rejected it)
     * @throws BreakGlassSodException  if the approver is the requester, or lacks the approver role
     */
    public void approveAndIssue(BreakGlassArtifact artifact, String approverId,
                                Set<String> approverRoles, String correlationId) {
        if (!artifact.admissible()) {
            throw new IllegalStateException(
                    "refusing to approve an inadmissible break-glass artifact — bounds="
                            + artifact.boundsResult().violations() + " c2=" + artifact.c2Result().violations());
        }
        String requester = artifact.grant().requestedBy();

        // C6.4 / C1.2 — author≠approver: the requester may never approve their own emergency access.
        if (approverId != null && approverId.equals(requester)) {
            throw new BreakGlassSodException(
                    "separation of duties: '" + approverId + "' requested this break-glass grant and "
                            + "may not also approve it (author≠approver)");
        }
        // C6.4 / C1.3 — approval flows only through a studio approver, never an auto-approving superuser.
        if (approverRoles == null || !approverRoles.contains(approverRole)) {
            throw new BreakGlassSodException(
                    "approver '" + approverId + "' must hold the studio approver role '" + approverRole
                            + "' to approve a break-glass grant (roles=" + approverRoles + ")");
        }

        audit.recordGranted(artifact.grant(), approverId, correlationId);
    }

    /** Record a single use of an issued, still-valid break-glass grant (C6.3). */
    public void recordUse(BreakGlassGrant grant, String principalId, String action, String correlationId) {
        audit.recordUsed(grant, principalId, action, correlationId);
    }
}
