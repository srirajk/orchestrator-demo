package com.openwolf.iam.repository;

import com.openwolf.iam.entity.PersonalResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PersonalResourceRepository extends JpaRepository<PersonalResource, UUID> {

    List<PersonalResource> findByPrincipalIdAndResourceType(String principalId, String resourceType);

    List<PersonalResource> findByPrincipalId(String principalId);

    Optional<PersonalResource> findByPrincipalIdAndResourceTypeAndResourceId(
            String principalId, String resourceType, String resourceId);

    void deleteByPrincipalIdAndResourceTypeAndResourceId(
            String principalId, String resourceType, String resourceId);
}
