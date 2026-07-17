package com.openwolf.iam.policystudio.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * S1a — the missing runtime-load wire (lifecycle-spine hardening). After a candidate bundle compiles and
 * the activation CAS flips the tenant's active pointer, this loader <b>materialises the promoted bundle's
 * rendered policies — each stamped {@code policyVersion = <bundleId>} — into a directory the serving Cerbos
 * PDP watches</b>. Cerbos' disk driver ({@code watchForChanges: true}) reloads them, so the promotion the
 * control plane performed actually changes runtime enforcement instead of only flipping a pointer the PDP
 * never sees.
 *
 * <p><b>Additive, never destructive.</b> Every file is namespaced by the content-addressed {@code bundleId}
 * and every policy inside carries {@code version: <bundleId>} (the {@link StagingCandidateProbe} invariant).
 * A resource policy is keyed in Cerbos by {@code (kind, version, scope)}, so a promoted bundle's
 * {@code version=<bundleId>} policies coexist with the base {@code version=default} policies — the default
 * tenant keeps evaluating {@code default} unchanged, and a promoted tenant (whose
 * {@code activePolicyVersion} the gateway now sends) evaluates its own bundle.
 *
 * <p><b>Topology.</b> The watched directory is a writable volume shared by iam-service (writes here) and the
 * Cerbos container (reads, under its watched {@code /conf/policies}). The path is configured via
 * {@code iam.policy-studio.runtime-policies-dir} (env {@code IAM_POLICY_STUDIO_RUNTIME_POLICIES_DIR}) — never
 * a hardcoded path. When it is unset (a checkout with no runtime volume, e.g. most unit tests) the load is a
 * logged no-op so the deterministic promotion machinery still runs; a configured-but-unwritable dir
 * fail-closes the promotion (the CAS is rolled back by the surrounding transaction).
 */
@Component
public class PromotedBundleLoader {

    private static final Logger log = LoggerFactory.getLogger(PromotedBundleLoader.class);

    private final String runtimePoliciesDir;

    public PromotedBundleLoader(
            @Value("${iam.policy-studio.runtime-policies-dir:}") String runtimePoliciesDir) {
        this.runtimePoliciesDir = runtimePoliciesDir == null ? "" : runtimePoliciesDir.trim();
    }

    /** True iff a runtime-policies directory is configured — otherwise {@link #load} is a no-op. */
    public boolean isConfigured() {
        return !runtimePoliciesDir.isBlank();
    }

    /**
     * Write the promoted bundle's rendered (bundleId-stamped) policies into the Cerbos-watched runtime
     * directory. Returns the files written (empty when no dir is configured).
     *
     * @throws PromotedBundleLoadException if the configured directory cannot be written (fail-closed)
     */
    public List<Path> load(PolicyBundle bundle) {
        if (!isConfigured()) {
            log.info("runtime-policies-dir not configured — skipping runtime load of promoted bundle '{}'. "
                    + "Set iam.policy-studio.runtime-policies-dir to the Cerbos-watched volume to enforce it.",
                    bundle.bundleId());
            return List.of();
        }
        try {
            Path dir = Path.of(runtimePoliciesDir);
            Files.createDirectories(dir);
            List<Path> written = new ArrayList<>();
            for (BundleFile file : bundle.renderedFiles()) {
                // renderedFiles() stamps the concrete bundleId into the version position; the
                // StagingCandidateProbe already verified every rendered file carries it and no sentinel
                // survives, so what lands in the watched dir is exactly what cerbos compile validated.
                Path target = dir.resolve(stagedFileName(bundle.bundleId(), file.path()));
                Files.writeString(target, file.yaml(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                written.add(target);
            }
            log.info("Loaded promoted bundle '{}' into runtime policies dir '{}' ({} file(s)) — "
                    + "Cerbos watchForChanges will reload; version={} now enforceable, base default unchanged",
                    bundle.bundleId(), dir.toAbsolutePath(), written.size(), bundle.bundleId());
            return written;
        } catch (IOException e) {
            throw new PromotedBundleLoadException(bundle.bundleId(), runtimePoliciesDir, e);
        }
    }

    /** A filesystem-safe, bundle-namespaced file name so promoted versions coexist and re-promotion is idempotent. */
    private static String stagedFileName(String bundleId, String path) {
        String safeBundle = bundleId.replaceAll("[^A-Za-z0-9_.-]", "_");
        String safePath = path.replaceAll("[^A-Za-z0-9_.-]", "_");
        String name = safeBundle + "__" + safePath;
        if (!name.endsWith(".yaml") && !name.endsWith(".yml")) {
            name = name + ".yaml";
        }
        return name;
    }
}
