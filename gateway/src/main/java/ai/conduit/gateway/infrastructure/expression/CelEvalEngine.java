package ai.conduit.gateway.infrastructure.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.NullValue;
import org.projectnessie.cel.Env;
import org.projectnessie.cel.EnvOption;
import org.projectnessie.cel.Program;
import org.projectnessie.cel.checker.Decls;
import org.projectnessie.cel.common.types.Err;
import org.projectnessie.cel.common.types.ref.Val;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link EvalEngine} backed by <a href="https://github.com/projectnessie/cel-java">cel-java</a>
 * (org.projectnessie.cel), replacing the archived, Jackson-2-pinned {@code io.burt:jmespath-jackson}.
 * cel-tools has <b>no Jackson dependency</b> — that decoupling is the whole point of this migration
 * (it removes the last blocker to Spring Boot 4).
 *
 * <h2>Checked, single-variable environments</h2>
 * One CEL {@link Env} is built per {@link RootVar}, each declaring <b>only</b> that root's variable as
 * a dynamic value ({@code Decls.Dyn}). Because compilation is declaration-checked, an expression that
 * references any other identifier — a bare legacy-JMESPath token like {@code failed}, or {@code output.x}
 * compiled as {@link RootVar#INPUT} — is an undeclared reference and fails {@link #compile} loudly. This
 * is how the migration refuses to admit un-migrated (JMESPath-dialect) manifests at ingest.
 *
 * <h2>Error taxonomy (why not an error code)</h2>
 * The spec called for classifying on a structured error <i>code</i>. cel-java 0.6.1 does not expose one:
 * evaluation failures surface either as an {@link Err} {@code Val} or as a thrown {@link RuntimeException},
 * and the only discriminator the library provides is a stable, factory-produced message prefix
 * (<i>"no such key"</i>, <i>"no such attribute"</i>, <i>"no such field"</i>, <i>"index out of bounds"</i>
 * for recoverable navigation failures; <i>"no such overload"</i>, <i>"divide by zero"</i>, … for genuine
 * contract breaks). We therefore classify on cel-java's own error taxonomy, centralized in one method
 * ({@link #recoverable}). This preserves the design intent — a type/overload error is NEVER masked as a
 * missing field — using the structured signal the library actually gives. In practice the codemod guards
 * every production field access with {@code has(...)}, so recoverable classification is exercised only by
 * the residual unguarded sites (e.g. {@code map.over}) and the tests.
 *
 * <h2>Cache</h2>
 * Compiled expressions are memoized in a plain {@link ConcurrentHashMap} keyed by (source, root),
 * unbounded by design: every expression originates in an ingest-validated manifest (cardinality ~25).
 * Compilation and evaluation are pure CPU with no blocking — virtual-thread safe.
 */
@Component
public class CelEvalEngine implements EvalEngine {

    private final ObjectMapper mapper;
    private final Map<RootVar, Env> envs = new EnumMap<>(RootVar.class);
    private final Map<CacheKey, CompiledExpr> cache = new ConcurrentHashMap<>();

    public CelEvalEngine(ObjectMapper mapper) {
        this.mapper = mapper;
        for (RootVar var : RootVar.values()) {
            // newEnv already applies the CEL standard library + macros (has, map, filter, …).
            // Declaring ONLY this root's variable makes any other identifier an undeclared reference.
            envs.put(var, Env.newEnv(EnvOption.declarations(Decls.newVar(var.varName(), Decls.Dyn))));
        }
    }

    @Override
    public CompiledExpr compile(String source, RootVar var) {
        return cache.computeIfAbsent(new CacheKey(source, var), k -> doCompile(k.source(), k.var()));
    }

    private CompiledExpr doCompile(String source, RootVar var) {
        if (source == null) {
            throw new ExpressionCompileException("<null>", var, "expression source is null");
        }
        Env env = envs.get(var);
        Env.AstIssuesTuple compiled = env.compile(source);
        if (compiled.hasIssues()) {
            throw new ExpressionCompileException(source, var,
                    compiled.getIssues().toString().replace("\n", " "));
        }
        Program program = env.program(compiled.getAst());
        return new CompiledExpr(source, var, program);
    }

    @Override
    public JsonNode eval(CompiledExpr compiled, JsonNode root, Mode mode) {
        Object bound = (root == null) ? null : mapper.convertValue(root, Object.class);
        Map<String, Object> activation = new java.util.HashMap<>(1);
        activation.put(compiled.var().varName(), bound);

        Val val;
        try {
            val = compiled.program().eval(activation).getVal();
        } catch (RuntimeException thrown) {
            // Some navigation failures (e.g. index-out-of-bounds) are thrown, not returned as Err vals.
            return handleError(compiled, mode, messageOf(thrown), thrown);
        }
        if (Err.isError(val)) {
            Throwable cause = (val instanceof Err err && err.hasCause()) ? err.getCause() : null;
            return handleError(compiled, mode, String.valueOf(val.value()), cause);
        }
        return toJson(val);
    }

    /** Mode-aware error handling: strict always throws; lenient absorbs only recoverable navigation errors. */
    private JsonNode handleError(CompiledExpr compiled, Mode mode, String message, Throwable cause) {
        if (mode == Mode.LENIENT && recoverable(message)) {
            return JsonNodeFactory.instance.missingNode();
        }
        throw new ExpressionEvalException(compiled.source(), message, cause);
    }

    /**
     * cel-java's recoverable <i>navigation</i>-failure taxonomy — the set that JMESPath resolves to a
     * plain {@code null} rather than an error, so lenient CEL must too to preserve byte-parity:
     * <ul>
     *   <li>a missing map key / attribute / field ({@code "no such key"}, …);</li>
     *   <li>an out-of-bounds index ({@code "index out of bounds"});</li>
     *   <li>selecting a field on a null intermediate ({@code "invalid type for field selection"});</li>
     *   <li>selecting a field/index on a scalar ({@code "no such overload: string.ref-resolve(*)"}).</li>
     * </ul>
     * Everything else is a genuine <i>computation</i> break and is NOT recoverable — notably an
     * arithmetic/comparison overload mismatch ({@code "no such overload: string.add(int)"}, {@code
     * "…less…"}) and {@code "divide by zero"}. This is the structural line the spec asked for
     * (navigation vs. computation), drawn on cel-java's own error taxonomy since 0.6.1 exposes no code.
     */
    private static boolean recoverable(String message) {
        if (message == null) return false;
        String m = message.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("no such key")
                || m.contains("no such attribute")
                || m.contains("no such field")
                || m.contains("index out of bounds")
                || m.contains("invalid type for field selection")) {
            return true;
        }
        // Field/index access on a scalar is a navigation failure (JMESPath → null), NOT an arithmetic
        // overload mismatch. cel-java marks the former with the "ref-resolve" overload id.
        return m.contains("no such overload") && m.contains("ref-resolve");
    }

    private static String messageOf(Throwable t) {
        String m = t.getMessage();
        return m != null ? m : t.getClass().getSimpleName();
    }

    // ── CEL Val → Jackson JsonNode ───────────────────────────────────────────────────────────────
    // convertToNative(Object) gives a native Java tree, but leaves protobuf NullValue markers on null
    // leaves and mixes Object[] / List for arrays — so we normalize recursively into JsonNodes.

    private JsonNode toJson(Val val) {
        return normalize(val.convertToNative(Object.class));
    }

    private JsonNode normalize(Object o) {
        JsonNodeFactory nf = JsonNodeFactory.instance;
        if (o == null || o instanceof NullValue) {
            return nf.nullNode();
        }
        if (o instanceof JsonNode node) {
            return node;
        }
        if (o instanceof Map<?, ?> map) {
            ObjectNode out = nf.objectNode();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.set(String.valueOf(e.getKey()), normalize(e.getValue()));
            }
            return out;
        }
        if (o instanceof Object[] arr) {
            ArrayNode out = nf.arrayNode();
            for (Object e : arr) out.add(normalize(e));
            return out;
        }
        if (o instanceof Iterable<?> it) {
            ArrayNode out = nf.arrayNode();
            for (Object e : it) out.add(normalize(e));
            return out;
        }
        // Scalars (Long, Double, String, Boolean, byte[], BigDecimal, …) marshal cleanly.
        return mapper.valueToTree(o);
    }

    private record CacheKey(String source, RootVar var) {}
}
