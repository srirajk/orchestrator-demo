package ai.meridian.gateway.domain.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A manifest-declared entity type. This is the load-bearing declaration that makes the
 * gateway's input pipeline domain-agnostic: extraction, resolution and binding all loop
 * over these instead of hardcoding wealth-shaped fields.
 *
 * @param key          canonical resolved field name used in agent input binding
 *                     (e.g. {@code relationship_id}, {@code fund_id}, {@code period}).
 * @param extractAs    the field the LLM extracts the raw human value into
 *                     (e.g. {@code relationship_reference}).
 * @param kind         one of {@code resolvable} (name → id via the resolver),
 *                     {@code literal} (value used as-is) or {@code list} (array of strings).
 * @param display      human label, used to compile prompt/clarification copy.
 * @param idPattern    regex hint that recognises a literal ID (lets the resolver short-circuit
 *                     and powers the keyword-fallback extractor). May be null for some literals.
 * @param resolveType  type string passed to the resolver — resolvable kinds only.
 * @param required     whether this entity must be present for the request to proceed.
 * @param defaultValue optional default applied when the value is absent (literals).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EntityType(
    String key,
    @JsonProperty("extract_as") String extractAs,
    String kind,
    String display,
    @JsonProperty("id_pattern") String idPattern,
    @JsonProperty("resolve_type") String resolveType,
    boolean required,
    @JsonProperty("default") String defaultValue
) {
    public boolean isResolvable() { return "resolvable".equals(kind); }
    public boolean isLiteral()    { return "literal".equals(kind); }
    public boolean isList()       { return "list".equals(kind); }
}
