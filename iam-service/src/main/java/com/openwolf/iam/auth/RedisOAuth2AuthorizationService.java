package com.openwolf.iam.auth;

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
import java.util.Set;

/**
 * Redis-backed Spring Authorization Server authorization store.
 *
 * <p>Spring Authorization Server's default store is in-memory. That makes refresh tokens
 * fragile across IAM restarts and concurrent token rotation because the BFF can still hold
 * a session whose authorization object no longer exists in the issuer. Redis keeps that
 * short-lived OAuth2 state outside the JVM while still letting tokens expire naturally.</p>
 */
public class RedisOAuth2AuthorizationService implements OAuth2AuthorizationService {

    private static final Duration FALLBACK_TTL = Duration.ofDays(1);
    private static final Duration EXPIRY_GRACE = Duration.ofMinutes(1);

    private static final List<String> TOKEN_LOOKUP_ORDER = List.of(
            OAuth2ParameterNames.STATE,
            OAuth2ParameterNames.CODE,
            OAuth2ParameterNames.ACCESS_TOKEN,
            OidcParameterNames.ID_TOKEN,
            OAuth2ParameterNames.REFRESH_TOKEN,
            OAuth2ParameterNames.DEVICE_CODE,
            OAuth2ParameterNames.USER_CODE
    );

    private final RedisOperations<String, OAuth2Authorization> authorizationRedis;
    private final StringRedisTemplate stringRedis;
    private final Clock clock;
    private final String authorizationKeyPrefix;
    private final String tokenIndexKeyPrefix;

    public RedisOAuth2AuthorizationService(
            RedisOperations<String, OAuth2Authorization> authorizationRedis,
            StringRedisTemplate stringRedis,
            String keyPrefix) {
        this(authorizationRedis, stringRedis, keyPrefix, Clock.systemUTC());
    }

    RedisOAuth2AuthorizationService(
            RedisOperations<String, OAuth2Authorization> authorizationRedis,
            StringRedisTemplate stringRedis,
            String keyPrefix,
            Clock clock) {
        Assert.hasText(keyPrefix, "keyPrefix cannot be empty");
        this.authorizationRedis = authorizationRedis;
        this.stringRedis = stringRedis;
        this.clock = clock;
        this.authorizationKeyPrefix = keyPrefix + ":authorization:";
        this.tokenIndexKeyPrefix = keyPrefix + ":authorization-token:";
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");

        OAuth2Authorization existing = findById(authorization.getId());
        if (existing != null) {
            deleteTokenIndexes(existing);
        }

        Duration ttl = ttlFor(authorization);
        authorizationRedis.opsForValue().set(authorizationKey(authorization.getId()), authorization, ttl);
        saveTokenIndexes(authorization, ttl);
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");

        OAuth2Authorization persisted = findById(authorization.getId());
        deleteTokenIndexes(persisted != null ? persisted : authorization);
        authorizationRedis.delete(authorizationKey(authorization.getId()));
    }

    @Override
    public OAuth2Authorization findById(String id) {
        Assert.hasText(id, "id cannot be empty");
        return authorizationRedis.opsForValue().get(authorizationKey(id));
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        Assert.hasText(token, "token cannot be empty");

        if (tokenType != null) {
            return findByIndexedToken(tokenType.getValue(), token);
        }

        for (String tokenTypeValue : TOKEN_LOOKUP_ORDER) {
            OAuth2Authorization authorization = findByIndexedToken(tokenTypeValue, token);
            if (authorization != null) {
                return authorization;
            }
        }
        return null;
    }

    private OAuth2Authorization findByIndexedToken(String tokenType, String tokenValue) {
        String authorizationId = stringRedis.opsForValue().get(tokenIndexKey(tokenType, tokenValue));
        return authorizationId == null ? null : findById(authorizationId);
    }

    private void saveTokenIndexes(OAuth2Authorization authorization, Duration ttl) {
        for (TokenIndex tokenIndex : tokenIndexes(authorization)) {
            stringRedis.opsForValue().set(tokenIndex.key(), authorization.getId(), ttl);
        }
    }

    private void deleteTokenIndexes(OAuth2Authorization authorization) {
        Set<String> keys = new LinkedHashSet<>();
        for (TokenIndex tokenIndex : tokenIndexes(authorization)) {
            keys.add(tokenIndex.key());
        }
        if (!keys.isEmpty()) {
            stringRedis.delete(keys);
        }
    }

    private List<TokenIndex> tokenIndexes(OAuth2Authorization authorization) {
        List<TokenIndex> indexes = new ArrayList<>();

        String state = authorization.getAttribute(OAuth2ParameterNames.STATE);
        if (state != null && !state.isBlank()) {
            indexes.add(new TokenIndex(tokenIndexKey(OAuth2ParameterNames.STATE, state)));
        }

        addTokenIndex(indexes, authorization, OAuth2ParameterNames.CODE, OAuth2AuthorizationCode.class);
        addTokenIndex(indexes, authorization, OAuth2ParameterNames.ACCESS_TOKEN, OAuth2AccessToken.class);
        addTokenIndex(indexes, authorization, OidcParameterNames.ID_TOKEN, OidcIdToken.class);
        addTokenIndex(indexes, authorization, OAuth2ParameterNames.REFRESH_TOKEN, OAuth2RefreshToken.class);
        addTokenIndex(indexes, authorization, OAuth2ParameterNames.DEVICE_CODE, OAuth2DeviceCode.class);
        addTokenIndex(indexes, authorization, OAuth2ParameterNames.USER_CODE, OAuth2UserCode.class);

        return indexes;
    }

    private <T extends OAuth2Token> void addTokenIndex(
            List<TokenIndex> indexes,
            OAuth2Authorization authorization,
            String tokenType,
            Class<T> tokenClass) {
        OAuth2Authorization.Token<T> token = authorization.getToken(tokenClass);
        if (token != null) {
            indexes.add(new TokenIndex(tokenIndexKey(tokenType, token.getToken().getTokenValue())));
        }
    }

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

    private String authorizationKey(String id) {
        return authorizationKeyPrefix + id;
    }

    private String tokenIndexKey(String tokenType, String tokenValue) {
        return tokenIndexKeyPrefix + tokenType + ":" + sha256(tokenValue);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private record TokenIndex(String key) {
    }
}
