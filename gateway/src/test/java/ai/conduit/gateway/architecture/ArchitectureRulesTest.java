package ai.conduit.gateway.architecture;

import ai.conduit.gateway.adapter.ProtocolAdapter;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import static com.tngtech.archunit.lang.conditions.ArchConditions.callConstructor;
import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Codified, always-true architecture invariants for the gateway (F5 spec §3a).
 *
 * <p>Scope is production classes only — {@link ImportOption.DoNotIncludeTests} — so the deliberate
 * violators in {@code architecture.fixtures} (committed to prove these rules can fail; see
 * {@link ArchitectureRulesFixtureTest}) do not trip the positive rules here.
 *
 * <p>These lock invariants that are true at commit time; they are guards against regression, not
 * refactors. Each rule names the downstream story that legitimately retargets it (F5 spec §8).
 */
@AnalyzeClasses(packages = "ai.conduit.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureRulesTest {

    // ── Rule 1: the agent-call chokepoint ─────────────────────────────────────────────────────────
    // Owner+name scoped on purpose: only a call whose *target owner* is ProtocolAdapter and whose
    // name is exactly "invoke" counts. HttpAdapter.invokeGet/invokePost (owner HttpAdapter, different
    // name) and any adapter's own invoke override (owner HttpAdapter/McpAdapter, not ProtocolAdapter)
    // are NOT caught — this locks the interface chokepoint, not the adapters' private plumbing.
    // GovernedInvoker retargets this rule later (F5 §8).
    static final DescribedPredicate<JavaMethodCall> PROTOCOL_ADAPTER_INVOKE =
            new DescribedPredicate<>("a call to ProtocolAdapter.invoke") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return call.getTarget().getOwner().isEquivalentTo(ProtocolAdapter.class)
                            && call.getTarget().getName().equals("invoke");
                }
            };

    @ArchTest
    static final ArchRule only_harness_invokes_protocol_adapter =
            noClasses()
                    .that().resideOutsideOfPackage("..orchestration.harness..")
                    .should(callMethodWhere(PROTOCOL_ADAPTER_INVOKE))
                    .because("ProtocolAdapter.invoke is the single agent-call chokepoint; every outbound "
                            + "agent call must pass through AgentHarness (bulkhead → breaker → SLA). A "
                            + "caller elsewhere would bypass the resilience guard.");

    // ── Rule 2: object-store seam ─────────────────────────────────────────────────────────────────
    // Only the audit sink or the reserved objectstore port may touch the AWS S3 SDK. The claim-check
    // story lands its MinIO spill behind ..infrastructure.objectstore.. without editing this rule.
    @ArchTest
    static final ArchRule object_store_access_confined_to_seam =
            noClasses()
                    .that().resideOutsideOfPackage("..infrastructure.audit..")
                    .and().resideOutsideOfPackage("..infrastructure.objectstore..")
                    .should().dependOnClassesThat().resideInAPackage("software.amazon.awssdk.services.s3..")
                    .because("S3/object-store access is confined to the audit sink and the reserved "
                            + "objectstore port; nothing else on the request path may reach WORM storage directly.");

    // ── Rule 3: HTTP-client hygiene (plain rule — nothing to freeze, fails on first new violation) ──
    // 3a. No no-arg `new RestTemplate()` — that ctor uses SimpleClientHttpRequestFactory over
    //     HttpURLConnection with an infinite read timeout. RemoteEmbedder's `new RestTemplate(factory)`
    //     (1-arg, timed) is deliberately not matched.
    private static final ArchRule NO_NOARG_RESTTEMPLATE =
            noClasses()
                    .should(callConstructor(RestTemplate.class))
                    .because("a no-arg RestTemplate has no connect/read timeout and can pin a virtual "
                            + "thread's carrier — construct it only with a timed request factory.");

    // 3b. No dependency on HttpURLConnection anywhere (its getInputStream0() is synchronized → carrier pin).
    private static final ArchRule NO_HTTP_URL_CONNECTION =
            noClasses()
                    .should().dependOnClassesThat().areAssignableTo(java.net.HttpURLConnection.class)
                    .because("HttpURLConnection has no first-class timeouts and synchronized internals that "
                            + "pin virtual-thread carriers; use the timed JDK HttpClient-backed clients.");

    // 3c. No RestClient static factory (builder/create) outside ..config.. — construction of
    //     HTTP clients is centralized so timeouts/observation are applied in exactly one place.
    //     (WebClient dropped from the classpath in F3 — spring-webflux/reactor-netty removed once
    //     CoverageClient migrated off WebClient to a timed RestClient.)
    private static final DescribedPredicate<JavaMethodCall> RESTCLIENT_FACTORY =
            new DescribedPredicate<>("a RestClient static factory call") {
                @Override
                public boolean test(JavaMethodCall call) {
                    var owner = call.getTarget().getOwner();
                    boolean rightOwner = owner.isEquivalentTo(RestClient.class);
                    String name = call.getTarget().getName();
                    return rightOwner && (name.equals("builder") || name.equals("create"));
                }
            };

    private static final ArchRule NO_OUT_OF_CONFIG_HTTP_CLIENT_FACTORY =
            noClasses()
                    .that().resideOutsideOfPackage("..config..")
                    .should(callMethodWhere(RESTCLIENT_FACTORY))
                    .because("HTTP clients are constructed only in ..config.. so connect/read timeouts and "
                            + "observation are applied uniformly; inject the built client/builder elsewhere.");

    /** Composite of the three HTTP-client hygiene guards; named as a single test for the harness. */
    @ArchTest
    static final ArchRule no_unhygienic_http_clients =
            CompositeArchRule.of(NO_NOARG_RESTTEMPLATE)
                    .and(NO_HTTP_URL_CONNECTION)
                    .and(NO_OUT_OF_CONFIG_HTTP_CLIENT_FACTORY);

    /** Exposed for {@link ArchitectureRulesFixtureTest} to evaluate against the committed fixtures. */
    static ArchRule noNoArgRestTemplateRule() {
        return NO_NOARG_RESTTEMPLATE;
    }
}
