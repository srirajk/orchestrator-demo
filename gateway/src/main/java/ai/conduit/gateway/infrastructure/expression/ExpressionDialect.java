package ai.conduit.gateway.infrastructure.expression;

/**
 * The manifest expression dialect this build speaks — the cross-container skew gate.
 *
 * <p>Manifest expressions ({@code select}, {@code map.over}, {@code condition}, figure {@code path}, …)
 * are a language, and the registry-service that ingests them and the gateway that evaluates them must
 * agree on which one. The registry stamps the routing index with {@link #CURRENT} at ingest; the
 * gateway refuses to start unless the stamp equals the dialect its own engine speaks. A mixed pairing —
 * a gateway on one dialect reading an index a registry built in another — is refused loudly rather than
 * silently mis-evaluating every expression.
 *
 * <p>This constant is flipped in a single place as the CEL migration lands: {@code jmespath} while the
 * JMESPath engine is on the request path, {@code cel-v1} once the call sites and manifests are migrated.
 * Any cross-commit pairing therefore fails the readiness check with a named mismatch.
 */
public final class ExpressionDialect {

    /** The dialect this build's expression engine speaks and the registry stamps at ingest. */
    public static final String CURRENT = "jmespath";

    private ExpressionDialect() {}
}
