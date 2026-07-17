package com.openwolf.iam.tenancy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Axiom B4.1 — a half-provisioned tenant is FULLY UNUSABLE. Inject a failure after 2 of the 4
 * artifacts; the tenant must be absent from the active directory, and a request under it must fail
 * closed at the A2 seam with zero I/O. Red if a partial tenant can do anything.
 */
class ProvisioningAtomicityTest {

    private static final String TENANT = "acme";

    @Test
    void halfProvisionedTenantIsFullyUnusable(@TempDir Path staging) {
        ActiveTenantRepository activeRepo = ProvisioningTestSupport.activeRepo();
        ActiveTenantDirectory directory = new ActiveTenantDirectory(activeRepo);

        // POLICY + REDIS succeed; REGISTRY (artifact 3) fails → exactly 2 of 4 staged.
        RegistrySpaceAdapter failingRegistry =
                new ProvisioningTestSupport.FailingRegistryAdapter(new InProcessRegistrySpaceAdapter(), 1);

        TenantProvisioningService service = new TenantProvisioningService(
                ProvisioningTestSupport.opsRepo(),
                directory,
                ProvisioningTestSupport.realPolicyAdapterNoProbe(staging),
                new InProcessTenantNamespaceAdapter(),
                failingRegistry,
                new PersistentAuditPartitionAdapter(ProvisioningTestSupport.auditRepo()));

        assertThatThrownBy(() -> service.provision(
                new ProvisioningRequest(TENANT, "Acme Bank", "acme"), "key-atomic-1", "admin@bank"))
                .isInstanceOf(ProvisioningException.class);

        // The ledger recorded exactly the two artifacts that completed before the failure.
        ProvisioningOperation op = service.latestOperation(TENANT).orElseThrow();
        assertThat(op.getStatus()).isEqualTo(ProvisioningOperation.Status.FAILED);
        assertThat(op.stagedArtifacts()).containsExactlyInAnyOrder(
                ProvisioningOperation.Artifact.POLICY, ProvisioningOperation.Artifact.REDIS);
        assertThat(op.stagedArtifacts()).doesNotContain(
                ProvisioningOperation.Artifact.REGISTRY, ProvisioningOperation.Artifact.AUDIT);

        // The tenant is NOT in the directory — it can do NOTHING.
        assertThat(directory.isActive(TENANT)).isFalse();
        assertThat(directory.find(TENANT)).isEmpty();
        assertThat(directory.snapshot().tenantPolicyVersions()).doesNotContainKey(TENANT);

        // Fail-closed at A2 with ZERO I/O: the lookup reads only the in-memory snapshot; the durable
        // directory repository is never touched on a resolve.
        clearInvocations(activeRepo);
        assertThat(directory.find(TENANT)).isEmpty();
        assertThat(directory.find(TENANT)).isEmpty();
        verifyNoInteractions(activeRepo);
    }

    @Test
    void everyStageFailurePointLeavesTheTenantAbsent(@TempDir Path staging) {
        // Whichever single artifact fails, the tenant never becomes visible.
        for (int failAt = 0; failAt < 4; failAt++) {
            ActiveTenantDirectory directory = new ActiveTenantDirectory(ProvisioningTestSupport.activeRepo());
            PolicyBootstrapAdapter policy = ProvisioningTestSupport.realPolicyAdapterNoProbe(staging.resolve("s" + failAt));
            TenantNamespaceAdapter ns = new InProcessTenantNamespaceAdapter();
            RegistrySpaceAdapter reg = new InProcessRegistrySpaceAdapter();
            AuditPartitionAdapter audit = new PersistentAuditPartitionAdapter(ProvisioningTestSupport.auditRepo());

            switch (failAt) {
                case 0 -> policy = new ProvisioningTestSupport.FailingPolicyAdapter(policy, 1);
                case 1 -> ns = new ProvisioningTestSupport.FailingNamespaceAdapter(ns, 1);
                case 2 -> reg = new ProvisioningTestSupport.FailingRegistryAdapter(reg, 1);
                case 3 -> audit = new ProvisioningTestSupport.FailingAuditAdapter(audit, 1);
                default -> { }
            }
            TenantProvisioningService service = new TenantProvisioningService(
                    ProvisioningTestSupport.opsRepo(), directory, policy, ns, reg, audit);

            String tenant = "t" + failAt;
            String key = "k-" + failAt;
            TenantProvisioningService svc = service;
            assertThatThrownBy(() -> svc.provision(
                    new ProvisioningRequest(tenant, tenant, tenant), key, "admin"))
                    .isInstanceOf(ProvisioningException.class);
            assertThat(directory.isActive(tenant))
                    .as("tenant must be absent when artifact %d fails", failAt).isFalse();
        }
    }
}
