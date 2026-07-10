package com.openwolf.iam.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Security configuration for the IAM service.
 * <p>
 * Two filter chains:
 * <ol>
 *   <li>Order 1 — Spring Authorization Server: handles /oauth/authorize, /oauth/token,
 *       /.well-known/openid-configuration, /oauth2/jwks, OIDC userinfo.</li>
 *   <li>Order 2 — Resource server + API: protects /users/**, /roles/**, /admin/**, etc.
 *       via JWT bearer tokens.</li>
 * </ol>
 * </p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${spring.security.oauth2.authorizationserver.issuer:http://localhost:8084}")
    private String issuerUrl;

    // Persistent location of the RSA signing key (full JWK JSON, incl. private params + stable kid).
    // The key is loaded if present, else generated once and written here. Persisting it means the
    // signing key + its kid survive restarts, so live sessions are not invalidated on every restart.
    @Value("${iam.signing-key-path:/app/keys/signing-key.json}")
    private String signingKeyPath;

    // Browser origins allowed to call this IAM cross-origin (Axiom's own admin/chat UIs).
    // Comma-separated, env-overridable. Adding an origin here is CORS allowlist config only,
    // not a change to auth logic. Default covers: legacy admin-ui (5180), Axiom admin console
    // (5182), and LibreChat (3080).
    @Value("${iam.cors.allowed-origins:http://localhost:5180,http://localhost:5182,http://localhost:3080}")
    private List<String> corsAllowedOrigins;

    @Value("${iam.oauth2.librechat.client-id:librechat}")
    private String librechatClientId;

    @Value("${iam.oauth2.librechat.client-secret:librechat-secret}")
    private String librechatClientSecret;

    @Value("${iam.oauth2.librechat.redirect-uri:http://localhost:3080/oauth/openid/callback}")
    private String librechatRedirectUri;

    @Value("${iam.oauth2.conduit-chat.client-id:conduit-chat}")
    private String conduitChatClientId;

    @Value("${iam.oauth2.conduit-chat.client-secret:conduit-chat-secret}")
    private String conduitChatClientSecret;

    @Value("${iam.oauth2.conduit-chat.redirect-uri:http://localhost:8099/api/auth/callback}")
    private String conduitChatRedirectUri;

    @Value("${iam.oauth2.conduit-chat.post-logout-redirect-uri:http://localhost:8099/}")
    private String conduitChatPostLogoutRedirectUri;

    @Value("${iam.oauth2.conduit-insights.client-id:conduit-insights}")
    private String conduitInsightsClientId;

    @Value("${iam.oauth2.conduit-insights.redirect-uri:http://localhost:5175/callback}")
    private String conduitInsightsRedirectUri;

    @Value("${iam.oauth2.conduit-insights.post-logout-redirect-uri:http://localhost:5175/}")
    private String conduitInsightsPostLogoutRedirectUri;

    @Value("${iam.oauth2.admin-ui.client-id:admin-ui-client}")
    private String adminUiClientId;

    @Value("${iam.oauth2.admin-ui.client-secret:admin-ui-secret}")
    private String adminUiClientSecret;

    @Value("${iam.oauth2.gateway.client-id:gateway-client}")
    private String gatewayClientId;

    @Value("${iam.oauth2.gateway.client-secret:gateway-secret}")
    private String gatewayClientSecret;

    // =========================================================
    // Filter Chain 1 — Spring Authorization Server
    // =========================================================

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        // Build the authorization server configurer to get its endpoint matcher
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                new OAuth2AuthorizationServerConfigurer();
        RequestMatcher endpointsMatcher = authorizationServerConfigurer.getEndpointsMatcher();

        // Include /login in this chain so form login is served here, not by the resource-server
        // chain. Static resources (/css/**) are intentionally NOT in this matcher — they fall
        // to Order-2 which permitAll()s them, avoiding the Bearer 401 from the resource server.
        http
            .securityMatcher(new OrRequestMatcher(
                    endpointsMatcher,
                    new AntPathRequestMatcher("/login"),
                    new AntPathRequestMatcher("/login/**")
            ))
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .csrf(csrf -> csrf.ignoringRequestMatchers(endpointsMatcher))
            .with(authorizationServerConfigurer, configurer ->
                    configurer.oidc(Customizer.withDefaults()))
            .exceptionHandling(exceptions -> exceptions
                    .defaultAuthenticationEntryPointFor(
                            new LoginUrlAuthenticationEntryPoint("/login"),
                            new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                    )
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
            .formLogin(form -> form.loginPage("/login").permitAll());

        return http.build();
    }

    // =========================================================
    // Filter Chain 2 — Resource Server (API endpoints)
    // =========================================================

    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints — no auth needed
                        .requestMatchers(
                                "/health",
                                "/actuator/**",
                                "/.well-known/**",
                                "/oauth2/**",
                                "/oauth/**",
                                "/auth/login",
                                "/auth/token",
                                "/login",
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/default-ui.css"
                        ).permitAll()
                        // The /admin/** surface — the audit trail and the Cerbos policy lifecycle —
                        // is administrative, not merely authenticated. Before this rule, any principal
                        // holding a valid token (e.g. a relationship manager whose only role is
                        // chat_user) could read the entire audit log and create, approve, or deploy
                        // authorization policies, because only UserController and AuthController#impersonate
                        // carried a @PreAuthorize and every other admin route fell through to
                        // anyRequest().authenticated(). Enforced here rather than per-method so a new
                        // @RequestMapping under /admin cannot silently arrive unguarded.
                        .requestMatchers("/admin/**")
                        .hasAnyRole("platform_admin", "tenant_admin", "domain_admin")
                        // Everything else requires a valid JWT
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    /**
     * Extract the "roles" claim from JWT and convert to Spring Security ROLE_ authorities.
     * This enables @PreAuthorize("hasRole('platform_admin')") on controllers.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter rolesConverter = new JwtGrantedAuthoritiesConverter();
        rolesConverter.setAuthoritiesClaimName("roles");
        rolesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(rolesConverter);
        return converter;
    }

    // =========================================================
    // CORS
    // =========================================================

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsAllowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    // =========================================================
    // OAuth2 Clients
    // =========================================================

    @Bean
    public RegisteredClientRepository registeredClientRepository(PasswordEncoder passwordEncoder) {
        // Gateway service — client_credentials (machine-to-machine)
        RegisteredClient gatewayClient = RegisteredClient.withId("gateway-client-id")
                .clientId(gatewayClientId)
                .clientSecret(passwordEncoder.encode(gatewayClientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("agent:read")
                .scope("admin:read")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .build())
                .build();

        // Admin UI — authorization_code + PKCE + refresh_token
        RegisteredClient adminUiClient = RegisteredClient.withId("admin-ui-client-id")
                .clientId(adminUiClientId)
                .clientSecret(passwordEncoder.encode(adminUiClientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://localhost:5180/callback")
                .redirectUri("http://localhost:5180/silent-renew.html")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .scope("roles")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .refreshTokenTimeToLive(Duration.ofDays(7))
                        .reuseRefreshTokens(false)
                        .build())
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .build())
                .build();

        // LibreChat — OIDC SSO ("Login with Meridian" button).
        // Accept BOTH client_secret_basic and client_secret_post: the LibreChat openid-client
        // sends the credentials in the request body (client_secret_post). Registering only
        // CLIENT_SECRET_BASIC makes Spring reject the token call with
        // "Client authentication failed: authentication_method" → invalid_client.
        RegisteredClient librechatClient = RegisteredClient.withId("librechat-client-id")
                .clientId(librechatClientId)
                .clientSecret(passwordEncoder.encode(librechatClientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(librechatRedirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(2))
                        .refreshTokenTimeToLive(Duration.ofDays(1))
                        .reuseRefreshTokens(false)
                        .build())
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .build())
                .build();

        // Conduit Chat — OIDC SSO for the end-user chat BFF (the user logs in AS THEMSELVES;
        // the BFF forwards the user's token to the gateway → entitlements as the real principal).
        // offline_access is included so Axiom issues a refresh_token alongside the access token,
        // enabling the BFF's OAuth2AuthorizedClientManager refresh_token provider to silently
        // renew credentials without a full re-login.
        RegisteredClient conduitChatClient = RegisteredClient.withId("conduit-chat-client-id")
                .clientId(conduitChatClientId)
                .clientSecret(passwordEncoder.encode(conduitChatClientSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(conduitChatRedirectUri)
                .postLogoutRedirectUri(conduitChatPostLogoutRedirectUri)
                .postLogoutRedirectUri("http://localhost:8099/")
                .postLogoutRedirectUri("http://localhost:8099/api/auth/login")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .scope("offline_access")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(2))
                        .refreshTokenTimeToLive(Duration.ofDays(1))
                        .reuseRefreshTokens(false)
                        .build())
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(false)
                        .build())
                .build();

        // Conduit Insights — public SPA (PKCE, no client secret); admin-only analytics. The SPA runs the
        // OIDC authorization-code + PKCE flow itself and calls the gateway with the bearer token; the
        // /v1/insights Cerbos gate performs the admin authorization. Separate app, no BFF.
        RegisteredClient conduitInsightsClient = RegisteredClient.withId("conduit-insights-client-id")
                .clientId(conduitInsightsClientId)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(conduitInsightsRedirectUri)
                .postLogoutRedirectUri(conduitInsightsPostLogoutRedirectUri)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope(OidcScopes.EMAIL)
                .scope("offline_access")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(2))
                        .refreshTokenTimeToLive(Duration.ofDays(1))
                        .reuseRefreshTokens(false)
                        .build())
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .build();

        return new InMemoryRegisteredClientRepository(gatewayClient, adminUiClient, librechatClient, conduitChatClient, conduitInsightsClient);
    }

    // =========================================================
    // JWK Source — RSA 2048, persisted (load-or-generate) so kid is stable across restarts
    // =========================================================

    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        RSAKey rsaKey = loadOrGenerateRsaKey();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    /**
     * Load the RSA signing key (with its stable kid) from {@code signingKeyPath} if it exists;
     * otherwise generate a new key once and write it there. This keeps the same key + kid across
     * restarts, so previously-issued tokens remain verifiable and live sessions survive a restart.
     */
    private RSAKey loadOrGenerateRsaKey() {
        Path path = Path.of(signingKeyPath);
        if (Files.exists(path)) {
            try {
                return RSAKey.parse(Files.readString(path));
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to load RSA signing key from " + path, ex);
            }
        }
        RSAKey generated = generateRsaKey();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, generated.toJSONString());
            // Restrict to owner-read/write only (0600) — the file contains the RSA private key.
            // Best-effort: UnsupportedOperationException is thrown on non-POSIX filesystems
            // (e.g. Windows, certain container overlays) and is silently ignored.
            try {
                Files.setPosixFilePermissions(path,
                        PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                log.warn("Could not restrict signing-key file permissions to 0600 (non-POSIX filesystem): {}", path);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to persist RSA signing key to " + path, ex);
        }
        return generated;
    }

    private RSAKey generateRsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
            RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate RSA 2048 key pair for JWT signing", ex);
        }
    }

    // =========================================================
    // JWT Encoder (used by /auth/login to issue RS256 tokens)
    // =========================================================

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    // =========================================================
    // JWT Decoder (for validating tokens on the resource server side)
    // =========================================================

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    // =========================================================
    // Authorization Server Settings
    // =========================================================

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .issuer(issuerUrl)
                // Map to /oauth/* paths to preserve backwards-compatible API paths
                .authorizationEndpoint("/oauth/authorize")
                .tokenEndpoint("/oauth/token")
                .tokenIntrospectionEndpoint("/oauth/introspect")
                .tokenRevocationEndpoint("/oauth/revoke")
                .jwkSetEndpoint("/oauth2/jwks")
                .oidcUserInfoEndpoint("/oauth/userinfo")
                .build();
    }

    // =========================================================
    // Password Encoder — BCrypt strength 12
    // =========================================================

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    // =========================================================
    // Authentication Manager (used by /auth/login)
    // =========================================================

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
