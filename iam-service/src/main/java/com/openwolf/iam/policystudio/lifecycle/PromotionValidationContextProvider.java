package com.openwolf.iam.policystudio.lifecycle;

/**
 * Supplies the {@link PromotionValidationContext} (manifest vocabulary + author scope + immutable base
 * ceiling) a candidate bundle must be re-validated against at promotion time (Axiom H2). It is a trusted
 * server-side source, keyed off the candidate — promotion never accepts these facts from the request.
 *
 * <p>Behind a port so the deterministic harness can drive the re-validation with fixture vocabulary /
 * ceilings, while production derives them from the effective per-tenant manifest.
 */
public interface PromotionValidationContextProvider {

    /**
     * @return the grounding context to re-validate {@code candidate} against.
     * @throws IllegalStateException if no trusted context can be produced — promotion must FAIL CLOSED
     *                               rather than validate a candidate against an empty/absent ceiling.
     */
    PromotionValidationContext contextFor(PolicyBundle candidate);
}
