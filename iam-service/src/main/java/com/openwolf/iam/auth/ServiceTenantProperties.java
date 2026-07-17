package com.openwolf.iam.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Explicit tenant binding for non-principal token subjects — i.e. {@code client_credentials}
 * service clients that have no {@code Principal} row (e.g. {@code gateway-client}).
 *
 * <p>Story A1 removes the blanket default-tenant fallback: a token subject that resolves to no
 * principal is un-mintable <em>unless</em> it is a service client with an explicit tenant binding
 * declared here (config, not caller-supplied). An unknown service subject stays un-mintable — the
 * enricher throws rather than guessing a tenant.
 *
 * <p>Bound from {@code conduit.iam.service-tenants.<client-id>=<tenant-id>}.
 */
@Component
@ConfigurationProperties(prefix = "conduit.iam")
public class ServiceTenantProperties {

    /** client-id → tenant-id for machine (client_credentials) subjects. */
    private Map<String, String> serviceTenants = new HashMap<>();

    public Map<String, String> getServiceTenants() {
        return serviceTenants;
    }

    public void setServiceTenants(Map<String, String> serviceTenants) {
        this.serviceTenants = serviceTenants;
    }

    /** Returns the bound tenant for a service subject, or {@code null} if none is declared. */
    public String tenantFor(String subject) {
        return subject == null ? null : serviceTenants.get(subject);
    }
}
