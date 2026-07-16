package ai.conduit.gateway.infrastructure.outbound;

import java.net.URI;

/**
 * Derives a stable {@link OutboundGate} key from a fully-resolved URL — its {@code scheme://authority}
 * (host + port), stripped of path/query. Two coverage endpoints on the same service
 * ({@code /discover}, {@code /resolve}) collapse to ONE key, while two distinct coverage services get
 * two keys, so one slow service cannot drain the other's permits.
 *
 * <p>World B: the key is the manifest-resolved coverage base-URL (or the config-resolved Cerbos
 * base-URL) — never a domain name. No domain literal ever enters the gateway through this seam.
 */
public final class OutboundKeys {

    private OutboundKeys() {}

    /**
     * {@code scheme://authority} of {@code url}, or the raw string when it cannot be parsed as a
     * hierarchical URI (still a stable, non-domain key — the fallback is deterministic).
     */
    public static String baseUrl(String url) {
        if (url == null || url.isBlank()) return "unknown";
        try {
            URI u = URI.create(url.strip());
            if (u.getScheme() != null && u.getAuthority() != null) {
                return u.getScheme() + "://" + u.getAuthority();
            }
        } catch (IllegalArgumentException ignored) {
            // fall through to the raw-string fallback
        }
        return url.strip();
    }
}
