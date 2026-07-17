package com.openwolf.iam.policystudio;

import com.openwolf.iam.policystudio.api.StudioSessionStore;
import com.openwolf.iam.policystudio.lifecycle.BundleCanonicalizer;
import com.openwolf.iam.policystudio.lifecycle.BundleFile;
import com.openwolf.iam.policystudio.lifecycle.BundleTestMetadata;
import com.openwolf.iam.policystudio.lifecycle.PolicyBundle;
import com.openwolf.iam.policystudio.lifecycle.StudioBaselineActivationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;

/**
 * The single server-side path that turns an <em>authored candidate policy</em> (canonical YAML) into a
 * consequence review, deriving <b>all grounding — vocabulary, base ceiling, current snapshot, and the
 * sampled fixture matrix — from the tenant's manifest</b> via {@link StudioGroundingProvider}. The only
 * caller-supplied inputs are the trusted principal's {@code tenant}/{@code author} (from the JWT, never a
 * body field) and the authored {@code canonicalYaml} + {@code resourceKind} selector.
 *
 * <p>This is the S2 trust boundary: no caller may hand in the two bundle snapshots, the vocabulary, or the
 * fixture matrix — those are the grounding, and grounding is server-derived here. Both the C4 review route
 * ({@code StudioReviewController}) and the grounded assemble route ({@code StudioGroundingController}) go
 * through this one collaborator, so there is exactly one derivation and no route can bypass it.
 */
@Service
public class GroundedStudioReviewService {

    private final StudioGroundingProvider grounding;
    private final PolicyYamlParser parser;
    private final CanonicalPolicyWriter writer;
    private final ConsequenceDiffService diff;
    private final ProductionPdpDecisionSource pdpSource;
    private final ObjectProvider<ConsequenceProseModelClient> prose;
    private final BundleCanonicalizer canonicalizer;
    private final StudioSessionStore store;
    private final StudioBaselineActivationService baselineActivation;
    private final Path baseBundleDir;

    public GroundedStudioReviewService(StudioGroundingProvider grounding,
                                       PolicyYamlParser parser,
                                       CanonicalPolicyWriter writer,
                                       ConsequenceDiffService diff,
                                       ProductionPdpDecisionSource pdpSource,
                                       ObjectProvider<ConsequenceProseModelClient> prose,
                                       BundleCanonicalizer canonicalizer,
                                       StudioSessionStore store,
                                       StudioBaselineActivationService baselineActivation,
                                       @Value("${iam.policy-studio.base-bundle-dir:infra/cerbos/policies}") String baseBundleDir) {
        this.grounding = grounding;
        this.parser = parser;
        this.writer = writer;
        this.diff = diff;
        this.pdpSource = pdpSource;
        this.prose = prose;
        this.canonicalizer = canonicalizer;
        this.store = store;
        this.baselineActivation = baselineActivation;
        Path configured = Path.of(baseBundleDir);
        this.baseBundleDir = Files.isDirectory(configured) ? configured : Path.of("..").resolve(baseBundleDir).normalize();
    }

    private static String resolveKind(String resourceKind) {
        return resourceKind == null || resourceKind.isBlank() ? "agent" : resourceKind;
    }

    /**
     * Compute a consequence review for an authored candidate, deriving all grounding server-side and
     * caching the review against the VERIFIED author + tenant. The {@code tenant}/{@code author} are the
     * principal's trusted identity; {@code canonicalYaml} is the authored candidate. Nothing else — no
     * snapshots, vocabulary, or matrix — crosses the trust boundary.
     */
    public ConsequenceReview assembleAndReview(String tenant, String author, String resourceKind, String canonicalYaml) {
        String kind = resolveKind(resourceKind);
        StudioGroundingSnapshot grounded = grounding.snapshot(tenant, kind);
        baselineActivation.ensureExplicitBaseline(tenant, grounded.current().bundleId());
        PolicyBundle bundle = materializeCandidate(tenant, grounded, canonicalYaml, grounded.matrix().fixtureSetHash());
        BundleSnapshot candidate = new BundleSnapshot(
                bundle.bundleId(),
                parser.parse(canonicalYaml),
                grounded.baseCeiling(),
                bundle.canonicalContent());
        ConsequenceReview review = diff.computeReview(
                tenant, grounded.vocabulary(), grounded.current(), candidate, grounded.matrix(), pdpSource);
        ConsequenceProseModelClient client = prose.getIfAvailable();
        if (client != null) {
            review = diff.attachProse(review, client);
        }
        store.putReview(tenant, author, review);
        return review;
    }

    /**
     * Materialize the self-contained candidate bundle for an authored policy, deriving grounding
     * (base root, manifest refs, matrix) server-side. {@code fixtureSetHash} is bundle test metadata,
     * not grounding, so a caller override is honoured where the route allows it.
     */
    public PolicyBundle assembleCandidateBundle(String tenant, String resourceKind, String canonicalYaml, String fixtureSetHash) {
        String kind = resolveKind(resourceKind);
        StudioGroundingSnapshot grounded = grounding.snapshot(tenant, kind);
        String hash = fixtureSetHash == null || fixtureSetHash.isBlank() ? grounded.matrix().fixtureSetHash() : fixtureSetHash;
        return materializeCandidate(tenant, grounded, canonicalYaml, hash);
    }

    private PolicyBundle materializeCandidate(String tenant, StudioGroundingSnapshot grounded,
                                              String canonicalYaml, String fixtureSetHash) {
        PolicyIR parsed = parser.parse(canonicalYaml);
        PolicyIR sentinel = new PolicyIR(
                parsed.apiVersion(),
                BundleCanonicalizer.BUNDLE_VERSION_SENTINEL,
                parsed.resource(),
                tenant,
                parsed.scopePermissions(),
                parsed.importDerivedRoles(),
                parsed.rules());
        String yaml = writer.write(sentinel);
        BundleTestMetadata metadata = new BundleTestMetadata(
                fixtureSetHash,
                grounded.matrix().cells().size(),
                "manifest-grounded-sampled-matrix",
                "cerbos-0.53.0");
        return PolicyBundle.materialize(
                tenant,
                List.of(
                        new BundleFile("policies/" + parsed.resource() + "_resource.yaml",
                                versionStampedBaseRoot(parsed.resource())),
                        new BundleFile("policies/" + parsed.resource() + "@" + tenant + ".yaml", yaml)),
                grounded.manifestRefs(),
                metadata,
                canonicalizer);
    }

    private String versionStampedBaseRoot(String resourceKind) {
        Path path = baseBundleDir.resolve(resourceKind + "_resource.yaml");
        try {
            String yaml = Files.readString(path);
            String stamped = yaml.replaceFirst(
                    "(?m)^(\\s*version:\\s*)(\"default\"|'default'|default)\\s*$",
                    "$1" + Matcher.quoteReplacement("\"" + BundleCanonicalizer.BUNDLE_VERSION_SENTINEL + "\""));
            if (stamped.equals(yaml)) {
                throw new IllegalStateException("base policy '" + path + "' does not declare version default");
            }
            return stamped;
        } catch (IOException e) {
            throw new IllegalStateException("failed to read base policy '" + path + "'", e);
        }
    }
}
