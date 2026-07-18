package ai.conduit.gateway.infrastructure.tenancy;

import ai.conduit.gateway.domain.auth.CerbosEntitlementAdapter;
import ai.conduit.gateway.domain.auth.Principal;
import ai.conduit.gateway.domain.auth.TenantContextResolver;
import ai.conduit.gateway.domain.auth.TenantExecutionContext;
import ai.conduit.gateway.infrastructure.outbound.OutboundGate;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.AgentManifest.Constraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for bug-296: the single-tenant demo's real application default must be a policy version
 * that the real Cerbos PDP accepts and that selects the deployed base bundle. This deliberately reads
 * {@code application.yml}; a test-only tenant version cannot make this proof false-green.
 */
class DefaultTenantPolicyVersionCerbosIT {

    // This is version-specific regression evidence. Do not permit an environment override to silently
    // turn the claimed Cerbos 0.53.0 proof into a run against a different PDP implementation/tag.
    private static final DockerImageName CERBOS_IMAGE =
            DockerImageName.parse("ghcr.io/cerbos/cerbos:0.53.0");

    @Test
    void demoDefaultTenantVersionServesThroughRealCerbos() throws Exception {
        String provisionedTenants = demoProvisionedTenantsProperty();
        ConfigBackedTenantSnapshotSource source = new ConfigBackedTenantSnapshotSource(provisionedTenants);
        SnapshotProvisionedTenantDirectory directory = new SnapshotProvisionedTenantDirectory();
        directory.install(source.fetch());

        TenantExecutionContext tenant = new TenantContextResolver(directory).resolve(defaultTenantJwt());
        assertThat(tenant.activePolicyVersion())
                .as("the application.yml default must select the deployed Cerbos base-policy version")
                .isEqualTo("default");

        Path auditDir = Files.createTempDirectory("cerbos-default-version-it");
        auditDir.toFile().setWritable(true, false);
        auditDir.toFile().setReadable(true, false);
        auditDir.toFile().setExecutable(true, false);

        try (GenericContainer<?> cerbos = realCerbos(auditDir)) {
            cerbos.start();
            CerbosEntitlementAdapter adapter = adapter(cerbos);
            Principal jane = new Principal("rm_jane", List.of("relationship_manager"), List.of(),
                    Map.of("wealth", "confidential-pii"), List.of());
            AgentManifest wealthAgent = wealthAgent();

            CerbosEntitlementAdapter.BatchResult entitlement =
                    adapter.checkAgents(jane, List.of(wealthAgent), tenant);
            assertThat(entitlement.source()).isEqualTo("cerbos");
            assertThat(entitlement.isAllowed(wealthAgent.agentId()))
                    .as("the default tenant's SERVED entitlement must survive a real PDP check")
                    .isTrue();
            assertThat(adapter.checkAgentMembership(jane, List.of(wealthAgent), tenant))
                    .as("the default tenant's membership probe must survive the same real PDP check")
                    .containsEntry(wealthAgent.agentId(), true);
        }
    }

    private static String demoProvisionedTenantsProperty() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("gateway-application", new ClassPathResource("application.yml"));
        loaded.forEach(sources::addLast);
        String value = new PropertySourcesPropertyResolver(sources)
                .getProperty("conduit.tenancy.provisioned-tenants");
        assertThat(value).as("application.yml demo tenancy configuration").isNotBlank();
        return value;
    }

    private static GenericContainer<?> realCerbos(Path auditDir) {
        return new GenericContainer<>(CERBOS_IMAGE)
                .withExposedPorts(3592)
                .withFileSystemBind(repoRoot().resolve("infra/cerbos").toString(), "/conf", BindMode.READ_ONLY)
                .withFileSystemBind(auditDir.toString(), "/var/log/cerbos", BindMode.READ_WRITE)
                .withCommand("server", "--config=/conf/config.yaml")
                .waitingFor(Wait.forHttp("/_cerbos/health").forPort(3592).forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(60)));
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("infra/cerbos/config.yaml"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("could not locate infra/cerbos from " + Path.of("").toAbsolutePath());
        }
        return dir;
    }

    private static CerbosEntitlementAdapter adapter(GenericContainer<?> cerbos) {
        String host = cerbos.getHost();
        int port = cerbos.getMappedPort(3592);
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        RestClient restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl("http://" + host + ":" + port)
                .build();
        return new CerbosEntitlementAdapter(restClient, new ObjectMapper(),
                new OutboundGate(new SimpleMeterRegistry(), 16, 250), host, port, 3000,
                "closed", "relationship");
    }

    private static Jwt defaultTenantJwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("verified-demo-token")
                .header("alg", "RS256")
                .subject("rm_jane")
                .claim("tenant_id", "default")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }

    private static AgentManifest wealthAgent() {
        return new AgentManifest("wealth-holdings", "Wealth holdings", null, "1", null,
                "wealth-management", "segment", null, null, "http", null, null, null,
                new Constraints("read", "confidential-pii", 5000),
                null, null, null, true, null);
    }
}
