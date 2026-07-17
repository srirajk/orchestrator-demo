package com.openwolf.iam.policystudio.lifecycle;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The durable promotion/rollback ledger (Axiom Story C5). The idempotency-key lookup is what makes a
 * retry idempotent: a lost-response retry finds the existing PROMOTED receipt and returns it rather than
 * driving a second CAS (C5.3).
 */
@Repository
public interface PromotionRepository extends JpaRepository<PromotionRecord, UUID> {

    Optional<PromotionRecord> findByIdempotencyKey(String idempotencyKey);

    List<PromotionRecord> findByTenantIdOrderByCreatedAtDesc(String tenantId);
}
