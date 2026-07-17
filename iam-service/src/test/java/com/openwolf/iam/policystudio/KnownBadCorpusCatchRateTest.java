package com.openwolf.iam.policystudio;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C3.2 — the MEASURED catch-rate over the hand-reviewed known-bad corpus.
 *
 * <p>This is the empirical heart of the story: it runs the FULL corpus (31 pairs) through the C3 draft
 * gate — the independently-generated oracle + mechanically-injected negative probes + C2's
 * deterministic gate — and asserts the aggregate catch-rate ≥ 90%, 100% for the cross-tenant AND
 * wildcard classes, and zero regression (every corpus item stays caught by its pinned layer). It emits
 * a per-item pass/miss table to {@code docs/implementation/evidence/studio/c3} — the checked-in
 * headline artifact — and publishes any misses.
 *
 * <p><b>The gate, not the LLM.</b> The oracle is a deterministic, seeded stub (no real LLM is ever
 * called). The rate therefore measures the GATE — probes + invariants + the deterministic validator —
 * over a fixed adversary, which is precisely what makes it reproducible. Live-LLM oracle quality is a
 * separate concern (see the evidence README); it does not move this number.
 */
class KnownBadCorpusCatchRateTest {

    private static final double AGGREGATE_THRESHOLD = 0.90;

    private IndependentTestGenService newService() {
        return new IndependentTestGenService(
                C3Corpus.oracle(),
                new NegativeProbeInjector(),
                new PolicyExpectationEvaluator(new ConditionEvaluator()),
                new GeneratedPolicyValidator(),
                new CanonicalPolicyWriter(),
                new CerbosCompileGate("ghcr.io/cerbos/cerbos:latest", 60),
                "infra/cerbos/policies");
    }

    private record Row(
            String id, String category, CatchLayer expected, CatchLayer actual,
            boolean caught, boolean moatCritical, Set<ProbeKind> failingProbes,
            int validatorViolations, String note) {}

    @Test
    void overGrantYamlCaughtBeforeApproval() {
        IndependentTestGenService svc = newService();
        PolicyYamlParser parser = new PolicyYamlParser();
        ProbeAttributes probeAttrs = C3TestGenFixtures.probeAttributes();

        List<C3Corpus.Item> corpus = C3Corpus.items();
        assertThat(corpus).as("the known-bad corpus must have at least 30 items").hasSizeGreaterThanOrEqualTo(30);

        List<Row> rows = new ArrayList<>();
        String sampleOracleYaml = null;
        for (C3Corpus.Item item : corpus) {
            PolicyIR ir = parser.parse(item.badYaml());
            IndependentTestGenService.TestGenReport r =
                    svc.assess(item.request(), ir, probeAttrs, /* runCompile */ false);
            Set<ProbeKind> failing = r.oracleFailures().stream()
                    .map(Expectation::kind).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            rows.add(new Row(item.id(), item.category(), item.expectedLayer(), r.primaryLayer(),
                    r.caught(), r.moatCritical(), failing, r.validatorViolations().size(), item.note()));
            if (sampleOracleYaml == null && item.expectedLayer() == CatchLayer.ORACLE) {
                sampleOracleYaml = "# Rendered from item " + item.id() + " — intent: "
                        + item.request().intent() + "\n" + r.oracleTestYaml();
            }
        }

        long caught = rows.stream().filter(Row::caught).count();
        double rate = (double) caught / rows.size();
        List<Row> misses = rows.stream().filter(row -> !row.caught()).toList();

        // Per-category rates.
        Map<String, long[]> perCat = new TreeMap<>(); // cat -> [caught, total]
        for (Row row : rows) {
            long[] cc = perCat.computeIfAbsent(row.category(), k -> new long[2]);
            if (row.caught()) {
                cc[0]++;
            }
            cc[1]++;
        }

        writeEvidence(rows, rate, perCat, misses, sampleOracleYaml);

        // ── Assertions ────────────────────────────────────────────────────────────────────────────
        // Aggregate ≥ 90%.
        assertThat(rate)
                .as("aggregate catch-rate over %d known-bad pairs (misses: %s)", rows.size(),
                        misses.stream().map(Row::id).toList())
                .isGreaterThanOrEqualTo(AGGREGATE_THRESHOLD);

        // 100% for the cross-tenant AND wildcard classes.
        assertCategory100(perCat, C3TestGenFixtures.CAT_CROSS_TENANT);
        assertCategory100(perCat, C3TestGenFixtures.CAT_WILDCARD);

        // Zero regression: every item stays caught, and by the layer it is pinned to.
        for (Row row : rows) {
            assertThat(row.caught())
                    .as("regression: corpus item %s (%s) is no longer caught", row.id(), row.category())
                    .isTrue();
            assertThat(row.actual())
                    .as("corpus item %s changed catch layer (expected %s)", row.id(), row.expected())
                    .isEqualTo(row.expected());
            if (row.expected() == CatchLayer.ORACLE) {
                assertThat(row.moatCritical())
                        .as("item %s must be moat-critical (validator-clean, oracle-caught)", row.id())
                        .isTrue();
            }
        }

        // The moat must not be vacuous: a substantial set is caught ONLY by the independent oracle.
        long moat = rows.stream().filter(Row::moatCritical).count();
        assertThat(moat)
                .as("moat-critical items (validator accepted, oracle caught) — the co-generated blind spot")
                .isGreaterThanOrEqualTo(10);
    }

    private void assertCategory100(Map<String, long[]> perCat, String category) {
        long[] cc = perCat.get(category);
        assertThat(cc).as("category '%s' must be present in the corpus", category).isNotNull();
        assertThat(cc[0])
                .as("category '%s' must be 100%% caught (%d/%d)", category, cc[0], cc[1])
                .isEqualTo(cc[1]);
    }

    // ── evidence emission ──────────────────────────────────────────────────────────────────────

    private void writeEvidence(List<Row> rows, double rate, Map<String, long[]> perCat, List<Row> misses,
                               String sampleOracleYaml) {
        StringBuilder md = new StringBuilder();
        md.append("# C3.2 — Known-bad corpus catch-rate (MEASURED)\n\n");
        md.append("> Auto-generated by `KnownBadCorpusCatchRateTest`. This is a **validated hypothesis")
          .append(" with a living corpus**, not a formal-completeness claim. Add adversarial pairs over")
          .append(" time; the gate must keep clearing the bars below.\n\n");
        md.append("**Harness:** deterministic seeded oracle (NO real LLM) + mechanical negative probes +")
          .append(" C2 deterministic gate. The rate measures the GATE over a fixed adversary.\n\n");
        md.append(String.format("## Headline%n%n"));
        long caught = rows.stream().filter(Row::caught).count();
        long moat = rows.stream().filter(Row::moatCritical).count();
        md.append(String.format("- **Aggregate catch-rate: %.1f%% (%d/%d)**%n", rate * 100, caught, rows.size()));
        md.append(String.format("- **Moat-critical (validator-clean, caught ONLY by the independent oracle): %d**%n", moat));
        md.append(String.format("- **Misses: %d** %s%n", misses.size(),
                misses.isEmpty() ? "" : misses.stream().map(Row::id).collect(Collectors.joining(", ", "(", ")"))));
        md.append("\n## Per-class rate\n\n| Class | Caught | Total | Rate |\n|---|---|---|---|\n");
        for (Map.Entry<String, long[]> e : perCat.entrySet()) {
            long[] cc = e.getValue();
            md.append(String.format("| %s | %d | %d | %.0f%% |%n", e.getKey(), cc[0], cc[1],
                    100.0 * cc[0] / cc[1]));
        }
        md.append("\n## Per-item pass/miss table\n\n");
        md.append("| ID | Class | Expected layer | Actual layer | Caught | Moat-critical | Failing probes | Note |\n");
        md.append("|---|---|---|---|---|---|---|---|\n");
        for (Row row : rows) {
            md.append(String.format("| %s | %s | %s | %s | %s | %s | %s | %s |%n",
                    row.id(), row.category(), row.expected(), row.actual(),
                    row.caught() ? "✅" : "❌ MISS", row.moatCritical() ? "yes" : "",
                    row.failingProbes().isEmpty() ? "—" : row.failingProbes().toString(),
                    row.note()));
        }
        if (!misses.isEmpty()) {
            md.append("\n## Published misses\n\n");
            for (Row m : misses) {
                md.append(String.format("- **%s** (%s): %s%n", m.id(), m.category(), m.note()));
            }
        }
        String content = md.toString();

        Path dir = resolveEvidenceDir();
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("catch-rate-table.md"), content, StandardCharsets.UTF_8);
            if (sampleOracleYaml != null) {
                Files.writeString(dir.resolve("sample-oracle-test.yaml"), sampleOracleYaml, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // Evidence emission is best-effort; the assertions above are the source of truth.
        }
        // Always echo the headline so it is captured in the build log.
        System.out.printf("[C3.2] catch-rate=%.1f%% caught=%d/%d moat-critical=%d misses=%d -> %s%n",
                rate * 100, caught, rows.size(), moat, misses.size(), dir.resolve("catch-rate-table.md"));
    }

    /** Walk up from the working dir to the repo root; fall back to the module target dir. */
    private Path resolveEvidenceDir() {
        File cur = new File("").getAbsoluteFile();
        for (int i = 0; i < 6 && cur != null; i++) {
            File candidate = new File(cur, "docs/implementation/evidence/studio/c3");
            if (new File(cur, "docs/implementation").isDirectory()) {
                return candidate.toPath();
            }
            cur = cur.getParentFile();
        }
        return new File("target/c3-evidence").toPath();
    }
}
