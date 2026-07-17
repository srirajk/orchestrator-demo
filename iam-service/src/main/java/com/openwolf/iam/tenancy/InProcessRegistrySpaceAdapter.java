package com.openwolf.iam.tenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-instance stand-in for the registry-space artifact (Axiom B4). The real call is the
 * registry-service's {@code RegistryIngestor.ingestTenant(ctx)} (the seam it documents as "the seam
 * B4 calls per provisioned tenant"), which lives in the gateway/registry module and process — the
 * deferred multi-instance seam. This impl tracks the per-tenant manifest space in process so
 * provisioning orchestration, idempotent retry, and cleanup ordering are real and testable.
 */
@Component
public class InProcessRegistrySpaceAdapter implements RegistrySpaceAdapter {

    private static final Logger log = LoggerFactory.getLogger(InProcessRegistrySpaceAdapter.class);

    private final Set<String> created = ConcurrentHashMap.newKeySet();

    @Override
    public String createSpace(String tenantId) {
        String path = spacePath(tenantId);
        created.add(tenantId); // idempotent
        log.info("Provisioned registry manifest space '{}' for tenant '{}' "
                        + "(in-process; live RegistryIngestor.ingestTenant is the multi-instance seam)",
                path, tenantId);
        return path;
    }

    @Override
    public boolean spaceExists(String tenantId) {
        return created.contains(tenantId);
    }

    @Override
    public void removeSpace(String tenantId) {
        created.remove(tenantId);
        log.info("Removed registry manifest space '{}' for deprovisioned tenant '{}'",
                spacePath(tenantId), tenantId);
    }

    static String spacePath(String tenantId) {
        return "tenants/" + tenantId + "/manifests";
    }
}
