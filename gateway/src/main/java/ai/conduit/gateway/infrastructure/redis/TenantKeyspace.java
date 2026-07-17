package ai.conduit.gateway.infrastructure.redis;

import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * The tenant-aware key/index naming seam (Axiom Story A3).
 *
 * <p>Every Redis key and RediSearch index name the gateway touches is derived here from the
 * request's {@link TenantExecutionContext} (A2), so the tenant segment enters a key name in exactly
 * one place and always from the explicitly-passed, A1-canonical-validated context — never from a raw
 * caller string.
 *
 * <h2>Key space — {@code {context}:{tenant}:...}</h2>
 * The bounded-context invariant and the tenant invariant are <b>independent</b> and both enforced.
 * Context isolation (gateway vs IAM) is a separate Redis instance/namespace per bounded context
 * (see {@code CLAUDE.md} §3); tenant isolation is this seam. This class only owns the
 * <em>{@code {tenant}}</em> segment inside the gateway context; it never reaches into another
 * context's namespace.
 *
 * <h2>Two schemes, and why default stays legacy</h2>
 * <ul>
 *   <li><b>Real (non-default) tenant, multi-tenant ON</b> — value keys get the prefix
 *       {@code t:{tenant}:} (e.g. {@code t:acme:vec:agent.a:0}); the RediSearch routing index is
 *       <b>per tenant</b>, {@code intent_idx__{tenant}}. Per-index isolation is strictly stronger
 *       than prefix-filtering inside one shared index: a query that forgets the tenant filter still
 *       cannot see another tenant's documents, because they live in a different index.</li>
 *   <li><b>Default tenant, or multi-tenant OFF</b> — the <b>legacy</b> names ({@code intent_idx},
 *       {@code vec:...}) with NO {@code __tenant} suffix and NO {@code t:{tenant}:} prefix. This is
 *       load-bearing: the registry-service ingestion (the WRITE side; per-tenant ingestion is A4,
 *       not built yet) still writes the legacy {@code intent_idx} / {@code vec:} scheme, and the
 *       gateway only READS it. If a default-tenant routing query read {@code intent_idx__default}
 *       while the index is named {@code intent_idx}, the single-tenant demo would die. So the seam
 *       resolves to legacy for the default tenant; {@code intent_idx__{tenant}} activates only for
 *       real tenants (which do not exist until A4/B4).</li>
 * </ul>
 *
 * <h2>Isolation-boundary honesty</h2>
 * Namespacing scopes the gateway's own application commands; it is <b>not</b> protection against a
 * raw Redis operator. A privileged client with {@code SCAN}/{@code FT._LIST}/{@code KEYS} can still
 * enumerate every tenant's keys. The application-level answer is that all gateway commands go through
 * a tenant-qualified facade with no unqualified {@code SCAN}/{@code FT._LIST}
 * ({@link TenantRedisFacade}); the infrastructure-level answer (out of A3 scope) is Redis ACLs or a
 * separate instance per tenant.
 */
@Component
public class TenantKeyspace {

    /** Canonical tenant-id grammar — identical to IAM's {@code TenantClaims.CANONICAL_TENANT_ID}. */
    static final Pattern CANONICAL_TENANT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    private final boolean multiTenantEnabled;
    private final String defaultTenant;

    public TenantKeyspace(
            @Value("${conduit.tenancy.multi-tenant.enabled:false}") boolean multiTenantEnabled,
            @Value("${conduit.tenancy.default-tenant:default}") String defaultTenant) {
        this.multiTenantEnabled = multiTenantEnabled;
        this.defaultTenant = (defaultTenant == null || defaultTenant.isBlank())
                ? "default" : defaultTenant.trim();
    }

    /**
     * The legacy/single-tenant regime for this context: either multi-tenancy is OFF, or the context
     * is absent/unresolved, or the tenant is the configured default. In this regime every name is a
     * legacy name so the existing (registry-written) routing index and the running demo keep working.
     */
    public boolean isLegacy(TenantExecutionContext ctx) {
        if (!multiTenantEnabled) return true;
        String segment = rawSegment(ctx);
        return segment == null || segment.equals(defaultTenant);
    }

    /**
     * H5 fail-closed predicate for the data plane. Under multi-tenant enforcement a routing (or other
     * data-plane) query MUST carry a resolved tenant; a null/absent tenant here would otherwise resolve
     * to the shared legacy {@code intent_idx} — the fail-open hole the security audit flagged. Returns
     * {@code true} when such a query must be DENIED rather than served from the legacy index. Always
     * {@code false} with multi-tenancy OFF (the single-tenant demo) and for the configured default
     * tenant (which legitimately keeps legacy names), so nothing about the demo path changes.
     */
    public boolean deniesTenantlessDataRoute(TenantExecutionContext ctx) {
        return multiTenantEnabled && rawSegment(ctx) == null;
    }

    /**
     * The RediSearch index name for this context. Legacy ⇒ {@code legacyIndexName} unchanged; a real
     * tenant ⇒ {@code legacyIndexName__{tenant}} (per-tenant index — stronger than a shared-index
     * prefix filter).
     */
    public String indexName(String legacyIndexName, TenantExecutionContext ctx) {
        if (isLegacy(ctx)) return legacyIndexName;
        return legacyIndexName + "__" + segment(ctx);
    }

    /**
     * The value-key prefix for this context. Legacy ⇒ empty (keys unchanged); a real tenant ⇒
     * {@code t:{tenant}:}. Compose ahead of a legacy key/prefix, e.g. {@code keyPrefix(ctx) + "vec:"}.
     */
    public String keyPrefix(TenantExecutionContext ctx) {
        if (isLegacy(ctx)) return "";
        return "t:" + segment(ctx) + ":";
    }

    /** Qualifies a legacy value key for this context: legacy ⇒ unchanged; real tenant ⇒ {@code t:{tenant}:key}. */
    public String key(String legacyKey, TenantExecutionContext ctx) {
        return keyPrefix(ctx) + legacyKey;
    }

    /**
     * The validated, canonical tenant segment used in names — the configured default in the legacy
     * regime, otherwise the context's canonical tenant id. Never a raw caller string: a non-default
     * segment that fails the canonical grammar fails closed rather than entering a key/index name.
     */
    public String segment(TenantExecutionContext ctx) {
        if (isLegacy(ctx)) return defaultTenant;
        String segment = rawSegment(ctx);
        if (!CANONICAL_TENANT_ID.matcher(segment).matches()) {
            throw new IllegalArgumentException(
                    "tenant segment '" + segment + "' violates canonical grammar [a-z0-9][a-z0-9-]{0,62}");
        }
        return segment;
    }

    /** The configured default tenant name. */
    public String defaultTenant() {
        return defaultTenant;
    }

    private String rawSegment(TenantExecutionContext ctx) {
        if (ctx == null) return null;
        String t = ctx.tenantId();
        return (t == null || t.isBlank()) ? null : t.trim();
    }
}
