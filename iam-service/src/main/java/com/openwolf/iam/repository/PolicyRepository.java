package com.openwolf.iam.repository;

import com.openwolf.iam.entity.Policy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PolicyRepository extends JpaRepository<Policy, UUID> {

    List<Policy> findByTenantId(String tenantId);

    List<Policy> findByTenantIdAndStatus(String tenantId, String status);

    Optional<Policy> findByTenantIdAndName(String tenantId, String name);
}
