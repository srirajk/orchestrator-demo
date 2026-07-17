package com.openwolf.iam.policystudio;

import com.openwolf.iam.policystudio.api.StudioSessionStore;
import com.openwolf.iam.policystudio.lifecycle.BundleCanonicalizer;
import com.openwolf.iam.policystudio.lifecycle.BundleFile;
import com.openwolf.iam.policystudio.lifecycle.BundleTestMetadata;
import com.openwolf.iam.policystudio.lifecycle.PolicyBundle;
import com.openwolf.iam.policystudio.lifecycle.SelfContainedBundleAssembler;
import com.openwolf.iam.policystudio.lifecycle.StudioBaselineActivationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
    private final SelfContainedBundleAssembler assembler;

    public GroundedStudioReviewService(StudioGroundingProvider grounding,
                                       PolicyYamlParser parser,
                                       CanonicalPolicyWriter writer,
                                       ConsequenceDiffService diff,
                                       ProductionPdpDecisionSource pdpSource,
                                       ObjectProvider<ConsequenceProseModelClient> prose,
                                       BundleCanonicalizer canonicalizer,
                                       StudioSessionStore store,
                                       StudioBaselineActivationService baselineActivation,
                                       SelfContainedBundleAssembler assembler) {
        this.grounding = grounding;
        this.parser = parser;
        this.writer = writer;
        this.diff = diff;
        this.pdpSource = pdpSource;
        this.prose = prose;
        this.canonicalizer = canonicalizer;
        this.store = store;
        this.baselineActivation = baselineActivation;
        this.assembler = assembler;
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
        // S3 (Bug B): capture the WHOLE self-contained set — base ceiling + every scope-chain policy for
        // the kind + imported derived-roles / variables — plus the authored tenant child, so the bundleId
        // hashes a complete policy universe and the candidate compiles from its own captured contents
        // (never the mutable external base dir). The manifest refs used for validation are captured too.
        List<BundleFile> files = new ArrayList<>(assembler.captureScopeChain(parsed.resource()));
        files.add(new BundleFile("policies/" + parsed.resource() + "@" + tenant + ".yaml", yaml));
        return PolicyBundle.materialize(
                tenant,
                files,
                grounded.manifestRefs(),
                metadata,
                canonicalizer);
    }
}
