package ai.meridian.gateway.domain.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record EffectiveManifest(
    String domainId,
    String subDomainId,
    String agentId,
    List<String> requiredContext,
    List<String> clarificationPriority,
    Map<String, ClarificationSchema> clarificationSchema,
    DomainManifest.EntityRegistry entityRegistry,
    DomainManifest.AuthorizationContract authorizationContract,
    List<String> mustPreserve,
    List<String> canDrop
) {

    public static EffectiveManifest merge(DomainManifest domain, SubDomainManifest sub, String agentId) {
        List<String> requiredContext = union(
            domain != null && sub != null ? sub.requiredContext() : null,
            null
        );
        if (domain == null && sub == null) requiredContext = List.of();

        List<String> clarPriority = sub != null && sub.clarificationPriority() != null
            ? sub.clarificationPriority() : List.of();

        Map<String, ClarificationSchema> schema = new LinkedHashMap<>();
        if (sub != null && sub.clarificationSchema() != null) {
            schema.putAll(sub.clarificationSchema());
        }

        DomainManifest.EntityRegistry registry = mostSpecific(
            sub != null ? sub.entityRegistry() : null,
            domain != null ? domain.entityRegistry() : null
        );

        DomainManifest.AuthorizationContract contract = mostSpecific(
            sub != null ? sub.authorizationContract() : null,
            domain != null ? domain.authorizationContract() : null
        );

        List<String> mustPreserve = new ArrayList<>();
        List<String> canDrop = new ArrayList<>();
        if (domain != null && domain.memoryCompaction() != null) {
            if (domain.memoryCompaction().mustPreserve() != null) mustPreserve.addAll(domain.memoryCompaction().mustPreserve());
            if (domain.memoryCompaction().canDrop() != null) canDrop.addAll(domain.memoryCompaction().canDrop());
        }

        return new EffectiveManifest(
            domain != null ? domain.domainId() : null,
            sub != null ? sub.subDomainId() : null,
            agentId,
            requiredContext,
            clarPriority,
            schema,
            registry,
            contract,
            mustPreserve,
            canDrop
        );
    }

    private static List<String> union(List<String> a, List<String> b) {
        List<String> result = new ArrayList<>();
        if (a != null) result.addAll(a);
        if (b != null) b.stream().filter(x -> !result.contains(x)).forEach(result::add);
        return result;
    }

    private static <T> T mostSpecific(T subLevel, T domainLevel) {
        return subLevel != null ? subLevel : domainLevel;
    }
}
