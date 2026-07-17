package com.openwolf.iam.tenancy;

import com.openwolf.iam.entity.AuditLog;
import com.openwolf.iam.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Real audit-partition adapter (Axiom B4, A6) over the {@code audit_log} table. The tenant's audit
 * partition is the {@code tenant_id}-scoped slice; writing the genesis event materialises it, and
 * {@link #export} reads it back scoped to the tenant. There is deliberately NO delete method — the
 * partition is never removed, so a deprovisioned tenant's evidence remains exportable.
 */
@Component
public class PersistentAuditPartitionAdapter implements AuditPartitionAdapter {

    private static final Logger log = LoggerFactory.getLogger(PersistentAuditPartitionAdapter.class);

    private static final String RESOURCE_TYPE = "tenant";
    private static final String ACTION_PROVISIONED = "tenant.provisioned";
    private static final String ACTION_DEPROVISIONED = "tenant.deprovisioned";

    private final AuditLogRepository auditLogRepository;

    public PersistentAuditPartitionAdapter(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    public void recordProvisioned(String tenantId, String actor, String correlationId) {
        auditLogRepository.save(new AuditLog(
                tenantId, actor, "system", ACTION_PROVISIONED, RESOURCE_TYPE, tenantId,
                null, "{\"event\":\"provisioned\"}", null, correlationId));
        log.info("Audit partition materialised for tenant '{}' (genesis event recorded)", tenantId);
    }

    @Override
    public void recordDeprovisioned(String tenantId, String actor, String correlationId) {
        // APPEND — the partition is retained; this is the deprovision evidence event, not a delete.
        auditLogRepository.save(new AuditLog(
                tenantId, actor, "system", ACTION_DEPROVISIONED, RESOURCE_TYPE, tenantId,
                null, "{\"event\":\"deprovisioned\",\"audit_retained\":true}", null, correlationId));
        log.info("Recorded deprovision event into tenant '{}' audit partition (partition retained)", tenantId);
    }

    @Override
    public boolean partitionExists(String tenantId) {
        return !auditLogRepository.findByTenantIdOrderByOccurredAtDesc(tenantId).isEmpty();
    }

    @Override
    public List<AuditLog> export(String tenantId) {
        return auditLogRepository.findByTenantIdOrderByOccurredAtDesc(tenantId);
    }
}
