package ai.meridian.gateway.synthesis.input;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stage 2 — Resolve.
 *
 * Pure deterministic lookup; no LLM involved.  Maps human-readable relationship
 * and fund references to their canonical system IDs.
 *
 * Rules:
 * <ul>
 *   <li>Matching is case-insensitive and uses {@code contains} semantics so that
 *       "Whitman" and "Whitman Family Office" both resolve to "REL-00042".</li>
 *   <li>If a relationship reference is present but matches nothing → the returned
 *       bag has {@code needsClarification=true} and {@code relationshipId=null}.</li>
 *   <li>Fund references that already look like "FND-XXXX" are kept as-is; anything
 *       else is looked up in the fund table.</li>
 *   <li>Tickers and period pass through unchanged.</li>
 * </ul>
 */
@Service
public class EntityResolver {

    private static final Logger log = LoggerFactory.getLogger(EntityResolver.class);

    /**
     * Ordered lookup table: entries are checked in insertion order.
     * The key is a lowercase substring to match against the verbatim reference.
     */
    private static final Map<String, String> RELATIONSHIP_TABLE = new LinkedHashMap<>();
    private static final Map<String, String> FUND_TABLE = new LinkedHashMap<>();

    static {
        // ── Relationship seed entities (from agent-catalog.md) ───────────────
        RELATIONSHIP_TABLE.put("whitman",  "REL-00042");
        RELATIONSHIP_TABLE.put("chen",     "REL-00099");
        RELATIONSHIP_TABLE.put("okafor",   "REL-00188");  // rm_jane denied by JWT book, not by bad ID
        RELATIONSHIP_TABLE.put("andersen", "REL-00200");

        // ── Fund seed entities ───────────────────────────────────────────────
        FUND_TABLE.put("fnd-7781", "FND-7781");          // identity mapping
        FUND_TABLE.put("innovation fund", "FND-7781");
    }

    /**
     * Resolves verbatim references in {@code bag} to system IDs and returns a new
     * {@link EntityBag} with the {@code relationshipId}, {@code fundId}, and
     * {@code needsClarification} fields populated.
     */
    public EntityBag resolve(EntityBag bag) {
        String relationshipId = resolveRelationship(bag.relationshipReference());
        String fundId         = resolveFund(bag.fundReference());

        boolean needsClarification =
                (bag.relationshipReference() != null && !bag.relationshipReference().isBlank()
                        && relationshipId == null);

        if (needsClarification) {
            log.warn("Could not resolve relationship reference '{}' — clarification needed.",
                    bag.relationshipReference());
        } else {
            log.debug("Resolved: relRef='{}' → '{}', fundRef='{}' → '{}'",
                    bag.relationshipReference(), relationshipId,
                    bag.fundReference(), fundId);
        }

        return bag.withResolved(relationshipId, fundId, needsClarification);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private String resolveRelationship(String reference) {
        if (reference == null || reference.isBlank()) return null;
        String lower = reference.toLowerCase();
        for (Map.Entry<String, String> entry : RELATIONSHIP_TABLE.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String resolveFund(String reference) {
        if (reference == null || reference.isBlank()) return null;

        // Already a system ID?
        if (reference.toUpperCase().matches("FND-[A-Z0-9]+")) {
            return reference.toUpperCase();
        }

        String lower = reference.toLowerCase();
        for (Map.Entry<String, String> entry : FUND_TABLE.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        // Unresolved fund — not a clarification trigger (fund is usually optional)
        log.debug("Fund reference '{}' could not be resolved; leaving null.", reference);
        return null;
    }
}
