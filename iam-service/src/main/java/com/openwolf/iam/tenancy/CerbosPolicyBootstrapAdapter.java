package com.openwolf.iam.tenancy;

import com.openwolf.iam.policystudio.CanonicalPolicyWriter;
import com.openwolf.iam.policystudio.CerbosCompileGate;
import com.openwolf.iam.policystudio.PolicyIR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The real Cerbos policy-bootstrap adapter (Axiom B4). Builds a fresh tenant's deny-all bundle from
 * the current approved base ceiling and B1's parental-consent posture, WITHOUT mutating the live
 * bundle:
 *
 * <ol>
 *   <li>Read every {@code tenant_default_*.yaml} in the base bundle — these enumerate every
 *       base-ceiling {@code (action, role)} tuple per resource kind (their totality is CI-enforced).
 *   <li>For each, emit a {@code tenant_&lt;id&gt;_&lt;resource&gt;.yaml} that {@code EFFECT_DENY}s the exact
 *       same tuple set under {@code scope: &lt;tenantId&gt;} and
 *       {@code SCOPE_PERMISSIONS_REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS} — so nothing falls through and
 *       the fresh tenant denies EVERYTHING until a reviewed total restriction policy is promoted.
 *   <li>Content-address the bundle (sha-256 over the canonical children) → a stable policy version.
 *   <li>Stage the children under {@code &lt;stagingRoot&gt;/&lt;tenant&gt;/&lt;version&gt;/}, alongside existing
 *       versions; the live bundle is never touched, no empty scope policy is ever written.
 *   <li>Probe: compile each staged child + the immutable base bundle with the pinned Cerbos in an
 *       ephemeral {@code docker run --rm} (reusing {@link CerbosCompileGate}) — never the running PDP.
 * </ol>
 *
 * <p>The tenant-child grammar mirrors IAM's {@code TenantClaims} / gateway's {@code TenantKeyspace}.
 */
@Component
public class CerbosPolicyBootstrapAdapter implements PolicyBootstrapAdapter {

    private static final Logger log = LoggerFactory.getLogger(CerbosPolicyBootstrapAdapter.class);

    private static final String API_VERSION = "api.cerbos.dev/v1";
    private static final String POLICY_VERSION_FIELD = "default";
    private static final String PARENTAL_CONSENT = "SCOPE_PERMISSIONS_REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS";
    private static final String DENY = "EFFECT_DENY";
    private static final Pattern CEILING_FILE = Pattern.compile("tenant_default_.*\\.ya?ml");
    // Same canonical tenant grammar as TenantClaims/TenantKeyspace — fail closed on anything else.
    private static final Pattern CANONICAL_TENANT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    private final CanonicalPolicyWriter writer;
    private final CerbosCompileGate compileGate;
    private final String baseBundleDir;
    private final String stagingRootDir;

    public CerbosPolicyBootstrapAdapter(
            CanonicalPolicyWriter writer,
            CerbosCompileGate compileGate,
            @Value("${iam.policy-studio.base-bundle-dir:infra/cerbos/policies}") String baseBundleDir,
            @Value("${iam.tenancy.policy-staging-dir:build/tenant-policy-staging}") String stagingRootDir) {
        this.writer = writer;
        this.compileGate = compileGate;
        this.baseBundleDir = baseBundleDir;
        this.stagingRootDir = stagingRootDir;
    }

    @Override
    public TenantBootstrapBundle stage(String tenantId) {
        if (tenantId == null || !CANONICAL_TENANT_ID.matcher(tenantId).matches()) {
            throw new ProvisioningException("illegal tenant id (fails canonical grammar): " + tenantId);
        }
        Path base = resolveBaseBundleDir();
        List<Ceiling> ceilings = readCeilings(base);
        if (ceilings.isEmpty()) {
            throw new ProvisioningException("no base-ceiling tenant_default_*.yaml found under " + base);
        }

        // Deterministic order (by resource) so the content hash is stable across runs.
        Map<String, String> childByResource = new TreeMap<>();
        for (Ceiling ceiling : ceilings) {
            childByResource.put(ceiling.resource(), writer.write(denyAll(tenantId, ceiling)));
        }
        String policyVersion = contentAddress(childByResource);

        Path stagingDir = resolveStagingRoot().resolve(tenantId).resolve(policyVersion);
        try {
            Files.createDirectories(stagingDir);
            for (Ceiling ceiling : ceilings) {
                Path file = stagingDir.resolve("tenant_" + tenantId + "_" + ceiling.resource() + ".yaml");
                Files.writeString(file, childByResource.get(ceiling.resource()), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new ProvisioningException("could not stage bootstrap bundle for '" + tenantId + "'", e);
        }
        log.info("Staged deny-all bootstrap for tenant '{}' — version {} ({} resource children) at {}",
                tenantId, policyVersion, childByResource.size(), stagingDir);
        return new TenantBootstrapBundle(tenantId, policyVersion, stagingDir, childByResource);
    }

    @Override
    public void probe(TenantBootstrapBundle bundle) {
        if (!isProbeAvailable()) {
            log.warn("Cerbos compile gate unavailable — bootstrap bundle {} for tenant '{}' verified by "
                            + "deterministic deny-all totality only, NOT compile-probed (seam).",
                    bundle.policyVersion(), bundle.tenantId());
            return;
        }
        Path base = resolveBaseBundleDir();
        for (Map.Entry<String, String> child : bundle.childByResource().entrySet()) {
            String candidateFile = "generated_" + child.getKey() + "_" + bundle.tenantId() + ".yaml";
            CerbosCompileGate.CompileOutcome outcome = compileGate.compile(child.getValue(), base, candidateFile);
            if (!outcome.success()) {
                throw new ProvisioningException("cerbos probe rejected bootstrap child '" + child.getKey()
                        + "' for tenant '" + bundle.tenantId() + "':\n" + outcome.output());
            }
        }
        log.info("Cerbos-probed bootstrap bundle {} for tenant '{}' ({} children compile clean)",
                bundle.policyVersion(), bundle.tenantId(), bundle.childByResource().size());
    }

    @Override
    public boolean isProbeAvailable() {
        return compileGate.isAvailable();
    }

    /**
     * H6 compensation — delete the staged (never-activated) version directory for a FAILED provision,
     * and prune the now-empty tenant staging root. Best-effort and idempotent: a missing directory is
     * a no-op. The live bundle is never under the staging root, so this can never touch it.
     */
    @Override
    public void discardStaged(TenantBootstrapBundle bundle) {
        if (bundle == null || bundle.stagingDir() == null) {
            return;
        }
        Path stagingDir = bundle.stagingDir();
        try {
            deleteRecursively(stagingDir);
            // Prune the tenant-level parent (…/staging/<tenant>) if this was its last staged version.
            Path tenantDir = stagingDir.getParent();
            if (tenantDir != null && Files.isDirectory(tenantDir)) {
                try (Stream<Path> siblings = Files.list(tenantDir)) {
                    if (siblings.findAny().isEmpty()) {
                        Files.deleteIfExists(tenantDir);
                    }
                }
            }
            log.info("H6 compensation: discarded staged bootstrap bundle {} for tenant '{}' at {}",
                    bundle.policyVersion(), bundle.tenantId(), stagingDir);
        } catch (IOException e) {
            // Best-effort: log and continue — the tenant is already absent from the directory (fail-closed).
            log.warn("H6 compensation: could not fully discard staged bundle {} for tenant '{}' at {}: {}",
                    bundle.policyVersion(), bundle.tenantId(), stagingDir, e.toString());
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    // ── deny-all construction ────────────────────────────────────────────────────────────────

    private PolicyIR denyAll(String tenantId, Ceiling ceiling) {
        List<PolicyIR.Rule> rules = new ArrayList<>();
        for (Ceiling.RuleTuple t : ceiling.rules()) {
            rules.add(new PolicyIR.Rule(t.actions(), DENY, t.roles(), List.of(), null));
        }
        return new PolicyIR(API_VERSION, POLICY_VERSION_FIELD, ceiling.resource(), tenantId,
                PARENTAL_CONSENT, List.of(), rules);
    }

    @SuppressWarnings("unchecked")
    private List<Ceiling> readCeilings(Path base) {
        List<Ceiling> ceilings = new ArrayList<>();
        try (Stream<Path> files = Files.list(base)) {
            List<Path> ceilingFiles = files
                    .filter(p -> CEILING_FILE.matcher(p.getFileName().toString()).matches())
                    .sorted()
                    .toList();
            Yaml yaml = new Yaml();
            for (Path p : ceilingFiles) {
                Map<String, Object> doc;
                try {
                    doc = yaml.load(Files.readString(p, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                Map<String, Object> rp = (Map<String, Object>) doc.get("resourcePolicy");
                if (rp == null) continue;
                String resource = String.valueOf(rp.get("resource"));
                List<Map<String, Object>> ruleMaps = (List<Map<String, Object>>) rp.get("rules");
                List<Ceiling.RuleTuple> rules = new ArrayList<>();
                if (ruleMaps != null) {
                    for (Map<String, Object> rm : ruleMaps) {
                        List<String> actions = asStrings(rm.get("actions"));
                        List<String> roles = asStrings(rm.get("roles"));
                        if (actions.isEmpty() || roles.isEmpty()) continue;
                        rules.add(new Ceiling.RuleTuple(actions, roles));
                    }
                }
                if (!rules.isEmpty()) {
                    ceilings.add(new Ceiling(resource, rules));
                }
            }
        } catch (IOException e) {
            throw new ProvisioningException("could not read base ceiling from " + base, e);
        }
        return ceilings;
    }

    private static List<String> asStrings(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object x : list) out.add(String.valueOf(x));
        return out;
    }

    private String contentAddress(Map<String, String> childByResource) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // TreeMap iteration is sorted → stable input regardless of insertion order.
            for (Map.Entry<String, String> e : new TreeMap<>(childByResource).entrySet()) {
                md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(e.getValue().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            String hex = HexFormat.of().formatHex(md.digest());
            return "pv_" + hex.substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Path resolveBaseBundleDir() {
        return resolveExisting(baseBundleDir,
                () -> new ProvisioningException("base bundle dir not found: " + baseBundleDir));
    }

    private Path resolveStagingRoot() {
        Path direct = Path.of(stagingRootDir);
        if (direct.isAbsolute()) return direct;
        // Anchor a relative staging root at the same repo root the base bundle resolves under, so
        // staging is stable no matter the working directory a test/JVM is launched from.
        Path base = resolveBaseBundleDir();
        Path repoRoot = base.getParent() != null && base.getParent().getParent() != null
                ? base.getParent().getParent().getParent() // infra/cerbos/policies → repo root
                : Path.of("").toAbsolutePath();
        return repoRoot == null ? Path.of("").toAbsolutePath().resolve(direct) : repoRoot.resolve(direct);
    }

    /** Resolve a possibly-relative path, walking up from CWD to find it when not directly present. */
    private static Path resolveExisting(String configured, java.util.function.Supplier<RuntimeException> onMissing) {
        Path direct = Path.of(configured);
        if (Files.isDirectory(direct)) return direct.toAbsolutePath().normalize();
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cursor != null; i++) {
            Path candidate = cursor.resolve(configured);
            if (Files.isDirectory(candidate)) return candidate.normalize();
            cursor = cursor.getParent();
        }
        throw onMissing.get();
    }

    /** One base resource-ceiling: the resource kind and its enumerated {@code (actions, roles)} tuples. */
    private record Ceiling(String resource, List<RuleTuple> rules) {
        record RuleTuple(List<String> actions, List<String> roles) {}
    }
}
