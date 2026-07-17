package com.openwolf.iam.policystudio.lifecycle;

import org.springframework.stereotype.Component;

/**
 * The production {@link PromotionValidationContextProvider} (Axiom H2). It FAILS CLOSED.
 *
 * <p><b>Honest state of the world:</b> the promotion candidate is a {@link PolicyBundle} — rendered
 * Cerbos YAML + certifying test metadata — and does NOT carry a typed {@link
 * com.openwolf.iam.policystudio.ManifestVocabulary} or {@link com.openwolf.iam.policystudio.BaseCeiling}.
 * There is no per-tenant {@code ManifestVocabularyProvider}/{@code BaseCeilingProvider} bean yet that
 * derives them from the effective manifest (the C2 authoring path still takes them from the request body;
 * see {@code StudioAuthoringController}'s documented grounding gap). The base ceiling also cannot be
 * reconstructed from the bundle's YAML alone: its derived-role modules are not the single
 * {@code resourcePolicy} document the studio parser accepts.
 *
 * <p>Rather than fabricate a vocabulary/ceiling (which would let promote() validate a candidate against an
 * empty or invented ceiling — worse than not validating), this provider throws. That makes promotion
 * FAIL CLOSED on any host until the manifest-derived context source is wired — the correct security
 * posture for the H2 hole, and consistent with "no canned/fallback data". The deterministic gate injects a
 * fixture provider to prove the re-validation itself is unconditional and rejects wildcard / sibling-scope
 * / base-ceiling-omitting candidates.
 *
 * <p>Wiring the real per-tenant provider (manifest → vocabulary + base ceiling) is the follow-up that turns
 * production promotion back on; until then the single-tenant demo does not drive promotion, so it is
 * unaffected.
 */
@Component
public class ManifestPromotionValidationContextProvider implements PromotionValidationContextProvider {

    @Override
    public PromotionValidationContext contextFor(PolicyBundle candidate) {
        String id = candidate == null ? "<null>" : candidate.bundleId();
        throw new IllegalStateException(
                "no per-tenant manifest vocabulary/base-ceiling source is wired, so the candidate '" + id
                        + "' cannot be re-validated at promotion — refusing to promote (fail closed). Wire a "
                        + "PromotionValidationContextProvider that derives the vocabulary + immutable base ceiling "
                        + "from the effective manifest before enabling promotion in production.");
    }
}
