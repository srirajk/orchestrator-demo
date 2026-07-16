package ai.conduit.gateway.infrastructure.expression;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The manifest-expression seam. All seven manifest-declared expression sites (edge {@code select},
 * {@code map.over}, {@code map.item_select}, node {@code condition}, figure {@code path},
 * produced-entity {@code select}, and the per-item id selector inside a produced-entity prune) run
 * through this interface — the gateway holds no other expression evaluator on the request path.
 *
 * <p>Expressions are <b>compiled once</b> ({@link #compile}) and evaluated many times ({@link #eval}).
 * Compilation is declaration-checked against the {@link RootVar} the call site declares, so a wrong-root
 * reference or a legacy dialect string fails loudly at ingest rather than silently at runtime.
 *
 * <h2>Modes</h2>
 * <ul>
 *   <li>{@link Mode#LENIENT} — the request path. A <i>recoverable</i> evaluation error (a missing
 *       field/key/attribute, an out-of-bounds index) yields a {@code MissingNode} so the call site can
 *       degrade exactly as it did under JMESPath (empty array, skipped figure, null projection).
 *       A non-recoverable error (a type/overload mismatch, divide-by-zero) still rethrows —
 *       lenience never masks a genuine contract break into a wrong number.</li>
 *   <li>{@link Mode#STRICT} — ingest validation only. <i>Every</i> evaluation error rethrows, so
 *       {@code SelectContractValidator} can prove an expression is safe before the manifest is
 *       admitted.</li>
 * </ul>
 *
 * <p><b>World B:</b> the engine interprets only the manifest-supplied expression string and the
 * caller-declared {@link RootVar}. It embeds no domain vocabulary.
 *
 * <p><b>PayloadHandle:</b> the signature is pure {@link JsonNode} in / {@link JsonNode} out — the
 * engine never performs I/O. When claim-check lands, {@code Ref} hydration happens caller-side before
 * {@code eval} is called.
 */
public interface EvalEngine {

    /** Lenient (request path) vs. strict (ingest validation) evaluation. */
    enum Mode { LENIENT, STRICT }

    /**
     * Declaration-check and compile {@code source} against the environment for {@code var}.
     *
     * @throws ExpressionCompileException if the expression fails to parse or references any identifier
     *         other than {@code var}'s bound name (an undeclared reference — e.g. a bare legacy
     *         JMESPath identifier, or a wrong-root reference).
     */
    CompiledExpr compile(String source, RootVar var) throws ExpressionCompileException;

    /**
     * Evaluate {@code compiled} against {@code root} (bound to its declared variable).
     *
     * @throws ExpressionEvalException in {@link Mode#STRICT} on any error; in {@link Mode#LENIENT} only
     *         on a non-recoverable error. A recoverable error under {@link Mode#LENIENT} returns a
     *         {@code MissingNode} instead of throwing.
     */
    JsonNode eval(CompiledExpr compiled, JsonNode root, Mode mode) throws ExpressionEvalException;
}
