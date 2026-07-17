package com.openwolf.iam.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2DeviceCode;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.OAuth2UserCode;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis-backed Spring Authorization Server authorization store — tenant-qualified (Axiom Story A3).
 *
 * <p>Spring Authorization Server's default store is in-memory. That makes refresh tokens fragile
 * across IAM restarts and concurrent token rotation because the BFF can still hold a session whose
 * authorization object no longer exists in the issuer. Redis keeps that short-lived OAuth2 state
 * outside the JVM while still letting tokens expire naturally.</p>
 *
 * <h2>Key space — {@code {context}:{tenant}:...}</h2>
 * The bounded-context segment ({@code iam}) and the tenant segment are independent and both enforced.
 * The authorization record lives at <b>{@code iam:t:{tenant}:oauth2:authorization:{id}}</b>. The
 * tenant segment is never caller-supplied: it is read from the authorization's own minted token
 * claims ({@code tenant_id}), which IAM issues under the canonical grammar.
 *
 * <h2>The one permitted locator</h2>
 * {@link #findByToken} starts with only an opaque token value, so it cannot know the tenant. The one
 * permitted exception to "everything is tenant-qualified" is a minimal locator
 * <b>{@code iam:oauth2:token:{sha256(token)} → {tenant, authorizationId}}</b>. It carries NO roles,
 * claims, or payload — only the coordinates needed to reach the tenant-qualified record, which is
 * then read and whose stored tenant is verified to equal the locator's tenant before returning. A
 * tampered locator (wrong tenant or id) therefore resolves to nothing.
 */
public class RedisOAuth2AuthorizationService implements OAuth2AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(RedisOAuth2AuthorizationService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Duration FALLBACK_TTL = Duration.ofDays(1);
    private static final Duration EXPIRY_GRACE = Duration.ofMinutes(1);

    private final RedisOperations<String, OAuth2Authorization> authorizationRedis;
    private final StringRedisTemplate stringRedis;
    private final Clock clock;

    /** Bounded-context segment, e.g. {@code iam} (the part of the prefix before the tenant). */
    private final String contextSegment;
    /** Service segment inside the context, e.g. {@code oauth2}. */
    private final String serviceSegment;
    /** The tenant claim the record is keyed by, e.g. {@code tenant_id}. */
    private final String tenantClaim;
    /** The tenant used when an authorization carries no tenant claim (single-tenant/demo). */
    private final String defaultTenant;

    public RedisOAuth2AuthorizationService(
            RedisOperations<String, OAuth2Authorization> authorizationRedis,
            StringRedisTemplate stringRedis,
            String keyPrefix,
            String tenantClaim,
            String defaultTenant) {
        this(authorizationRedis, stringRedis, keyPrefix, tenantClaim, defaultTenant, Clock.systemUTC());
    }

    RedisOAuth2AuthorizationService(
            RedisOperations<String, OAuth2Authorization> authorizationRedis,
            StringRedisTemplate stringRedis,
            String keyPrefix,
            String tenantClaim,
            String defaultTenant,
            Clock clock) {
        Assert.hasText(keyPrefix, "keyPrefix cannot be empty");
        Assert.hasText(tenantClaim, "tenantClaim cannot be empty");
        Assert.hasText(defaultTenant, "defaultTenant cannot be empty");
        this.authorizationRedis = authorizationRedis;
        this.stringRedis = stringRedis;
        this.clock = clock;
        this.tenantClaim = tenantClaim;
        this.defaultTenant = defaultTenant.trim();

        // Split "iam:oauth2" → context "iam", service "oauth2". The context segment sits BEFORE the
        // tenant segment (iam:t:{tenant}:oauth2:...); the service segment sits after it.
        int firstColon = keyPrefix.indexOf(':');
        if (firstColon < 0) {
            this.contextSegment = keyPrefix;
            this.serviceSegment = keyPrefix;
        } else {
            this.contextSegment = keyPrefix.substring(0, firstColon);
            this.serviceSegment = keyPrefix.substring(firstColon + 1);
        }
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");
        String tenant = tenantOf(authorization);

        OAuth2Authorization existing = readRecord(tenant, authorization.getId());
        if (existing != null) {
            deleteLocators(existing, tenant);
        }

        Duration ttl = ttlFor(authorization);
        authorizationRedis.opsForValue().set(authorizationKey(tenant, authorization.getId()), authorization, ttl);
        saveLocators(authorization, tenant, ttl);
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");
        String tenant = tenantOf(authorization);

        OAuth2Authorization persisted = readRecord(tenant, authorization.getId());
        deleteLocators(persisted != null ? persisted : authorization, tenant);
        authorizationRedis.delete(authorizationKey(tenant, authorization.getId()));
    }

    @Override
    public OAuth2Authorization findById(String id) {
        Assert.hasText(id, "id cannot be empty");
        // A bare id carries no tenant. The tenant-safe path is findByToken (via the locator); a
        // by-id lookup resolves within the default tenant, which is all the single-tenant demo and
        // the SAS consent flow need. Real-tenant reads on the hot path go through findByToken.
        return readRecord(defaultTenant, id);
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        Assert.hasText(token, "token cannot be empty");

        String locatorJson = stringRedis.opsForValue().get(tokenLocatorKey(token));
        if (locatorJson == null) {
            return null;
        }
        Locator locator = parseLocator(locatorJson);
        if (locator == null || locator.tenant() == null || locator.authorizationId() == null) {
            return null;
        }

        OAuth2Authorization record = readRecord(locator.tenant(), locator.authorizationId());
        if (record == null) {
            return null;
        }
        // Verify the record actually belongs to the tenant the locator named. A tampered locator
        // pointing a token at another tenant's record is refused here.
        String recordTenant = tenantOf(record);
        if (!recordTenant.equals(locator.tenant())) {
            log.warn("OAuth locator tenant '{}' disagrees with record tenant '{}' for authorization {} — refusing",
                    locator.tenant(), recordTenant, locator.authorizationId());
            return null;
        }
        return record;
    }

    // ── tenant resolution ───────────────────────────────────────────────────────────────────────

    /**
     * The tenant an authorization belongs to, read from its minted token claims. Prefers the access
     * token, then the id token; falls back to the configured default when no claim is present (the
     * intermediate code-grant save before any token exists, and the single-tenant demo).
     */
    private String tenantOf(OAuth2Authorization authorization) {
        String fromAccess = claim(authorization.getToken(OAuth2AccessToken.class));
        if (fromAccess != null) return fromAccess;
        String fromId = claim(authorization.getToken(OidcIdToken.class));
        if (fromId != null) return fromId;
        return defaultTenant;
    }

    private String claim(OAuth2Authorization.Token<?> token) {
        if (token == null) return null;
        Map<String, Object> claims = token.getClaims();
        if (claims == null) return null;
        Object value = claims.get(tenantClaim);
        if (value == null) return null;
        String tenant = value.toString().trim();
        return tenant.isEmpty() ? null : tenant;
    }

    // ── record + locator persistence ────────────────────────────────────────────────────────────

    private OAuth2Authorization readRecord(String tenant, String id) {
        return authorizationRedis.opsForValue().get(authorizationKey(tenant, id));
    }

    private void saveLocators(OAuth2Authorization authorization, String tenant, Duration ttl) {
        String value = writeLocator(new Locator(tenant, authorization.getId()));
        for (String tokenValue : locatorTokenValues(authorization)) {
            stringRedis.opsForValue().set(tokenLocatorKey(tokenValue), value, ttl);
        }
    }

    private void deleteLocators(OAuth2Authorization authorization, String tenant) {
        Set<String> keys = new LinkedHashSet<>();
        for (String tokenValue : locatorTokenValues(authorization)) {
            keys.add(tokenLocatorKey(tokenValue));
        }
        if (!keys.isEmpty()) {
            stringRedis.delete(keys);
        }
    }

    /** Every opaque value that must resolve back to this authorization: state + all issued tokens. */
    private List<String> locatorTokenValues(OAuth2Authorization authorization) {
        List<String> values = new ArrayList<>();

        String state = authorization.getAttribute(OAuth2ParameterNames.STATE);
        if (state != null && !state.isBlank()) {
            values.add(state);
        }
        addTokenValue(values, authorization, OAuth2AuthorizationCode.class);
        addTokenValue(values, authorization, OAuth2AccessToken.class);
        addTokenValue(values, authorization, OidcIdToken.class);
        addTokenValue(values, authorization, OAuth2RefreshToken.class);
        addTokenValue(values, authorization, OAuth2DeviceCode.class);
        addTokenValue(values, authorization, OAuth2UserCode.class);
        return values;
    }

    private <T extends OAuth2Token> void addTokenValue(
            List<String> values, OAuth2Authorization authorization, Class<T> tokenClass) {
        OAuth2Authorization.Token<T> token = authorization.getToken(tokenClass);
        if (token != null) {
            values.add(token.getToken().getTokenValue());
        }
    }

    // ── key construction ────────────────────────────────────────────────────────────────────────

    /** {@code iam:t:{tenant}:oauth2:authorization:{id}} — the tenant-qualified record. */
    private String authorizationKey(String tenant, String id) {
        return contextSegment + ":t:" + tenant + ":" + serviceSegment + ":authorization:" + id;
    }

    /** {@code iam:oauth2:token:{sha256(token)}} — the minimal, tenant-agnostic locator. */
    private String tokenLocatorKey(String tokenValue) {
        return contextSegment + ":" + serviceSegment + ":token:" + sha256(tokenValue);
    }

    // ── TTL ─────────────────────────────────────────────────────────────────────────────────────

    private Duration ttlFor(OAuth2Authorization authorization) {
        Instant latestExpiry = latestExpiry(authorization);
        if (latestExpiry == null) {
            return FALLBACK_TTL;
        }
        Duration ttl = Duration.between(clock.instant(), latestExpiry.plus(EXPIRY_GRACE));
        if (ttl.isNegative() || ttl.isZero()) {
            return Duration.ofSeconds(1);
        }
        return ttl;
    }

    private Instant latestExpiry(OAuth2Authorization authorization) {
        Instant latest = null;
        latest = later(latest, expiresAt(authorization, OAuth2AuthorizationCode.class));
        latest = later(latest, expiresAt(authorization, OAuth2AccessToken.class));
        latest = later(latest, expiresAt(authorization, OidcIdToken.class));
        latest = later(latest, expiresAt(authorization, OAuth2RefreshToken.class));
        latest = later(latest, expiresAt(authorization, OAuth2DeviceCode.class));
        latest = later(latest, expiresAt(authorization, OAuth2UserCode.class));
        return latest;
    }

    private <T extends OAuth2Token> Instant expiresAt(OAuth2Authorization authorization, Class<T> tokenClass) {
        OAuth2Authorization.Token<T> token = authorization.getToken(tokenClass);
        return token == null ? null : token.getToken().getExpiresAt();
    }

    private Instant later(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    // ── locator (de)serialization — {tenant, authorizationId}, no payload ────────────────────────

    private String writeLocator(Locator locator) {
        try {
            return JSON.writeValueAsString(locator);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize OAuth token locator", ex);
        }
    }

    private Locator parseLocator(String json) {
        try {
            return JSON.readValue(json, Locator.class);
        } catch (JsonProcessingException ex) {
            log.warn("Unreadable OAuth token locator — treating as absent: {}", ex.getOriginalMessage());
            return null;
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** The locator payload — coordinates only, deliberately no roles/claims/token material. */
    record Locator(
            @JsonProperty("tenant") String tenant,
            @JsonProperty("authorizationId") String authorizationId) {

        @JsonCreator
        Locator {
        }
    }
}
