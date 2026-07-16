package ai.conduit.gateway.infrastructure.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Contract tests for {@link CelEvalEngine} — pure CPU, no Spring/Redis. Verifies the checked-compile
 * guardrail, the lenient-vs-strict error taxonomy, and the compile cache.
 */
class CelEvalEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final CelEvalEngine engine = new CelEvalEngine(MAPPER);

    private JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── lenient vs strict on a missing field ─────────────────────────────────────────────────────

    @Test
    @DisplayName("lenient: a missing field yields MissingNode (degrades like JMESPath null)")
    void missingFieldReturnsMissingNodeLenient() {
        CompiledExpr e = engine.compile("input.failed", RootVar.INPUT);
        JsonNode out = engine.eval(e, json("{}"), EvalEngine.Mode.LENIENT);
        assertThat(out.isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("strict: a missing field rethrows (ingest validation is not lenient)")
    void strictModeRethrowsMissingField() {
        CompiledExpr e = engine.compile("input.failed", RootVar.INPUT);
        assertThatThrownBy(() -> engine.eval(e, json("{}"), EvalEngine.Mode.STRICT))
                .isInstanceOf(ExpressionEvalException.class);
    }

    // ── type errors are NOT missing fields ───────────────────────────────────────────────────────

    @Test
    @DisplayName("lenient: a type/overload error rethrows — it is never masked as a missing field")
    void typeErrorThrowsNotMissing() {
        CompiledExpr e = engine.compile("input.x + 1", RootVar.INPUT);
        assertThatThrownBy(() -> engine.eval(e, json("{\"x\":\"str\"}"), EvalEngine.Mode.LENIENT))
                .isInstanceOf(ExpressionEvalException.class)
                .hasMessageContaining("overload");
    }

    @Test
    @DisplayName("classification keys on cel-java's structured taxonomy: navigation error absorbed, type error not")
    void errorClassificationUsesStructuredCode() {
        // Same LENIENT mode, same 'this is an error' surface — but classified differently by cel-java's
        // navigation-vs-overload taxonomy, not by 'is it an error'. Missing key → MissingNode; type
        // mismatch → rethrow.
        CompiledExpr missing = engine.compile("input.absent", RootVar.INPUT);
        assertThat(engine.eval(missing, json("{}"), EvalEngine.Mode.LENIENT).isMissingNode()).isTrue();

        CompiledExpr typeErr = engine.compile("input.a < input.b", RootVar.INPUT);
        assertThatThrownBy(() -> engine.eval(typeErr, json("{\"a\":1,\"b\":\"two\"}"), EvalEngine.Mode.LENIENT))
                .isInstanceOf(ExpressionEvalException.class);
    }

    // ── checked-compile guardrails (ingest-time loud failure) ────────────────────────────────────

    @Test
    @DisplayName("a bare legacy-JMESPath identifier is an undeclared reference → compile fails loud")
    void compileErrorAtIngestFailsLoud() {
        assertThatThrownBy(() -> engine.compile("failed", RootVar.INPUT))
                .isInstanceOf(ExpressionCompileException.class)
                .hasMessageContaining("failed");
    }

    @Test
    @DisplayName("a wrong-root reference (output.x compiled as INPUT) fails the checked compile")
    void wrongRootVarFailsCompile() {
        assertThatThrownBy(() -> engine.compile("output.x", RootVar.INPUT))
                .isInstanceOf(ExpressionCompileException.class);
        // …but the same expression compiles cleanly against its correct root.
        assertThatCode(() -> engine.compile("output.x", RootVar.OUTPUT)).doesNotThrowAnyException();
    }

    // ── guarded expression parity shape (key-present-null) ───────────────────────────────────────

    @Test
    @DisplayName("guarded multiselect-hash: absent field → key present with null (JMESPath shape)")
    void guardedHashProducesKeyPresentNull() {
        CompiledExpr e = engine.compile("{'failed': has(input.failed) ? input.failed : null}", RootVar.INPUT);
        JsonNode out = engine.eval(e, json("{}"), EvalEngine.Mode.LENIENT);
        assertThat(out.has("failed")).isTrue();
        assertThat(out.get("failed").isNull()).isTrue();
    }

    // ── compile cache: exactly one compile per (source, root) under concurrency ───────────────────

    @Test
    @DisplayName("compile cache: 10k concurrent evals across 32 VTs compile each source exactly once")
    void compileCacheUnderConcurrentEval() throws Exception {
        String src = "{'a': has(input.a) ? input.a : null, 'b': has(input.b) ? input.b : null}";
        Set<CompiledExpr> distinct = ConcurrentHashMap.newKeySet();
        AtomicInteger evals = new AtomicInteger();
        JsonNode fixture = json("{\"a\":1,\"b\":2}");

        int threads = 32, perThread = 10_000 / threads;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    for (int i = 0; i < perThread; i++) {
                        CompiledExpr c = engine.compile(src, RootVar.INPUT);
                        distinct.add(c);
                        engine.eval(c, fixture, EvalEngine.Mode.LENIENT);
                        evals.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (var f : futures) f.get(30, TimeUnit.SECONDS);
        }
        // computeIfAbsent yields one shared CompiledExpr for the key → one compilation.
        assertThat(distinct).hasSize(1);
        assertThat(evals.get()).isEqualTo(threads * perThread);
    }
}
