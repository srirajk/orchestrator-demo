package com.openwolf.iam.policystudio.lifecycle;

import com.openwolf.iam.policystudio.PolicyIR;
import com.openwolf.iam.policystudio.PolicyParseException;
import com.openwolf.iam.policystudio.PolicyYamlParser;
import com.openwolf.iam.policystudio.StudioGroundingProvider;
import com.openwolf.iam.policystudio.StudioGroundingSnapshot;
import com.openwolf.iam.policystudio.TenantScope;
import org.springframework.stereotype.Component;

/**
 * The production {@link PromotionValidationContextProvider} (Axiom H2). It derives the validator context
 * from the same manifest-backed source used by the author/review APIs. The base ceiling is never rebuilt
 * from rendered bundle YAML; the bundle is only inspected to identify the tenant/resource child policy.
 */
@Component
public class ManifestPromotionValidationContextProvider implements PromotionValidationContextProvider {

    private final StudioGroundingProvider grounding;
    private final PolicyYamlParser parser;

    public ManifestPromotionValidationContextProvider(StudioGroundingProvider grounding, PolicyYamlParser parser) {
        this.grounding = grounding;
        this.parser = parser;
    }

    @Override
    public PromotionValidationContext contextFor(PolicyBundle candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("candidate bundle must be set");
        }
        PolicyIR child = firstTenantChild(candidate);
        StudioGroundingSnapshot snapshot = grounding.snapshot(candidate.tenantId(), child.resource());
        boolean subscopesEnabled = !candidate.tenantId().equals(child.scope());
        return new PromotionValidationContext(
                snapshot.vocabulary(),
                TenantScope.of(candidate.tenantId()),
                subscopesEnabled,
                snapshot.baseCeiling());
    }

    private PolicyIR firstTenantChild(PolicyBundle candidate) {
        for (BundleFile file : candidate.renderedFiles()) {
            try {
                PolicyIR ir = parser.parse(file.yaml());
                if (ir.scope() != null && !ir.scope().isBlank()) {
                    return ir;
                }
            } catch (PolicyParseException ignored) {
                // Non-resource-policy bundle files are allowed in the snapshot; they are not the child
                // policy that identifies the manifest resource kind.
            }
        }
        throw new IllegalStateException("candidate '" + candidate.bundleId()
                + "' carries no tenant-child resource policy; cannot choose manifest grounding");
    }
}
