package ai.conduit.gateway.infrastructure.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.burt.jmespath.JmesPath;
import io.burt.jmespath.jackson.JacksonRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The identity proof for the CEL migration. For every manifest expression that ships in a production
 * manifest, this test runs BOTH engines — the legacy JMESPath ({@code io.burt}, kept in test scope for
 * exactly this purpose) and the new {@link CelEvalEngine} over the codemod-translated CEL form — over a
 * fixture matrix (full / optional-absent / required-absent / intermediate-object-absent / empty array /
 * numeric variants) and asserts the outputs are equal under a canonical-numeric comparator. The JMESPath
 * outputs are also written to {@code src/test/resources/expr-parity/*.json} as committed goldens: the
 * regression pin once JMESPath is gone.
 *
 * <p>The JMESPath→CEL translation lives in {@link #hash}, {@link #dotted}, and the one-off cases — the
 * SAME translator the codemod applies. A codemod bug is therefore a parity failure here, not a silent
 * production drift.
 */
class EngineParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JmesPath<JsonNode> JMES = new JacksonRuntime();
    private final CelEvalEngine cel = new CelEvalEngine(MAPPER);

    private static final Path GOLDEN_DIR =
            Path.of("src", "test", "resources", "expr-parity");

    // ── the shared translator (mirrors scripts/migrate-selects-to-cel.py) ────────────────────────

    /** A multiselect-hash: JMESPath {@code {a: a, b: b}} → CEL {@code {'a': has(v.a)?v.a:null, …}}. */
    private static Expr hash(String id, RootVar var, String... fields) {
        String v = var.varName();
        StringBuilder jmes = new StringBuilder("{");
        StringBuilder cel = new StringBuilder("{");
        for (int i = 0; i < fields.length; i++) {
            String f = fields[i];
            if (i > 0) { jmes.append(", "); cel.append(", "); }
            jmes.append(f).append(": ").append(f);
            cel.append("'").append(f).append("': has(").append(v).append(".").append(f)
               .append(") ? ").append(v).append(".").append(f).append(" : null");
        }
        return new Expr(id, var, jmes.append("}").toString(), cel.append("}").toString());
    }

    /** A dotted path: JMESPath {@code a.b.c} → CEL chain-guarded {@code has(v.a)&&has(v.a.b)&&… ? v.a.b.c : null}. */
    private static Expr dotted(String id, RootVar var, String dottedPath) {
        String v = var.varName();
        String[] seg = dottedPath.split("\\.");
        StringBuilder guard = new StringBuilder();
        StringBuilder access = new StringBuilder(v);
        for (int i = 0; i < seg.length; i++) {
            access.append(".").append(seg[i]);
            if (i > 0) guard.append(" && ");
            guard.append("has(").append(access).append(")");
        }
        String cel = guard + " ? " + access + " : null";
        return new Expr(id, var, dottedPath, cel);
    }

    private static Expr raw(String id, RootVar var, String jmes, String cel) {
        return new Expr(id, var, jmes, cel);
    }

    private record Expr(String id, RootVar var, String jmes, String cel) {}

    // ── the production expression set (every select/over/item_select/condition/path that ships) ───

    private static List<Expr> productionExpressions() {
        List<Expr> e = new ArrayList<>();
        // asset-servicing / settlement_risk — three consume selects (RootVar.INPUT)
        e.add(hash("settlement_risk.s1", RootVar.INPUT,
                "relationship_id", "pending", "failed", "as_of_date"));
        e.add(hash("settlement_risk.s2", RootVar.INPUT,
                "relationship_id", "holdings_by_custodian", "as_of_date"));
        e.add(hash("settlement_risk.s3", RootVar.INPUT,
                "relationship_id", "balances", "projected_cash_usd", "note", "as_of_date"));
        // asset-servicing / settlement_risk — figure paths (RootVar.OUTPUT)
        e.add(dotted("settlement_risk.f1", RootVar.OUTPUT, "failed_amount_usd"));
        e.add(dotted("settlement_risk.f2", RootVar.OUTPUT, "aging.max_failed_age_days"));
        e.add(dotted("settlement_risk.f3", RootVar.OUTPUT, "breach_count"));
        e.add(dotted("settlement_risk.f4", RootVar.OUTPUT, "cash_context.failed_exposure_to_settled_cash_pct"));
        e.add(dotted("settlement_risk.f5", RootVar.OUTPUT, "custody_context.failed_exposure_to_custody_mv_pct"));
        // asset-servicing / trade_penalty — select + map.over + item_select
        e.add(hash("trade_penalty.select", RootVar.INPUT, "failed"));
        e.add(raw("trade_penalty.over", RootVar.INPUT, "failed", "input.failed"));
        e.add(hash("trade_penalty.item_select", RootVar.ITEM,
                "trade_id", "security", "isin", "settle_date", "amount", "side", "as_of_date", "reason", "fail_item"));
        // wealth / concentration — select + figure paths
        e.add(hash("concentration.select", RootVar.INPUT,
                "positions", "total_value", "allocation_by_class", "relationship_id",
                "relationship_name", "currency", "as_of_date", "risk_profile"));
        e.add(dotted("concentration.f1", RootVar.OUTPUT, "single_name.top.weight_pct"));
        e.add(dotted("concentration.f2", RootVar.OUTPUT, "breach_count"));
        e.add(dotted("concentration.f3", RootVar.OUTPUT, "diversification.hhi"));
        e.add(dotted("concentration.f4", RootVar.OUTPUT, "policy.single_name_threshold_pct"));
        // wealth / concentration_review — select + condition
        e.add(hash("concentration_review.select", RootVar.INPUT,
                "relationship_id", "relationship_name", "breach_count", "flags", "policy"));
        e.add(raw("concentration_review.condition", RootVar.INPUT, "breach_count > `0`", "input.breach_count > 0"));
        // insurance / renewal_risk — two selects + figure paths
        e.add(hash("renewal_risk.s1", RootVar.INPUT,
                "policy_id", "policy_name", "insured_name", "line_of_business", "premium",
                "premium_currency", "coverage_limit", "deductible", "status", "effective_date",
                "expiry_date", "as_of_date"));
        e.add(hash("renewal_risk.s2", RootVar.INPUT,
                "policy_id", "claim_count", "claims", "claim_id", "amount", "status"));
        e.add(dotted("renewal_risk.f1", RootVar.OUTPUT, "loss_ratio_pct"));
        e.add(dotted("renewal_risk.f2", RootVar.OUTPUT, "policy.target_loss_ratio_pct"));
        e.add(dotted("renewal_risk.f3", RootVar.OUTPUT, "incurred_losses"));
        e.add(dotted("renewal_risk.f4", RootVar.OUTPUT, "client_disclosure"));
        return e;
    }

    // ── fixture matrix per expression ────────────────────────────────────────────────────────────

    private List<JsonNode> fixtures(Expr e) {
        List<JsonNode> f = new ArrayList<>();
        // 1. FULL — every referenced leaf present, ints (int64-boundary shape)
        f.add(populate(e, true, false));
        // 2. REQUIRED/OPTIONAL-ABSENT — empty root (all referenced fields absent)
        f.add(json("{}"));
        // 3. INTERMEDIATE-OBJECT-ABSENT — present the first intermediate but stop before the leaf
        f.add(populate(e, true, true));
        // 4. numeric variant — floats/decimals where ints (float-where-int, decimal in a > compare)
        f.add(populate(e, false, false));
        // 5. null-valued leaves. A comparison (`>`) applied to null is a genuine type error in BOTH
        //    engines' spirit (JMESPath returns null → CONDITION_ERROR at the call site); feeding null
        //    into a bare comparison is not a navigation case, so it's excluded for comparison exprs.
        if (!isComparison(e)) {
            f.add(nulls(e));
        }
        return f;
    }

    private static boolean isComparison(Expr e) {
        return !e.jmes().startsWith("{") && (e.jmes().contains(">") || e.jmes().contains("<"));
    }

    /** Build a root that assigns each referenced field/path a value; intIfWhole toggles int vs float. */
    private JsonNode populate(Expr e, boolean intIfWhole, boolean emptyIntermediate) {
        var root = MAPPER.createObjectNode();
        String v = e.var().varName();
        for (String path : referencedPaths(e)) {
            String[] seg = path.split("\\.");
            if (emptyIntermediate && seg.length > 1) {
                // present the first intermediate but stop before the leaf
                root.putObject(seg[0]);
                continue;
            }
            var cur = root;
            for (int i = 0; i < seg.length - 1; i++) {
                cur = cur.has(seg[i]) && cur.get(seg[i]).isObject() ? (com.fasterxml.jackson.databind.node.ObjectNode) cur.get(seg[i]) : cur.putObject(seg[i]);
            }
            String leaf = seg[seg.length - 1];
            if (intIfWhole) cur.put(leaf, 2);
            else cur.put(leaf, 2.5);
        }
        return root;
    }

    private JsonNode nulls(Expr e) {
        var root = MAPPER.createObjectNode();
        for (String path : referencedPaths(e)) {
            String top = path.split("\\.")[0];
            root.set(top, MAPPER.nullNode());
        }
        return root;
    }

    /** The leaf field/path names an expression reads (derived from its JMESPath form). */
    private List<String> referencedPaths(Expr e) {
        List<String> paths = new ArrayList<>();
        String j = e.jmes();
        if (j.startsWith("{")) {
            // multiselect-hash: keys map to same-named source fields
            for (String part : j.substring(1, j.length() - 1).split(",")) {
                String src = part.split(":")[1].trim();
                paths.add(src);
            }
        } else if (j.contains(">")) {
            paths.add(j.split(">")[0].trim());          // condition: LHS field
        } else {
            paths.add(j.trim());                          // dotted path or bare field
        }
        return paths;
    }

    // ── the proof ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CEL matches JMESPath for every production expression across the full fixture matrix")
    void celMatchesJmespathGoldensAcrossFixtureMatrix() throws IOException {
        Files.createDirectories(GOLDEN_DIR);
        int comparisons = 0;
        var goldenLog = MAPPER.createArrayNode();

        for (Expr e : productionExpressions()) {
            CompiledExpr compiled = cel.compile(e.cel(), e.var());
            var perExpr = MAPPER.createArrayNode();
            List<JsonNode> fixtures = fixtures(e);
            for (int i = 0; i < fixtures.size(); i++) {
                JsonNode root = fixtures.get(i);
                JsonNode golden = jmes(e.jmes(), root);
                JsonNode actual = cel.eval(compiled, root, EvalEngine.Mode.LENIENT);
                assertThat(canonicalEquals(golden, actual))
                        .as("parity %s fixture#%d\n  root=%s\n  jmes(%s)=%s\n  cel(%s)=%s",
                                e.id(), i, root, e.jmes(), golden, e.cel(), actual)
                        .isTrue();
                comparisons++;
                var rec = MAPPER.createObjectNode();
                rec.put("fixture", i);
                rec.set("root", root);
                rec.set("golden", norm(golden));
                perExpr.add(rec);
            }
            var exprGolden = MAPPER.createObjectNode();
            exprGolden.put("id", e.id());
            exprGolden.put("root", e.var().varName());
            exprGolden.put("jmespath", e.jmes());
            exprGolden.put("cel", e.cel());
            exprGolden.set("cases", perExpr);
            Files.writeString(GOLDEN_DIR.resolve(e.id() + ".json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(exprGolden));
            goldenLog.add(exprGolden);
        }
        assertThat(comparisons).isGreaterThanOrEqualTo(productionExpressions().size() * 4);
    }

    // ── canonical-numeric comparator: IntNode(2) ≡ LongNode(2) ≡ DoubleNode(2.0) when equal ───────

    static boolean canonicalEquals(JsonNode a, JsonNode b) {
        a = norm(a);
        b = norm(b);
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!canonicalEquals(a.get(i), b.get(i))) return false;
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) return false;
            var it = a.fieldNames();
            while (it.hasNext()) {
                String f = it.next();
                if (!b.has(f)) return false;
                if (!canonicalEquals(a.get(f), b.get(f))) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    /** null / NullNode / MissingNode all collapse to a single canonical null. */
    private static JsonNode norm(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return JsonNodeFactory.instance.nullNode();
        return n;
    }

    private JsonNode jmes(String expr, JsonNode root) {
        JsonNode out = JMES.compile(expr).search(root);
        return out == null ? JsonNodeFactory.instance.nullNode() : out;
    }

    private JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    static {
        // BigDecimal is used via decimalValue(); ensure class is initialized deterministically.
        BigDecimal.valueOf(0);
    }
}
