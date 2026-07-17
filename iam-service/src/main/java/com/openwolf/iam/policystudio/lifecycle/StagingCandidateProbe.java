package com.openwolf.iam.policystudio.lifecycle;

import com.openwolf.iam.policystudio.CerbosCompileGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The default {@link CandidateProbe} (Axiom Story C5). It enforces the deterministic version-stamping
 * invariant on every rendered policy — every policy in the snapshot must carry the candidate
 * {@code bundleId} as its {@code policyVersion} and leave no unresolved
 * {@link BundleCanonicalizer#BUNDLE_VERSION_SENTINEL} — and, when the pinned Cerbos is available, stages
 * each rendered file alongside the immutable base bundle and {@code cerbos compile}s it. A compile
 * failure aborts. On a Cerbos-less box the structural compile is skipped (the deterministic invariants
 * still gate), matching how the C4 real-PDP evidence run degrades.
 */
@Component
public class StagingCandidateProbe implements CandidateProbe {

    private static final Logger log = LoggerFactory.getLogger(StagingCandidateProbe.class);

    private final CerbosCompileGate compileGate;
    private final String baseBundleDir;

    public StagingCandidateProbe(
            CerbosCompileGate compileGate,
            @Value("${iam.policy-studio.base-bundle-dir:infra/cerbos/policies}") String baseBundleDir) {
        this.compileGate = compileGate;
        this.baseBundleDir = baseBundleDir;
    }

    @Override
    public void verify(PolicyBundle candidate) {
        // (1) Deterministic invariant: every policy stamps the candidate id and no sentinel survives.
        for (BundleFile rendered : candidate.renderedFiles()) {
            if (rendered.yaml().contains(BundleCanonicalizer.BUNDLE_VERSION_SENTINEL)) {
                throw new IllegalStateException("candidate '" + candidate.bundleId() + "' file '"
                        + rendered.path() + "' still carries the version sentinel after rendering");
            }
            if (!rendered.yaml().contains(candidate.bundleId())) {
                throw new IllegalStateException("candidate '" + candidate.bundleId() + "' file '"
                        + rendered.path() + "' does not stamp the bundle id as its policyVersion");
            }
        }

        // (2) Structural: stage each rendered file alongside the live base bundle and cerbos compile it.
        Path base = Path.of(baseBundleDir);
        if (!compileGate.isAvailable() || !Files.isDirectory(base)) {
            log.debug("cerbos compile probe skipped for '{}' (no pinned Cerbos / base bundle) — deterministic "
                    + "version-stamping invariant still gated", candidate.bundleId());
            return;
        }
        for (BundleFile rendered : candidate.renderedFiles()) {
            String fileName = "staged_" + rendered.path().replace('/', '_').replace('@', '_');
            if (!fileName.endsWith(".yaml") && !fileName.endsWith(".yml")) {
                fileName = fileName + ".yaml";
            }
            CerbosCompileGate.CompileOutcome outcome = compileGate.compile(rendered.yaml(), base, fileName);
            if (!outcome.success()) {
                throw new IllegalStateException("candidate '" + candidate.bundleId() + "' file '"
                        + rendered.path() + "' failed cerbos compile: " + outcome.output());
            }
        }
        log.debug("candidate '{}' staged + compiled + version-probed OK", candidate.bundleId());
    }
}
