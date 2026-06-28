package ai.meridian.gateway.domain.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record EffectiveManifest(
    String domainId,
    String subDomainId,
    String agentId,
    boolean resourceScoped,
    List<String> requiredContext,
    Map<String, ClarificationSchema> clarificationSchema,
    DomainManifest.Coverage coverage,
    List<String> mustPreserve,
    List<String> canDrop
) {

    public static EffectiveManifest merge(DomainManifest domain, SubDomainManifest sub, String agentId) {
        List<String> requiredContext = new ArrayList<>();
        if (sub != null && sub.requiredContext() != null) {
            requiredContext.addAll(sub.requiredContext());
        }

        Map<String, ClarificationSchema> schema = new LinkedHashMap<>();
        if (sub != null && sub.clarificationSchema() != null) {
            schema.putAll(sub.clarificationSchema());
        }

        boolean resourceScoped = sub != null && sub.resourceScoped();

        DomainManifest.Coverage coverage = domain != null ? domain.coverage() : null;

        List<String> mustPreserve = new ArrayList<>();
        List<String> canDrop = new ArrayList<>();
        if (domain != null && domain.memoryCompaction() != null) {
            if (domain.memoryCompaction().mustPreserve() != null)
                mustPreserve.addAll(domain.memoryCompaction().mustPreserve());
            if (domain.memoryCompaction().canDrop() != null)
                canDrop.addAll(domain.memoryCompaction().canDrop());
        }

        return new EffectiveManifest(
            domain != null ? domain.domainId() : null,
            sub != null ? sub.subDomainId() : null,
            agentId,
            resourceScoped,
            requiredContext,
            schema,
            coverage,
            mustPreserve,
            canDrop
        );
    }

    /** Returns true if this effective manifest requires a relationship_id in context. */
    public boolean requiresRelationship() {
        return requiredContext != null && requiredContext.contains("relationship_id");
    }

    /** Returns the ClarificationSchema for relationship_id, or null if not configured. */
    public ClarificationSchema relationshipClarification() {
        if (clarificationSchema == null) return null;
        return clarificationSchema.get("relationship_id");
    }
}
