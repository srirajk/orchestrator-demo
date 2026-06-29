package com.openwolf.iam.repository;

import com.openwolf.iam.entity.Principal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PrincipalRepository extends JpaRepository<Principal, String> {

    Optional<Principal> findByUsername(String username);

    List<Principal> findByTenantId(String tenantId);

    List<Principal> findByTenantIdAndIsActiveTrue(String tenantId);

    /**
     * Finds principals where the JSONB attributes contain the given domain in admin_domains array.
     * Uses PostgreSQL JSONB containment operator {@code @>}.
     */
    @Query(value = "SELECT * FROM principals WHERE tenant_id = :tenantId AND attributes @> CAST(:domainQuery AS jsonb)",
           nativeQuery = true)
    List<Principal> findDomainAdminsByTenantId(@Param("tenantId") String tenantId,
                                               @Param("domainQuery") String domainQuery);

    /**
     * Find all principals whose password_hash equals the given placeholder (used by DataSeeder).
     */
    @Query("SELECT p FROM Principal p WHERE p.passwordHash = :placeholder")
    List<Principal> findByPasswordHashPlaceholder(@Param("placeholder") String placeholder);
}
