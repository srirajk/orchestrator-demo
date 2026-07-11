package ai.conduit.gateway.synthesis.input;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The full, span-aware provenance model for a request's extracted entity references: a flat list
 * of {@link Mention} records keyed by manifest entity {@code key}. This is the source of truth the
 * grounding / masking stages (later pieces) consume; the flat scalar {@code Map<String,String>}
 * that older callers use is a lossy compatibility VIEW derived from it (see {@link #compatScalar}).
 *
 * <p>Generic and manifest-keyed — no per-entity-type field, no domain literal (World-B clean).
 *
 * @param mentions every extracted reference, in the order it was recorded (the deterministically
 *                 grounded focal reference for each key is recorded first, so it wins compat ties).
 */
public record MentionSet(List<Mention> mentions) {

    public MentionSet {
        mentions = mentions == null ? List.of() : List.copyOf(mentions);
    }

    public static MentionSet empty() {
        return new MentionSet(List.of());
    }

    public boolean isEmpty() {
        return mentions.isEmpty();
    }

    /** All mentions of one entity key, in recorded order. */
    public List<Mention> forKey(String entityKey) {
        List<Mention> out = new ArrayList<>();
        for (Mention m : mentions) {
            if (Objects.equals(m.entityKey(), entityKey)) out.add(m);
        }
        return out;
    }

    /** The distinct entity keys carried, in first-seen order. */
    public Set<String> entityKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Mention m : mentions) {
            if (m.entityKey() != null) keys.add(m.entityKey());
        }
        return keys;
    }

    /** Mentions the aligner could not span — surfaced so a later stage can mark masking incomplete. */
    public List<Mention> alignmentMisses() {
        List<Mention> out = new ArrayList<>();
        for (Mention m : mentions) {
            if (!m.aligned()) out.add(m);
        }
        return out;
    }

    /**
     * The compatibility scalar for an entity key (routing spec V2.1 resolution #3): the latest-turn
     * EXPLICIT mention's verbatim text, else the carried ANAPHORA mention's text, else {@code null}.
     * Mirrors {@code IntentClassifier.deriveFocalReference}: because the focal reference is recorded
     * first, it wins any same-turn tie, so this reproduces the legacy focal scalar for the focal key.
     */
    public String compatScalar(String entityKey) {
        Mention best = null;
        for (Mention m : forKey(entityKey)) {
            if (best == null || isBetterScalar(m, best)) best = m;
        }
        return best == null ? null : best.verbatimText();
    }

    /** The compat scalar for every carried key, in first-seen order (skips null-valued keys). */
    public Map<String, String> compatScalars() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : entityKeys()) {
            String v = compatScalar(key);
            if (v != null) out.put(key, v);
        }
        return out;
    }

    /**
     * Ordering for the compat scalar: EXPLICIT outranks ANAPHORA; within a rank a later turn (higher
     * message index) wins. Equal rank AND equal turn keeps the already-selected mention, so the
     * first-recorded focal reference wins a same-turn tie (e.g. "compare A and B" keeps the focal A).
     */
    private static boolean isBetterScalar(Mention candidate, Mention current) {
        int rc = rank(candidate);
        int rk = rank(current);
        if (rc != rk) return rc > rk;
        return candidate.messageIndex() > current.messageIndex();
    }

    private static int rank(Mention m) {
        return m.source() == MentionSource.EXPLICIT ? 1 : 0;
    }
}
