package com.openwolf.iam.policystudio.api;

import com.openwolf.iam.policystudio.BundleSnapshot;
import com.openwolf.iam.policystudio.CanonicalPolicyWriter;
import com.openwolf.iam.policystudio.ConsequenceDiffService;
import com.openwolf.iam.policystudio.ConsequenceProseModelClient;
import com.openwolf.iam.policystudio.ConsequenceReview;
import com.openwolf.iam.policystudio.PolicyIR;
import com.openwolf.iam.policystudio.PolicyYamlParser;
import com.openwolf.iam.policystudio.ProductionPdpDecisionSource;
import com.openwolf.iam.policystudio.StudioGroundingProvider;
import com.openwolf.iam.policystudio.StudioGroundingSnapshot;
import com.openwolf.iam.policystudio.lifecycle.BundleCanonicalizer;
import com.openwolf.iam.policystudio.lifecycle.BundleFile;
import com.openwolf.iam.policystudio.lifecycle.BundleTestMetadata;
import com.openwolf.iam.policystudio.lifecycle.PolicyBundle;
import com.openwolf.iam.policystudio.lifecycle.StudioBaselineActivationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;

@RestController
@RequestMapping("/admin/studio")
@PreAuthorize("hasAnyRole('policy_author','policy_approver','platform_admin')")
public class StudioGroundingController {

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

    public StudioGroundingController(StudioGroundingProvider grounding,
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

    public record AssemblePayload(String resourceKind, String canonicalYaml) {}

    public record CandidateBundlePayload(String resourceKind, String canonicalYaml, String fixtureSetHash) {}

    @GetMapping("/vocabulary/{resourceKind}")
    public ResponseEntity<StudioGroundingSnapshot> vocabulary(@PathVariable String resourceKind, Authentication auth) {
        return ResponseEntity.ok(grounding.snapshot(StudioPrincipal.tenant(auth), resourceKind));
    }

    @PostMapping("/reviews/assembled")
    public ResponseEntity<ConsequenceReview> assembledReview(@RequestBody AssemblePayload payload, Authentication auth) {
        if (payload == null || payload.canonicalYaml() == null || payload.canonicalYaml().isBlank()) {
            throw new IllegalArgumentException("canonicalYaml is required");
        }
        String tenant = StudioPrincipal.tenant(auth);
        String author = StudioPrincipal.actor(auth);
        String kind = payload.resourceKind() == null || payload.resourceKind().isBlank() ? "agent" : payload.resourceKind();
        StudioGroundingSnapshot grounded = grounding.snapshot(tenant, kind);
        baselineActivation.ensureExplicitBaseline(tenant, grounded.current().bundleId());
        PolicyBundle bundle = materializeCandidate(tenant, grounded, payload.canonicalYaml(), grounded.matrix().fixtureSetHash());
        BundleSnapshot candidate = new BundleSnapshot(
                bundle.bundleId(),
                parser.parse(payload.canonicalYaml()),
                grounded.baseCeiling(),
                bundle.canonicalContent());
        ConsequenceReview review = diff.computeReview(
                tenant, grounded.vocabulary(), grounded.current(), candidate, grounded.matrix(), pdpSource);
        ConsequenceProseModelClient client = prose.getIfAvailable();
        if (client != null) {
            review = diff.attachProse(review, client);
        }
        store.putReview(tenant, author, review);
        return ResponseEntity.ok(review);
    }

    @PostMapping("/bundles/candidates")
    public ResponseEntity<PolicyBundle> candidateBundle(@RequestBody CandidateBundlePayload payload, Authentication auth) {
        if (payload == null || payload.canonicalYaml() == null || payload.canonicalYaml().isBlank()) {
            throw new IllegalArgumentException("canonicalYaml is required");
        }
        String tenant = StudioPrincipal.tenant(auth);
        String kind = payload.resourceKind() == null || payload.resourceKind().isBlank() ? "agent" : payload.resourceKind();
        StudioGroundingSnapshot grounded = grounding.snapshot(tenant, kind);
        PolicyBundle bundle = materializeCandidate(tenant, grounded, payload.canonicalYaml(),
                payload.fixtureSetHash() == null || payload.fixtureSetHash().isBlank()
                        ? grounded.matrix().fixtureSetHash()
                        : payload.fixtureSetHash());
        return ResponseEntity.ok(bundle);
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
