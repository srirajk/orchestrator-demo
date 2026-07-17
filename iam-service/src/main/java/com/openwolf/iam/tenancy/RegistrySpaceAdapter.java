package com.openwolf.iam.tenancy;

/**
 * The registry-space artifact of tenant provisioning (Axiom B4, A4). A provisioned tenant gets its
 * own per-tenant manifest space (the folder {@code tenants/{tenant}/manifests/**} that
 * {@code RegistryIngestor.ingestTenant(ctx)} reads to build the tenant's routing index). Like the
 * Redis namespace this belongs to the registry bounded context, so the live call is the deferred
 * seam; provisioning orchestrates and records it.
 */
public interface RegistrySpaceAdapter {

    /** Create (idempotently) the tenant's manifest space; return its logical path. */
    String createSpace(String tenantId);

    boolean spaceExists(String tenantId);

    /** Remove the per-tenant manifest space on deprovision — only AFTER the directory-version ack. */
    void removeSpace(String tenantId);
}
