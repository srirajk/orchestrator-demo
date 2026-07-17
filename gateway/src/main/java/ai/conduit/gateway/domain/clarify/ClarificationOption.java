package ai.conduit.gateway.domain.clarify;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One offered choice in a structured clarification form.
 *
 * <p>Every field is DATA sourced from the deterministic resolve/discover result or the effective
 * manifest — never a domain literal authored in the gateway (World B). The SPA renders these blind:
 * it shows {@link #label} (and {@link #secondaryLabel} if present) and, on selection, submits
 * {@link #value} back verbatim. {@code value} is the exact token the offered-set validation predicate
 * checks against (see {@link ClarificationDescriptor#validate(String)}), so it must be the canonical
 * id / capability id, never a positional index.
 *
 * @param value          the submit token — a canonical entity id or capability id from the grounded set
 * @param label          the human display label (e.g. a coverage resource's name)
 * @param secondaryLabel optional supporting text (e.g. the id shown alongside the name); may be null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClarificationOption(String value, String label, String secondaryLabel) {

    public ClarificationOption {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ClarificationOption.value must be a non-blank submit token");
        }
    }

    /** Convenience for a capability/option with no secondary text. */
    public static ClarificationOption of(String value, String label) {
        return new ClarificationOption(value, label, null);
    }
}
