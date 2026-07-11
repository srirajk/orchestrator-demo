package ai.conduit.gateway.synthesis.input;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic reference aligner: locates a verbatim reference inside the ORIGINAL text of a
 * message and returns its {@code [start,end)} span over that original text. The span is computed
 * by the gateway, never taken from the LLM (routing spec V2.1 resolution #2).
 *
 * <p>Alignment is tolerant of the ways a verbatim copy drifts from the surrounding source text:
 * <ul>
 *   <li>case drift ("calderon" ↔ "Calderon"),</li>
 *   <li>surrounding / internal punctuation and whitespace runs,</li>
 *   <li>possessives ("Calderon's" aligns to the name "Calderon"),</li>
 *   <li>Unicode diacritics ("Jose" ↔ "José").</li>
 * </ul>
 * It does this by normalizing the message while keeping a normalized-index → original-index map, so
 * the returned span always indexes the ORIGINAL characters. Matches are anchored on normalized
 * token boundaries, so a reference never binds to a partial token (e.g. an id prefix inside a
 * longer id). A miss returns {@code null} — the caller records it and grants no span.
 *
 * <p>Pure text arithmetic — no domain knowledge (World-B clean).
 */
public final class MentionAligner {

    private MentionAligner() {}

    /** Combining marks left over after NFD decomposition (the diacritics we fold away). */
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    /** Normalized haystack plus, per normalized char, the ORIGINAL start/end offsets it came from. */
    private record Normalized(String text, int[] origStart, int[] origEnd) {}

    /**
     * The span of the first occurrence of {@code verbatim} inside {@code original}, or {@code null}
     * on a miss.
     */
    public static MentionSpan align(String original, String verbatim) {
        return align(original, verbatim, 0);
    }

    /**
     * The span of the {@code occurrence}-th (0-based) occurrence of {@code verbatim} inside
     * {@code original}, mapped back to ORIGINAL offsets, or {@code null} when there is no such
     * occurrence. Successive occurrences let a repeated reference align to distinct spans.
     */
    public static MentionSpan align(String original, String verbatim, int occurrence) {
        if (original == null || verbatim == null || occurrence < 0) return null;
        Normalized h = normalize(original);
        String needle = normalizeNeedle(verbatim);
        if (needle.isEmpty() || h.text().isEmpty()) return null;

        int from = 0;
        int seen = 0;
        int idx;
        while ((idx = h.text().indexOf(needle, from)) >= 0) {
            int nEnd = idx + needle.length();
            if (onTokenBoundary(h.text(), idx, nEnd)) {
                if (seen == occurrence) {
                    int start = h.origStart()[idx];
                    int end = h.origEnd()[nEnd - 1];
                    return new MentionSpan(start, end);
                }
                seen++;
            }
            from = idx + 1;
        }
        return null;
    }

    /** The normalized form of a reference needle (used for alignment and for de-duplication). */
    public static String normalizeNeedle(String verbatim) {
        return normalize(verbatim).text();
    }

    private static boolean onTokenBoundary(String hay, int start, int end) {
        boolean leftOk = start == 0 || hay.charAt(start - 1) == ' ';
        boolean rightOk = end == hay.length() || hay.charAt(end) == ' ';
        return leftOk && rightOk;
    }

    /**
     * Normalizes {@code original} to a lowercase, diacritic-folded string in which every run of
     * non-alphanumeric characters is collapsed to a single space (never leading/trailing), keeping
     * a parallel map from each normalized character to the original offset range it derives from.
     */
    private static Normalized normalize(String original) {
        StringBuilder sb = new StringBuilder(original.length());
        List<Integer> starts = new ArrayList<>(original.length());
        List<Integer> ends = new ArrayList<>(original.length());
        boolean pendingSpace = false;   // a separator run seen after real content
        int spaceStart = -1;
        int spaceEnd = -1;

        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (pendingSpace) {
                    sb.append(' ');
                    starts.add(spaceStart);
                    ends.add(spaceEnd);
                    pendingSpace = false;
                }
                String folded = fold(c);
                for (int k = 0; k < folded.length(); k++) {
                    sb.append(folded.charAt(k));
                    starts.add(i);
                    ends.add(i + 1);
                }
            } else if (sb.length() > 0) {
                // Separator — remembered only once real content has been emitted, and only
                // realized (as a single space) when the next alphanumeric char arrives, so a
                // trailing separator run never adds a trailing space.
                if (!pendingSpace) {
                    pendingSpace = true;
                    spaceStart = i;
                }
                spaceEnd = i + 1;
            }
        }

        int[] os = new int[starts.size()];
        int[] oe = new int[ends.size()];
        for (int i = 0; i < os.length; i++) {
            os[i] = starts.get(i);
            oe[i] = ends.get(i);
        }
        return new Normalized(sb.toString(), os, oe);
    }

    /** Lowercase + strip diacritics for one character (may fold to zero, one, or several chars). */
    private static String fold(char c) {
        String nfd = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
        String stripped = COMBINING_MARKS.matcher(nfd).replaceAll("");
        return stripped.toLowerCase();
    }
}
