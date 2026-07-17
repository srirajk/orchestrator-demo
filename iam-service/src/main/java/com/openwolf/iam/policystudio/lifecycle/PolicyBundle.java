package com.openwolf.iam.policystudio.lifecycle;

import java.util.ArrayList;
import java.util.List;

/**
 * An IMMUTABLE, content-addressed full policy bundle for one tenant (Axiom Story C5). A candidate is a
 * WHOLE snapshot — base policies + all required scope-chain policies + imported derived-roles / variables
 * + the manifests used for validation + the test metadata that certified it — never a partial, mutable
 * file. Its {@code bundleId} ({@code b_<sha256>}) is derived from the canonical full-bundle bytes by
 * {@link BundleCanonicalizer}, so two bundles with identical content have identical ids and any change
 * yields a different id.
 *
 * <p><b>Every policy in the snapshot uses {@code bundleId} as its {@code policyVersion}.</b> The files
 * are stored with the reproducible {@link BundleCanonicalizer#BUNDLE_VERSION_SENTINEL} in that position;
 * {@link #renderedFiles()} stamps the concrete id back in for staging / {@code cerbos compile} / probing.
 *
 * <p>The record is immutable and its id is content-addressed, so a stored bundle cannot be mutated in
 * place: {@link #verifyIntegrity(BundleCanonicalizer)} recomputes the id from the bytes and rejects any
 * tamper (C5.4).
 *
 * @param bundleId        the content-addressed identity ({@code b_<sha256>})
 * @param tenantId        the tenant this bundle is for
 * @param files           base + scope-chain + derived-roles / variables + manifest files (sentinel form)
 * @param manifestRefs    content refs of the manifests used for validation
 * @param testMetadata    the fixture matrix / oracle / PDP that certified the bundle
 * @param canonicalContent the exact canonical bytes the id was derived from (kept for evidence + re-hash)
 */
public record PolicyBundle(
        String bundleId,
        String tenantId,
        List<BundleFile> files,
        List<String> manifestRefs,
        BundleTestMetadata testMetadata,
        String canonicalContent) {

    public PolicyBundle {
        files = List.copyOf(files);
        manifestRefs = List.copyOf(manifestRefs);
    }

    /**
     * Materialise an immutable bundle from its constituent files (authored with the version sentinel),
     * manifest refs and test metadata. The id is derived from the canonical bytes.
     */
    public static PolicyBundle materialize(String tenantId, List<BundleFile> files, List<String> manifestRefs,
                                           BundleTestMetadata testMetadata, BundleCanonicalizer canon) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must be set");
        }
        String content = canon.canonicalContent(tenantId, files, manifestRefs, testMetadata, null);
        String id = canon.bundleId(content);
        return new PolicyBundle(id, tenantId, files, manifestRefs, testMetadata, content);
    }

    /**
     * The files with the version sentinel replaced by the concrete {@code bundleId} — the deployable form
     * staged alongside the live bundle and fed to {@code cerbos compile}. Never mutates this record.
     */
    public List<BundleFile> renderedFiles() {
        List<BundleFile> out = new ArrayList<>(files.size());
        for (BundleFile f : files) {
            out.add(new BundleFile(f.path(),
                    f.yaml().replace(BundleCanonicalizer.BUNDLE_VERSION_SENTINEL, bundleId)));
        }
        return out;
    }

    /**
     * Recompute the id from the canonical bytes and reject any in-place mutation (C5.4). A stored bundle
     * whose files, manifest refs, or test metadata were altered while keeping the same id fails here.
     *
     * @throws BundleTamperException if the recomputed id does not match {@code bundleId}
     */
    public void verifyIntegrity(BundleCanonicalizer canon) {
        String recomputedContent = canon.canonicalContent(tenantId, files, manifestRefs, testMetadata, bundleId);
        String recomputedId = canon.bundleId(recomputedContent);
        if (!recomputedId.equals(bundleId)) {
            throw new BundleTamperException(
                    "policy bundle '" + bundleId + "' failed integrity: recomputed id is '" + recomputedId
                            + "' — a promoted bundle is immutable and cannot be edited in place");
        }
    }
}
