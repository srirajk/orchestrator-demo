package com.openwolf.iam.policystudio.api;

import com.openwolf.iam.policystudio.BaseCeiling;
import com.openwolf.iam.policystudio.ManifestVocabulary;
import com.openwolf.iam.policystudio.PolicyAuthoringRequest;
import com.openwolf.iam.policystudio.PolicyStudioGenerationService;
import com.openwolf.iam.policystudio.StudioGenerationResult;
import com.openwolf.iam.policystudio.TenantScope;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The C2 authoring surface — turn a natural-language intent into a validated, canonical Cerbos policy.
 * Thin over {@link PolicyStudioGenerationService}: the model proposes, the deterministic gate decides,
 * and only the IR-derived canonical YAML is ever returned (never the model's raw text). A bad proposal
 * is a normal {@code REJECTED} outcome with violations, not an error.
 *
 * <p><b>Tenant scope (author scope) is derived from the principal's {@code tenant_id} claim</b>, never a
 * body field: the controller builds {@code authorScope = TenantScope.of(<claim>)}, so a drafter cannot
 * author a policy targeting another tenant's subtree. Requires the {@code policy_author} role.
 *
 * <p><b>Grounding note (documented gap):</b> the closed manifest {@code vocabulary} and the immutable
 * {@code baseCeiling} are supplied in the request body. There is currently no {@code
 * ManifestVocabularyProvider}/{@code BaseCeilingProvider} bean that derives them per-tenant from the
 * effective manifest (they are test fixtures today). Sourcing them server-side is the intended
 * follow-up; until then the caller supplies them (World-B config, not domain code).
 */
@RestController
@RequestMapping("/admin/studio")
@PreAuthorize("hasRole('policy_author')")
public class StudioAuthoringController {

    private final PolicyStudioGenerationService generation;

    public StudioAuthoringController(PolicyStudioGenerationService generation) {
        this.generation = generation;
    }

    /** The draft request. {@code authorScope} is intentionally absent — it is derived from the principal. */
    public record DraftPayload(String intent, ManifestVocabulary vocabulary,
                               boolean subscopesEnabled, BaseCeiling baseCeiling) {}

    /** The deterministic-gate outcome the SPA renders. */
    public record Validation(boolean ok, List<String> violations, String stage) {}

    /** The draft response — a fresh id, the read-only canonical YAML, and the validation verdict. */
    public record DraftResponse(String draftId, String tenantId, String authorId,
                                boolean accepted, String canonicalYaml, Validation validation) {}

    /** {@code POST /admin/studio/drafts} — generate + validate a candidate policy from NL intent. */
    @PostMapping("/drafts")
    public ResponseEntity<DraftResponse> draft(@RequestBody DraftPayload payload, Authentication auth) {
        if (payload == null || payload.intent() == null || payload.vocabulary() == null
                || payload.baseCeiling() == null) {
            throw new IllegalArgumentException("intent, vocabulary and baseCeiling are required");
        }
        String author = StudioPrincipal.actor(auth);
        String tenant = StudioPrincipal.tenant(auth);

        // Author scope is the principal's own tenant — never a body field.
        TenantScope authorScope = TenantScope.of(tenant);
        PolicyAuthoringRequest request = new PolicyAuthoringRequest(
                payload.intent(), payload.vocabulary(), authorScope,
                payload.subscopesEnabled(), payload.baseCeiling());

        StudioGenerationResult result = generation.generate(request);
        DraftResponse response = new DraftResponse(
                "draft-" + UUID.randomUUID(),
                tenant,
                author,
                result.accepted(),
                result.canonicalYaml(),
                new Validation(result.accepted(), result.violations(), result.stage().name()));
        return ResponseEntity.ok(response);
    }
}
