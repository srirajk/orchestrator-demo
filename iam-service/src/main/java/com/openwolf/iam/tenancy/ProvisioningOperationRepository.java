package com.openwolf.iam.tenancy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The provisioning-operation ledger (Axiom B4). The idempotency key is unique, so a retry loads the
 * same row and resumes/reconciles instead of starting a conflicting second run.
 */
@Repository
public interface ProvisioningOperationRepository extends JpaRepository<ProvisioningOperation, UUID> {

    Optional<ProvisioningOperation> findByIdempotencyKey(String idempotencyKey);

    List<ProvisioningOperation> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
