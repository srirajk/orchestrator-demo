package ai.conduit.gateway.registry.readiness;

import ai.conduit.gateway.domain.auth.ProvisionedTenantDirectory;
import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import ai.conduit.gateway.infrastructure.expression.ExpressionDialect;
import ai.conduit.gateway.infrastructure.redis.TenantKeyspace;
import ai.conduit.gateway.registry.embedding.QueryEmbedder;
import ai.conduit.gateway.registry.index.VectorIndex;
import ai.conduit.gateway.registry.service.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Refuses to start the gateway unless the registry ingestion job has produced a routing index that
 * this gateway can actually search — <em>and</em> tracks that verdict <b>per tenant</b> (Axiom A4),
 * so one tenant's broken or empty ingest fails only that tenant closed while healthy tenants serve.
 *
 * <p>The gateway no longer builds the index, so it must not assume one is there. Three ways the
 * assumption fails, all of which were previously silent:
 *
 * <ul>
 *   <li><b>No index.</b> Every search returns nothing, every question falls through to a
 *       clarification, and the gateway reports itself healthy.</li>
 *   <li><b>No agents.</b> Same symptom, different cause: the index exists but ingestion never
 *       registered anything.</li>
 *   <li><b>A different model.</b> The index was built by one embedding model and this gateway
 *       embeds queries with another. Cosine similarity across two vector spaces is arithmetic
 *       without meaning: it returns confident numbers for unrelated comparisons, so routing degrades
 *       into plausible nonsense with no error anywhere.</li>
 * </ul>
 *
 * <h2>Process readiness vs. per-tenant readiness (A4)</h2>
 * The <b>default/legacy</b> tenant is a process-level gate: if its index is missing, empty, or
 * model-mismatched the gateway <em>refuses to start</em> (unchanged pre-A4 behaviour — the
 * single-tenant demo must fail closed loudly). Every other provisioned tenant is verified
 * <em>independently</em> against its own {@code intent_idx__{tenant}} index and its own model/dialect
 * stamp; a failure there marks only that tenant not-ready in an <b>immutable per-tenant readiness
 * map</b> and never blocks startup or contaminates a sibling. Routing operations call
 * {@link #requireReady(TenantExecutionContext)}: a not-ready tenant is denied with
 * {@link TenantRegistryNotReadyException} even though its {@link TenantExecutionContext} resolves.
 *
 * <p>A gateway that cannot route <em>a tenant</em> is not a degraded gateway for that tenant; it is a
 * gateway that would answer the wrong questions. Better to deny that tenant and keep serving the rest.
 */
@Component
@Profile("!registry")
@ConditionalOnProperty(name = "conduit.registry.readiness.enabled", havingValue = "true", matchIfMissing = true)
public class RegistryReadinessVerifier {

    private static final Logger log = LoggerFactory.getLogger(RegistryReadinessVerifier.class);

    private static final String REMEDY =
            "The registry service ingests the manifests and builds the index; wait for it to become "
            + "healthy (docker compose up -d registry-service) before starting the gateway.";

    private final VectorIndex vectorIndex;
    private final AgentRegistry registry;
    private final QueryEmbedder queryEmbedder;
    private final TenantKeyspace keyspace;
    @Nullable
    private final ProvisionedTenantDirectory directory;
    private final boolean multiTenantEnabled;

    /** True once the default/legacy tenant has passed the strict process-level gate. */
    private volatile boolean defaultReady = false;
    /** Immutable per-tenant readiness snapshot, swapped atomically on (re)computation. */
    private final AtomicReference<Map<String, TenantReadiness>> readiness =
            new AtomicReference<>(Map.of());

    /**
     * Single-tenant constructor: no tenant seam, no directory. {@link #verify()} checks only the
     * default/legacy tenant against the legacy index names — byte-identical to the pre-A4 gate. Kept
     * so single-tenant wiring and mock-based unit tests need no directory.
     */
    public RegistryReadinessVerifier(VectorIndex vectorIndex,
                                     AgentRegistry registry,
                                     QueryEmbedder queryEmbedder) {
        this(vectorIndex, registry, queryEmbedder, new TenantKeyspace(false, "default"), null, false);
    }

    @Autowired
    public RegistryReadinessVerifier(VectorIndex vectorIndex,
                                     AgentRegistry registry,
                                     QueryEmbedder queryEmbedder,
                                     TenantKeyspace keyspace,
                                     @Nullable ProvisionedTenantDirectory directory,
                                     @org.springframework.beans.factory.annotation.Value(
                                             "${conduit.tenancy.multi-tenant.enabled:false}") boolean multiTenantEnabled) {
        this.vectorIndex        = vectorIndex;
        this.registry           = registry;
        this.queryEmbedder      = queryEmbedder;
        this.keyspace           = keyspace;
        this.directory          = directory;
        this.multiTenantEnabled = multiTenantEnabled;
    }

    @PostConstruct
    public void verify() {
        // 1) Default/legacy tenant — the process-level gate. Throws → the gateway refuses to start.
        verifyDefaultOrThrow();
        defaultReady = true;

        // 2) Every other provisioned tenant — verified independently, never fatal.
        refreshReadiness(discoverNonDefaultTenants());

        log.info("Registry ready — default tenant indexed by model '{}', expression dialect '{}'; "
                        + "per-tenant readiness: {}",
                queryEmbedder.modelId(), ExpressionDialect.CURRENT, readiness.get());
    }

    /**
     * The strict, process-fatal gate for the default/legacy tenant. Unchanged from pre-A4: a missing,
     * empty, unstamped, model-mismatched, or dialect-skewed default index throws and the gateway does
     * not start.
     */
    private void verifyDefaultOrThrow() {
        if (!vectorIndex.exists()) {
            throw new IllegalStateException(
                    "The routing vector index does not exist. The gateway does not build it. " + REMEDY);
        }

        long agents = registry.count();
        if (agents == 0) {
            throw new IllegalStateException(
                    "The routing vector index exists but no agents are registered. " + REMEDY);
        }

        String stamped = vectorIndex.stampedModelId();
        String current = queryEmbedder.modelId();
        if (stamped == null) {
            throw new IllegalStateException(
                    "The routing vector index carries no embedding-model stamp, so it cannot be shown "
                    + "to share a vector space with this gateway's query embedder (" + current + "). " + REMEDY);
        }
        if (!stamped.equals(current)) {
            throw new IllegalStateException(
                    "The routing vector index was built by embedding model '" + stamped
                    + "' but this gateway embeds queries with '" + current
                    + "'. Searching one vector space with another's vectors yields confident nonsense. "
                    + REMEDY);
        }

        verifyExprDialect();
    }

    /**
     * Refuse to start on a manifest-expression dialect skew. Manifest expressions are a language; if
     * the registry ingested them in one dialect and this gateway evaluates in another, every
     * expression mis-evaluates. Same failure mode as the embedding-model stamp, different axis.
     */
    private void verifyExprDialect() {
        String stampedDialect = vectorIndex.stampedExprDialect();
        String currentDialect = ExpressionDialect.CURRENT;
        if (stampedDialect == null) {
            throw new IllegalStateException(
                    "The routing index carries no expression-dialect stamp, so it cannot be shown to have "
                    + "been ingested in the dialect this gateway evaluates (" + currentDialect + "). " + REMEDY);
        }
        if (!stampedDialect.equals(currentDialect)) {
            throw new IllegalStateException(
                    "The routing index was ingested in expression dialect '" + stampedDialect
                    + "' but this gateway evaluates manifest expressions in '" + currentDialect
                    + "'. Every select/condition/map/figure expression would mis-evaluate. " + REMEDY);
        }
    }

    // ── per-tenant readiness (Axiom A4) ──────────────────────────────────────────────────────────

    /**
     * Recompute the immutable per-tenant readiness map for the given non-default tenants and swap it
     * in atomically. The default tenant's verdict is always {@link TenantReadiness#ready()} here
     * (it passed the process-level gate in {@link #verify()}, or the caller would never reach this).
     * Never throws: a broken tenant produces a not-ready entry, not an exception.
     *
     * <p>Exposed so a background provisioning refresh (or a test) can rebuild the map when the tenant
     * set changes, without restarting the gateway.
     */
    public void refreshReadiness(Collection<TenantExecutionContext> nonDefaultTenants) {
        Map<String, TenantReadiness> next = new LinkedHashMap<>();
        next.put(keyspace.defaultTenant(), TenantReadiness.available());
        for (TenantExecutionContext ctx : nonDefaultTenants) {
            if (keyspace.isLegacy(ctx)) continue; // the default is already recorded, strictly
            next.put(keyspace.segment(ctx), evaluate(ctx));
        }
        readiness.set(Map.copyOf(next));
    }

    /**
     * Evaluate one tenant's routing readiness against its own index/stamp — the same three checks as
     * the process gate, but returning a verdict instead of throwing, and scoped entirely to this
     * tenant's {@code intent_idx__{tenant}} index. A sibling tenant is never read.
     */
    public TenantReadiness evaluate(TenantExecutionContext ctx) {
        if (keyspace.isLegacy(ctx)) {
            return defaultReady ? TenantReadiness.available()
                    : TenantReadiness.unavailable("default tenant index failed the process gate");
        }
        if (!vectorIndex.exists(ctx)) {
            return TenantReadiness.unavailable("routing index does not exist");
        }
        if (vectorIndex.documentCount(ctx) == 0) {
            return TenantReadiness.unavailable("routing index exists but no agents are indexed");
        }
        String stamped = vectorIndex.stampedModelId(ctx);
        String current = queryEmbedder.modelId();
        if (stamped == null) {
            return TenantReadiness.unavailable("routing index carries no embedding-model stamp");
        }
        if (!stamped.equals(current)) {
            return TenantReadiness.unavailable("routing index built by model '" + stamped
                    + "' but this gateway queries with '" + current + "' — confident nonsense");
        }
        String stampedDialect = vectorIndex.stampedExprDialect(ctx);
        if (stampedDialect == null || !stampedDialect.equals(ExpressionDialect.CURRENT)) {
            return TenantReadiness.unavailable("expression-dialect skew (stamped '" + stampedDialect
                    + "', gateway evaluates '" + ExpressionDialect.CURRENT + "')");
        }
        return TenantReadiness.available();
    }

    /**
     * Whether routing may proceed for this tenant. The default/legacy tenant is ready iff the process
     * gate passed; any other tenant is ready iff its entry in the immutable readiness map says so.
     * An unknown tenant is not ready (fail closed).
     */
    public boolean isReady(TenantExecutionContext ctx) {
        if (keyspace.isLegacy(ctx)) {
            return defaultReady;
        }
        TenantReadiness verdict = readiness.get().get(keyspace.segment(ctx));
        return verdict != null && verdict.ready();
    }

    /**
     * Fail a routing operation closed if this tenant's registry is not ready. The tenant is known and
     * its context resolved (A2) — this is a per-tenant deny, not a process crash.
     */
    public void requireReady(TenantExecutionContext ctx) {
        if (isReady(ctx)) return;
        String segment = keyspace.isLegacy(ctx) ? keyspace.defaultTenant() : keyspace.segment(ctx);
        TenantReadiness verdict = readiness.get().get(segment);
        String reason = verdict != null ? verdict.reason() : "tenant not provisioned or never evaluated";
        throw new TenantRegistryNotReadyException(segment, reason);
    }

    /** An immutable snapshot of the current per-tenant readiness map. */
    public Map<String, TenantReadiness> readiness() {
        return readiness.get();
    }

    private List<TenantExecutionContext> discoverNonDefaultTenants() {
        if (!multiTenantEnabled || directory == null || !directory.hasSnapshot()) {
            return List.of();
        }
        // The directory exposes lookups, not a full listing, in A2. Until B4 provisioning surfaces the
        // provisioned set to the gateway, non-default tenants are discovered lazily (a request for a
        // provisioned-but-unevaluated tenant is denied fail-closed until refreshReadiness runs for it).
        return List.of();
    }

    /** A tenant's routing-readiness verdict — ready, or not-ready with a human reason. */
    public record TenantReadiness(boolean ready, String reason) {
        public static TenantReadiness available() {
            return new TenantReadiness(true, "ready");
        }
        public static TenantReadiness unavailable(String reason) {
            return new TenantReadiness(false, reason);
        }
        @Override
        public String toString() {
            return ready ? "ready" : "not-ready(" + reason + ")";
        }
    }
}
