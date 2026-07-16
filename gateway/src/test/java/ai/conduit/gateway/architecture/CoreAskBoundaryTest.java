package ai.conduit.gateway.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * F1 §c — the core→ask boundary. The gateway core (orchestration + adapter + infrastructure) must not
 * depend on the ask-side packages (synthesis, resolver, intent, clarify, domain.chat). This is the
 * package-level belt written NOW inside the single module; the Maven multi-module split is a second
 * belt tracked separately (see the deliverables note). Red before the pre-move fixes (RoutePreparedData
 * imported domain.chat); green after.
 */
@AnalyzeClasses(packages = "ai.conduit.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
public class CoreAskBoundaryTest {

    @ArchTest
    static final ArchRule core_must_not_depend_on_ask =
            noClasses()
                    .that().resideInAnyPackage("..orchestration..", "..adapter..", "..infrastructure..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..synthesis..", "..resolver..", "..intent..", "..clarify..", "..domain.chat..")
                    .because("the gateway core (orchestration/adapter/infrastructure) is reusable by any "
                            + "front door; the ask-side pipeline (synthesis/resolver/intent/clarify/chat) "
                            + "depends on the core, never the reverse — so the two can split into separate "
                            + "Maven modules without a dependency cycle.");
}
