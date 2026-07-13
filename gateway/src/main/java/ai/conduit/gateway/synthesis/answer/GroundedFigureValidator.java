package ai.conduit.gateway.synthesis.answer;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GroundedFigureValidator {

    private static final Pattern NUMERAL = Pattern.compile("(?<![A-Za-z0-9_])\\$?\\d[\\d,]*(?:\\.\\d+)?%?");
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "or", "of", "to", "a", "an", "is", "are", "was", "were",
            "for", "from", "by", "with", "in", "on", "as", "at", "this", "that",
            "value", "figure", "amount", "number");

    public ValidationResult validate(String answer, List<GroundedFigure> figures) {
        if (answer == null || answer.isBlank() || figures == null || figures.isEmpty()) {
            return ValidationResult.success();
        }
        List<String> errors = new ArrayList<>();
        for (Sentence sentence : sentences(answer)) {
            List<GroundedFigure> labelled = figures.stream()
                    .filter(f -> labelMatches(sentence.text(), f.label()))
                    .toList();
            Matcher matcher = NUMERAL.matcher(sentence.text());
            while (matcher.find()) {
                if (isListOrdinal(sentence.text(), matcher.start(), matcher.end())) continue;
                if (isSignedIdentifierSuffix(sentence.text(), matcher.start())) continue;
                String token = matcher.group();
                Double value = parse(token);
                if (value == null) continue;
                Token parsed = new Token(token, value,
                        token.contains("%") || hasCharAt(sentence.text(), matcher.end(), '%'),
                        token.contains("$"));
                // A numeral is grounded if it matches ANY real figure — a natural sentence may
                // legitimately combine two figures (e.g. "threshold is 10%, breached 6 times"),
                // so we must not scope the check to the sentence's nearest label. The `labelled`
                // set still drives strictness below: a plain numeral sitting next to a label is
                // held to the same bar as a load-bearing one. (bug-273)
                boolean matched = figures.stream().anyMatch(f -> valueMatches(parsed, f));
                if (!matched) {
                    if (!labelled.isEmpty() || isLoadBearing(parsed)) {
                        errors.add("unattributed numeral '" + token + "' near label(s) "
                                + labelled.stream().map(GroundedFigure::label).toList());
                    }
                }
            }
        }
        return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failure(errors);
    }

    private boolean isListOrdinal(String sentence, int start, int end) {
        if (sentence == null) return false;
        String prefix = sentence.substring(0, start).strip();
        if (!prefix.isEmpty()) return false;
        if (end >= sentence.length()) return false;
        char next = sentence.charAt(end);
        return next == '.' || next == ')';
    }

    private boolean isSignedIdentifierSuffix(String sentence, int start) {
        if (sentence == null || start < 2) return false;
        char sign = sentence.charAt(start - 1);
        if (sign != '+' && sign != '-') return false;
        char beforeSign = sentence.charAt(start - 2);
        return Character.isLetterOrDigit(beforeSign);
    }

    private boolean hasCharAt(String text, int index, char expected) {
        return text != null && index >= 0 && index < text.length() && text.charAt(index) == expected;
    }

    private boolean valueMatches(Token token, GroundedFigure figure) {
        if (!formatCompatible(token, figure)) return false;
        for (Double candidate : figure.numericValues()) {
            if (candidate == null) continue;
            double tolerance = Math.max(0.01, Math.abs(candidate) * 0.0001);
            if (Math.abs(token.value() - candidate) <= tolerance) return true;
        }
        return false;
    }

    private boolean isLoadBearing(Token token) {
        return token.percent() || token.currency();
    }

    private boolean formatCompatible(Token token, GroundedFigure figure) {
        String format = figure.format() == null ? "plain" : figure.format();
        if (token.percent()) return format.startsWith("percent");
        if (token.currency()) return "currency_usd".equals(format);
        return true;
    }

    private boolean labelMatches(String text, String label) {
        Set<String> labelTokens = tokens(label);
        if (labelTokens.isEmpty()) return false;
        Set<String> textTokens = tokens(text);
        int hits = 0;
        for (String token : labelTokens) {
            if (textTokens.contains(token)) hits++;
        }
        return hits == labelTokens.size();
    }

    private Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.isBlank() || STOPWORDS.contains(token)) continue;
            out.add(token);
        }
        return out;
    }

    private Double parse(String token) {
        if (token == null) return null;
        try {
            return Double.parseDouble(token.replace("$", "").replace(",", "").replace("%", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<Sentence> sentences(String text) {
        List<Sentence> out = new ArrayList<>();
        for (String part : text.split("(?<=[.!?\\n])\\s+")) {
            if (!part.isBlank()) out.add(new Sentence(part));
        }
        return out;
    }

    private record Sentence(String text) {}

    private record Token(String text, double value, boolean percent, boolean currency) {}

    public record ValidationResult(boolean ok, List<String> errors) {
        public static ValidationResult success() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult failure(List<String> errors) {
            return new ValidationResult(false, List.copyOf(errors));
        }
    }
}
