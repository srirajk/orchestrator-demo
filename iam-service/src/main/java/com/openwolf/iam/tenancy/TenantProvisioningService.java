package com.openwolf.iam.tenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Orchestrates atomic tenant provisioning / deprovisioning (Axiom Story B4 — the capstone).
 *
 * <p><b>Atomic visibility.</b> Creating a tenant stages four artifacts — a Cerbos deny-all policy
 * bootstrap ({@link PolicyBootstrapAdapter}), a Redis namespace ({@link TenantNamespaceAdapter}), a
 * registry manifest space ({@link RegistrySpaceAdapter}), and an audit partition
 * ({@link AuditPartitionAdapter}) — and only THEN flips the {@link ActiveTenantDirectory} with a
 * compare-and-set as the very LAST step. On an injected/crash failure the tenant is ABSENT from the
 * directory (so it resolves as unknown and every gateway request under it fails closed at the A2 seam
 * with zero I/O — a half-provisioned tenant is fully unusable) AND the already-staged NON-AUDIT
 * artifacts are COMPENSATED (H6): the Redis namespace/index, registry space, and staged policy bundle
 * are cleaned so a failed run leaves no orphaned artifact — not merely absence from the directory. The
 * AUDIT partition is append-only evidence and is never deleted. The persisted
 * {@link ProvisioningOperation} (keyed by idempotency key) records how far a run got; a retry with the
 * same key resumes and idempotently reconciles (content-addressed policy version + idempotent adapters
 * ⇒ one active tenant, no conflicting artifacts).
 *
 * <p><b>Deprovision</b> reverses the order: remove the tenant from the directory FIRST (every gateway
 * request then fails closed), wait for gateway instances to ack the new directory version, then clean
 * up non-audit Redis/registry artifacts and retain the policy bundles for the evidence-retention
 * period. The audit partition is NEVER deleted — a deprovisioned tenant's evidence stays exportable.
 *
 * <p>This service owns activation/deactivation ordering only; the adapters own each artifact. It never
 * embeds domain knowledge — a tenant id is an opaque canonical string.
 */
@Service
public class TenantProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);

    private final ProvisioningOperationRepository operations;
    private final ActiveTenantDirectory directory;
    private final PolicyBootstrapAdapter policyAdapter;
    private final TenantNamespaceAdapter namespaceAdapter;
    private final RegistrySpaceAdapter registryAdapter;
    private final AuditPartitionAdapter auditAdapter;

    public TenantProvisioningService(ProvisioningOperationRepository operations,
                                     ActiveTenantDirectory directory,
                                     PolicyBootstrapAdapter policyAdapter,
                                     TenantNamespaceAdapter namespaceAdapter,
                                     RegistrySpaceAdapter registryAdapter,
                                     AuditPartitionAdapter auditAdapter) {
        this.operations = operations;
        this.directory = directory;
        this.policyAdapter = policyAdapter;
        this.namespaceAdapter = namespaceAdapter;
        this.registryAdapter = registryAdapter;
        this.auditAdapter = auditAdapter;
    }

    /**
     * Provision (or reconcile a prior partial run of) a tenant. Idempotent on {@code idempotencyKey}:
     * a completed run returns the same active result; a failed/partial run resumes from the ledger.
     *
     * @throws ProvisioningException if a staging step fails — the tenant stays absent from the directory
     */
    @Transactional
    public ProvisioningResult provision(ProvisioningRequest request, String idempotencyKey, String actor) {
        String key = requireKey(idempotencyKey);
        String tenantId = request.tenantId();

        ProvisioningOperation op = operations.findByIdempotencyKey(key)
                .map(existing -> {
                    if (existing.getKind() != ProvisioningOperation.Kind.PROVISION) {
                        throw new ProvisioningException("idempotency key '" + key
                                + "' already used for a " + existing.getKind() + " operation");
                    }
                    if (!existing.getTenantId().equals(tenantId)) {
                        throw new ProvisioningException("idempotency key '" + key
                                + "' already bound to tenant '" + existing.getTenantId() + "'");
                    }
                    return existing;
                })
                .orElseGet(() -> operations.save(new ProvisioningOperation(
                        key, tenantId, request.name(), request.slug(),
                        ProvisioningOperation.Kind.PROVISION)));

        // Already fully provisioned — idempotent no-op (also self-heals the directory if needed).
        if (op.getStatus() == ProvisioningOperation.Status.ACTIVE && directory.isActive(tenantId)) {
            log.info("Provision '{}' (key {}) already ACTIVE — idempotent no-op", tenantId, key);
            return ProvisioningResult.of(op, directory.version());
        }

        // Track the staged policy bundle so a failure can COMPENSATE it (H6) — a failed run must leave
        // no orphaned staged artifact, not merely be absent from the directory.
        TenantBootstrapBundle bundle = null;
        try {
            // ── Stage the four artifacts. Each adapter is idempotent, so a retry re-runs safely. ──

            // 1. Cerbos policy bootstrap — deny-all bundle, content-addressed, staged + probed.
            bundle = policyAdapter.stage(tenantId);
            policyAdapter.probe(bundle);
            op.setPolicyVersion(bundle.policyVersion());
            op.markStaged(ProvisioningOperation.Artifact.POLICY);
            operations.save(op);

            // 2. Redis namespace + per-tenant index.
            namespaceAdapter.createNamespace(tenantId);
            op.markStaged(ProvisioningOperation.Artifact.REDIS);
            operations.save(op);

            // 3. Registry manifest space.
            registryAdapter.createSpace(tenantId);
            op.markStaged(ProvisioningOperation.Artifact.REGISTRY);
            operations.save(op);

            // 4. Audit partition (materialised by the genesis event).
            auditAdapter.recordProvisioned(tenantId, actor, op.getIdempotencyKey());
            op.markStaged(ProvisioningOperation.Artifact.AUDIT);
            operations.save(op);

            // ── Verify all four before ANY visibility. A gap here keeps the tenant unusable. ──
            verifyAllStaged(op, tenantId);

            // ── Activation compare-and-set — the LAST step. Only now is the tenant visible. ──
            long directoryVersion = directory.activate(tenantId, bundle.policyVersion());
            op.setStatus(ProvisioningOperation.Status.ACTIVE);
            op.setLastError(null);
            operations.save(op);
            log.info("Provisioned tenant '{}' — ACTIVE at directory v{} (policyVersion={})",
                    tenantId, directoryVersion, bundle.policyVersion());
            return ProvisioningResult.of(op, directoryVersion);

        } catch (RuntimeException e) {
            // COMPENSATE (H6): clean the already-staged NON-AUDIT artifacts so a failed run leaves no
            // orphaned Redis namespace/index or staged policy bundle — not merely absence from the
            // directory. Runs BEFORE the FAILED save so the durable ledger reflects the compensated state.
            compensateStaged(op, tenantId, bundle);

            op.setStatus(ProvisioningOperation.Status.FAILED);
            op.setLastError(e.toString());
            operations.save(op);
            // FAIL CLOSED: the tenant is NOT in the directory; a request under it resolves unknown at A2.
            log.warn("FAIL-CLOSED: provision of tenant '{}' failed after staging {} — staged artifacts "
                            + "compensated, tenant is ABSENT from the active directory and fully unusable "
                            + "(retry key {} to reconcile): {}",
                    tenantId, op.stagedArtifacts(), key, e.toString());
            throw (e instanceof ProvisioningException pe)
                    ? pe : new ProvisioningException("provisioning failed for tenant '" + tenantId + "'", e);
        }
    }

    /**
     * H6 compensation for a FAILED provision: clean up the NON-AUDIT artifacts already staged in this
     * run. Redis namespace/index and the staged policy bundle are debris from a never-activated run and
     * MUST NOT linger. The registry space is likewise cleaned. The AUDIT partition is append-only
     * evidence (the genesis event) and is NEVER deleted — matching the deprovision contract. Each step
     * is best-effort and guarded so a cleanup failure never masks the original provisioning error.
     */
    private void compensateStaged(ProvisioningOperation op, String tenantId, TenantBootstrapBundle bundle) {
        if (op.isStaged(ProvisioningOperation.Artifact.REGISTRY)) {
            try {
                registryAdapter.removeSpace(tenantId);
            } catch (RuntimeException ce) {
                log.warn("H6 compensation: failed to remove registry space for '{}': {}", tenantId, ce.toString());
            }
        }
        if (op.isStaged(ProvisioningOperation.Artifact.REDIS)) {
            try {
                namespaceAdapter.removeNamespace(tenantId);
            } catch (RuntimeException ce) {
                log.warn("H6 compensation: failed to remove Redis namespace for '{}': {}", tenantId, ce.toString());
            }
        }
        if (bundle != null) {
            try {
                policyAdapter.discardStaged(bundle);
            } catch (RuntimeException ce) {
                log.warn("H6 compensation: failed to discard staged policy bundle {} for '{}': {}",
                        bundle.policyVersion(), tenantId, ce.toString());
            }
        }
    }

    /**
     * Deprovision a tenant: revoke visibility, retain audit. Idempotent on {@code idempotencyKey}.
     * Ordering (B4): directory deactivation is FIRST and waits for the gateway directory-version ack
     * before deleting any non-audit artifact.
     */
    @Transactional
    public void deprovision(String tenantId, String idempotencyKey, String actor) {
        String key = requireKey(idempotencyKey);

        ProvisioningOperation op = operations.findByIdempotencyKey(key)
                .orElseGet(() -> operations.save(new ProvisioningOperation(
                        key, tenantId, tenantId, tenantId, ProvisioningOperation.Kind.DEPROVISION)));
        if (op.getStatus() == ProvisioningOperation.Status.DEACTIVATED) {
            log.info("Deprovision '{}' (key {}) already DEACTIVATED — idempotent no-op", tenantId, key);
            return;
        }

        // 1. FIRST: remove from the active directory. Every subsequent gateway request fails closed.
        long revokedVersion = directory.deactivate(tenantId);

        // 2. Wait for all gateway instances to ack the new directory version before touching artifacts.
        awaitDirectoryVersionAck(revokedVersion);

        // 3. Only now, clean up NON-AUDIT artifacts. Policy bundles are retained (evidence-retention).
        namespaceAdapter.removeNamespace(tenantId);
        registryAdapter.removeSpace(tenantId);

        // 4. The audit partition is NEVER deleted — record the deprovision as retained evidence.
        auditAdapter.recordDeprovisioned(tenantId, actor, op.getIdempotencyKey());

        op.setStatus(ProvisioningOperation.Status.DEACTIVATED);
        operations.save(op);
        log.info("Deprovisioned tenant '{}' — directory revoked at v{}, non-audit artifacts cleaned, "
                + "policy bundles + audit partition RETAINED", tenantId, revokedVersion);
    }

    public Optional<ProvisioningOperation> latestOperation(String tenantId) {
        return operations.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().findFirst();
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    private void verifyAllStaged(ProvisioningOperation op, String tenantId) {
        for (ProvisioningOperation.Artifact artifact : ProvisioningOperation.Artifact.values()) {
            if (!op.isStaged(artifact)) {
                throw new ProvisioningException("artifact " + artifact + " not staged for '" + tenantId + "'");
            }
        }
        if (!namespaceAdapter.namespaceExists(tenantId)) {
            throw new ProvisioningException("redis namespace missing for '" + tenantId + "'");
        }
        if (!registryAdapter.spaceExists(tenantId)) {
            throw new ProvisioningException("registry space missing for '" + tenantId + "'");
        }
        if (!auditAdapter.partitionExists(tenantId)) {
            throw new ProvisioningException("audit partition missing for '" + tenantId + "'");
        }
    }

    /**
     * Single-instance: the directory swap is synchronous, so the new version is immediately
     * effective and the ack is a no-op. In a multi-instance deployment this blocks until every
     * gateway replica reports it has installed a snapshot ≥ {@code version} (each replica polls the
     * directory out of band, A2). That fan-in is the deferred multi-instance seam.
     */
    protected void awaitDirectoryVersionAck(long version) {
        log.debug("Directory version v{} acked (single-instance; multi-instance fan-in is the seam)", version);
    }

    private static String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ProvisioningException("idempotency key is required");
        }
        return idempotencyKey.trim();
    }
}
