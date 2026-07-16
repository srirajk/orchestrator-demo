package ai.conduit.gateway.architecture;

import ai.conduit.gateway.orchestration.harness.AgentHarness;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * F1 §a — the governed-invocation funnel. Every agent dispatch must pass through the single
 * {@link ai.conduit.gateway.orchestration.invoke.GovernedInvoker} checkpoint (identity → authorize →
 * invoke → audit), so only the invoker may call {@link AgentHarness}{@code .execute*}. Any other
 * caller would dispatch an agent bypassing the fail-closed authorize/audit phases.
 *
 * <p>Production classes only ({@link ImportOption.DoNotIncludeTests}) — executor unit tests wire the
 * harness directly through the invoker's convenience seam and are not part of the production chokepoint.
 * Covers ALL {@code execute} overloads (1-/2-/3-arg): the predicate matches on owner + method name.
 */
@AnalyzeClasses(packages = "ai.conduit.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureFunnelTest {

    /** A call whose target owner is {@link AgentHarness} and whose name is exactly {@code execute}. */
    static final DescribedPredicate<JavaMethodCall> AGENT_HARNESS_EXECUTE =
            new DescribedPredicate<>("a call to AgentHarness.execute*") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return call.getTarget().getOwner().isEquivalentTo(AgentHarness.class)
                            && call.getTarget().getName().equals("execute");
                }
            };

    // The harness's own overloads delegate to each other (execute(node) → execute(node,exec,token)),
    // which is internal plumbing, not a bypass — so the harness package is excluded alongside the
    // invoker. Every OTHER caller (executors, ChatService, a future /v1/steps) must go through the invoker.
    @ArchTest
    static final ArchRule onlyGovernedInvokerCallsHarness =
            noClasses()
                    .that().resideOutsideOfPackage("..orchestration.invoke..")
                    .and().resideOutsideOfPackage("..orchestration.harness..")
                    .should(callMethodWhere(AGENT_HARNESS_EXECUTE))
                    .because("AgentHarness.execute* is dispatched ONLY by the GovernedInvoker checkpoint "
                            + "(identity → authorize → invoke → audit, fail-closed). A caller elsewhere "
                            + "would invoke an agent bypassing authorization and the audit verdict event.");
}
