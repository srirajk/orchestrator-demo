package ai.conduit.gateway.architecture;

import ai.conduit.gateway.domain.auth.RequestContext;
import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import ai.conduit.gateway.domain.chat.ChatService;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2.1 — the tenant-context resolution seam is a single, enforced choke point.
 *
 * <ol>
 *   <li><b>onlyResolverReadsTenantClaim</b> — the raw {@code "tenant_id"} claim string is referenced by
 *       exactly the resolver and the A1 audience/tenant verifier, nowhere else. A future direct reader
 *       (e.g. a consumer that re-reads the claim instead of taking the resolved context) turns this red.</li>
 *   <li><b>noDownstreamStaticTenantLookup</b> — only the servlet capture seam (the filter that sets it and
 *       the protected controllers that capture it) may touch {@link RequestContext#getTenant()}. Every other
 *       production class must receive the {@link TenantExecutionContext} explicitly.</li>
 *   <li><b>protectedControllerEntriesCarryTenant</b> — the service entry methods the protected controllers
 *       call ({@code ChatService.handleChat} / {@code decideRoute}) declare a {@link TenantExecutionContext}
 *       parameter, so the context is threaded, not recovered.</li>
 * </ol>
 */
public class TenantContextSeamArchTest {

    private static final JavaClasses PRODUCTION =
            new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                    .importPackages("ai.conduit.gateway");

    // ── A2.1 (1): only the resolver + the A1 verifier reference the claim string ──────────────────
    @Test
    void onlyResolverReadsTenantClaim() throws IOException {
        Path mainJava = Path.of("src/main/java");
        assertThat(Files.isDirectory(mainJava))
                .as("source scan must run from the gateway module dir").isTrue();

        List<String> offenders;
        try (Stream<Path> files = Files.walk(mainJava)) {
            offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(TenantContextSeamArchTest::referencesTenantClaimLiteral)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !name.equals("TenantContextResolver.java")   // the resolver (A2)
                            && !name.equals("SecurityConfig.java"))              // the A1 verifier
                    .toList();
        }
        assertThat(offenders)
                .as("only TenantContextResolver + SecurityConfig may reference the \"tenant_id\" claim literal")
                .isEmpty();
    }

    private static boolean referencesTenantClaimLiteral(Path javaFile) {
        try {
            return Files.readString(javaFile).contains("\"tenant_id\"");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ── A2.1 (2): no downstream static tenant lookup ─────────────────────────────────────────────
    static final DescribedPredicate<JavaMethodCall> REQUEST_CONTEXT_GET_TENANT =
            new DescribedPredicate<>("a call to RequestContext.getTenant()") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return call.getTarget().getOwner().isEquivalentTo(RequestContext.class)
                            && call.getTarget().getName().equals("getTenant");
                }
            };

    @Test
    void noDownstreamStaticTenantLookup() {
        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName(
                        "ai.conduit.gateway.api.v1.chat.ChatCompletionsController")
                .and().doNotHaveFullyQualifiedName(
                        "ai.conduit.gateway.api.v1.admin.RouteDecisionController")
                .and().doNotHaveFullyQualifiedName(
                        "ai.conduit.gateway.api.v1.insights.InsightsController")
                .should(callMethodWhere(REQUEST_CONTEXT_GET_TENANT))
                .because("the tenant is captured on the servlet thread by the controller capture seam and "
                        + "threaded EXPLICITLY as a TenantExecutionContext; downstream code must never "
                        + "recover it from the static RequestContext holder");
        rule.check(PRODUCTION);
    }

    // ── A2.1 (3): protected controller-to-service entries carry the tenant ───────────────────────
    @Test
    void protectedControllerEntriesCarryTenant() {
        assertThat(entryMethodsNamed("handleChat"))
                .as("every ChatService.handleChat overload carries a TenantExecutionContext")
                .isNotEmpty().allMatch(TenantContextSeamArchTest::declaresTenant);
        assertThat(entryMethodsNamed("decideRoute"))
                .as("ChatService.decideRoute carries a TenantExecutionContext")
                .isNotEmpty().allMatch(TenantContextSeamArchTest::declaresTenant);
    }

    private static List<java.lang.reflect.Method> entryMethodsNamed(String name) {
        return Stream.of(ChatService.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name)
                        && java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .toList();
    }

    private static boolean declaresTenant(java.lang.reflect.Method m) {
        return Stream.of(m.getParameterTypes()).anyMatch(t -> t.equals(TenantExecutionContext.class));
    }
}
