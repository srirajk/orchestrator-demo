package ai.conduit.gateway.architecture.fixtures;

import org.springframework.web.client.RestTemplate;

/**
 * DELIBERATE VIOLATOR — committed on purpose (F5 spec §3a-4).
 *
 * <p>Constructs a no-arg {@link RestTemplate} (no timeouts), so it violates the HTTP-client hygiene
 * rule {@code ArchitectureRulesTest.no_unhygienic_http_clients}. Excluded from the positive rule's
 * scope (DoNotIncludeTests) and asserted RED by {@link ai.conduit.gateway.architecture.ArchitectureRulesFixtureTest}.
 *
 * <p>Never executed — only the no-arg constructor call in its bytecode matters.
 */
public final class UnhygienicHttpClientFactory {

    private UnhygienicHttpClientFactory() {}

    static RestTemplate untimedRestTemplate() {
        // The forbidden no-arg ctor: SimpleClientHttpRequestFactory over HttpURLConnection, no read timeout.
        return new RestTemplate();
    }
}
