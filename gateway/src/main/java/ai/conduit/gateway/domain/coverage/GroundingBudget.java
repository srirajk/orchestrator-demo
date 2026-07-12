package ai.conduit.gateway.domain.coverage;

/**
 * Config-driven budgets for multi-reference grounding (routing spec V2.1 / Piece 2). Grounding each
 * mention across all its manifest interpretations is an N×M fan-out of coverage RESOLVE+CHECK calls
 * that each block up to 5s — a naive pass is a latency bomb (this gateway has virtual-thread
 * carrier-pinning history). These bounds cap the fan-out and give it a deadline; every field is
 * sourced from {@code conduit.grounding.*} config (CLAUDE.md §5 — no hardcoded operational constant).
 *
 * @param maxMentions                  most mentions ground per request (excess dropped in recorded
 *                                     order, deterministically).
 * @param maxInterpretationsPerMention most manifest interpretations ground per mention.
 * @param concurrency                  max in-flight coverage RESOLVE+CHECK task-chains (bounded via a
 *                                     semaphore over a virtual-thread executor).
 * @param stageDeadlineMillis          wall-clock budget for the whole grounding stage; unfinished
 *                                     interpretations are aggregated as {@code UNAVAILABLE}
 *                                     (fail-closed), deterministically.
 * @param residualMaxResolves          Compare-CLARIFY Tier-B cap: most residual (extractor-dropped)
 *                                     candidate phrases the detector will principal-agnostically RESOLVE
 *                                     to DECIDE a clarify. Bounds the extra fan-out on affected shapes
 *                                     only; innocent single-entity queries filter to zero candidates.
 */
public record GroundingBudget(
        int maxMentions,
        int maxInterpretationsPerMention,
        int concurrency,
        long stageDeadlineMillis,
        int residualMaxResolves) {

    /** Safe defaults for hand-built (test) construction, mirroring the shipped config values. */
    public static GroundingBudget defaults() {
        return new GroundingBudget(8, 4, 8, 8000, 3);
    }
}
