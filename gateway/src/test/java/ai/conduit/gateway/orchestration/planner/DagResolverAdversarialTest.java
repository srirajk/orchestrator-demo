package ai.conduit.gateway.orchestration.planner;

import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static ai.conduit.gateway.orchestration.planner.DagGraphFixtures.cap;
import static ai.conduit.gateway.orchestration.planner.DagGraphFixtures.entity;
import static ai.conduit.gateway.orchestration.planner.DagGraphFixtures.from;
import static ai.conduit.gateway.orchestration.planner.DagGraphFixtures.produce;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TECHNIQUE 4 — Adversarial / negative testing. Hand-built pathological graphs that must produce a
 * precise, classified refusal (never a crash, never a silent wrong plan): self-loop, 2-cycle and
 * longer cycles → {@code CYCLE}; a diamond → resolves once (shared producer deduped); an orphan
 * required entity → {@code UNMET_REQUIRED_INPUT}; an unknown goal → {@code UNKNOWN_NODE}; a node with
 * an absent OPTIONAL entity → still OK.
 */
class DagResolverAdversarialTest {

    private final DagResolver resolver = new DagResolver();

    @Test
    @DisplayName("self-loop: A consumes the type A itself produces ⇒ CYCLE (iterative resolver does not hang)")
    void selfLoopIsCycle() {
        var a = cap("cap.a", List.of(from("typeA")), List.of(produce("a", "typeA")));

        DagResolution r = resolver.resolve("cap.a", List.of(a), Set.of());

        assertThat(r.ok()).isFalse();
        assertThat(r.hasError(ResolutionError.Code.CYCLE)).isTrue();
        assertThat(r.errors().stream()
                .filter(e -> e.code() == ResolutionError.Code.CYCLE).findFirst().orElseThrow()
                .details()).contains("cap.a");
    }

    @Test
    @DisplayName("2-cycle: A↔B mutual from-dependency ⇒ CYCLE")
    void twoCycleIsCycle() {
        var a = cap("cap.a", List.of(from("typeB")), List.of(produce("a", "typeA")));
        var b = cap("cap.b", List.of(from("typeA")), List.of(produce("b", "typeB")));

        DagResolution r = resolver.resolve("cap.a", List.of(a, b), Set.of());

        assertThat(r.ok()).isFalse();
        assertThat(r.hasError(ResolutionError.Code.CYCLE)).isTrue();
        assertThat(r.errors().stream()
                .filter(e -> e.code() == ResolutionError.Code.CYCLE).findFirst().orElseThrow()
                .details()).contains("cap.a", "cap.b");
    }

    @Test
    @DisplayName("longer cycle: A→B→C→A (3-cycle) ⇒ CYCLE, all three named")
    void threeCycleIsCycle() {
        var a = cap("cap.a", List.of(from("typeC")), List.of(produce("a", "typeA")));
        var b = cap("cap.b", List.of(from("typeA")), List.of(produce("b", "typeB")));
        var c = cap("cap.c", List.of(from("typeB")), List.of(produce("c", "typeC")));

        DagResolution r = resolver.resolve("cap.a", List.of(a, b, c), Set.of());

        assertThat(r.ok()).isFalse();
        assertThat(r.hasError(ResolutionError.Code.CYCLE)).isTrue();
        assertThat(r.errors().stream()
                .filter(e -> e.code() == ResolutionError.Code.CYCLE).findFirst().orElseThrow()
                .details()).contains("cap.a", "cap.b", "cap.c");
    }

    @Test
    @DisplayName("diamond A→{B,C}→D: the shared producer resolves EXACTLY once (deduped)")
    void diamondSharedProducerResolvesOnce() {
        var base = cap("cap.base", List.of(entity("entE")), List.of(produce("c", "typeC")));
        var b    = cap("cap.b", List.of(from("typeC")), List.of(produce("b", "typeA")));
        var c    = cap("cap.c", List.of(from("typeC")), List.of(produce("c2", "typeB")));
        var d    = cap("cap.d", List.of(from("typeA"), from("typeB")), List.of(produce("d", "typeD")));

        DagResolution r = resolver.resolve("cap.d", List.of(base, b, c, d), Set.of("entE"));

        assertThat(r.ok()).isTrue();
        List<String> order = r.plan().nodes().stream().map(PlanNode::nodeId).toList();
        assertThat(order).hasSize(4);
        assertThat(order.stream().filter("cap.base"::equals).count()).isEqualTo(1);   // deduped
        // base precedes both branches; both branches precede the join.
        assertThat(order.indexOf("cap.base")).isLessThan(order.indexOf("cap.b"));
        assertThat(order.indexOf("cap.base")).isLessThan(order.indexOf("cap.c"));
        assertThat(order.indexOf("cap.b")).isLessThan(order.indexOf("cap.d"));
        assertThat(order.indexOf("cap.c")).isLessThan(order.indexOf("cap.d"));
    }

    @Test
    @DisplayName("orphan required entity: goal needs a required entity not in the available set ⇒ UNMET_REQUIRED_INPUT")
    void orphanRequiredEntityIsUnmet() {
        var goal = cap("cap.goal", List.of(entity("entMissing", true)), List.of(produce("g", "typeG")));

        DagResolution r = resolver.resolve("cap.goal", List.of(goal), Set.of());   // entMissing absent

        assertThat(r.ok()).isFalse();
        assertThat(r.hasError(ResolutionError.Code.UNMET_REQUIRED_INPUT)).isTrue();
        assertThat(r.errors().get(0).details()).contains("cap.goal", "entMissing");
    }

    @Test
    @DisplayName("required entity reachable via a producer chain, still missing at the leaf ⇒ UNMET_REQUIRED_INPUT")
    void deepMissingRequiredEntityIsUnmet() {
        // consumer ← producer(from), producer needs a required leaf entity that is not available.
        var producer = cap("cap.producer", List.of(entity("entLeaf", true)), List.of(produce("o", "typeA")));
        var consumer = cap("cap.consumer", List.of(from("typeA")), List.of(produce("r", "typeR")));

        DagResolution r = resolver.resolve("cap.consumer", List.of(producer, consumer), Set.of());

        assertThat(r.ok()).isFalse();
        assertThat(r.hasError(ResolutionError.Code.UNMET_REQUIRED_INPUT)).isTrue();
        assertThat(r.errors().get(0).details()).contains("cap.producer", "entLeaf");
    }

    @Test
    @DisplayName("AMBIGUOUS_PRODUCER candidate ids are reported in deterministic SORTED order (not input order)")
    void ambiguousProducersReportedSorted() {
        // Pass producers in reverse-sorted order; the resolver must still report them sorted.
        var zzz  = cap("cap.zzz", List.of(), List.of(produce("z", "typeA")));
        var aaa  = cap("cap.aaa", List.of(), List.of(produce("a", "typeA")));
        var goal = cap("cap.goal", List.of(from("typeA")), List.of(produce("g", "typeG")));

        DagResolution r = resolver.resolve("cap.goal", List.of(zzz, aaa, goal), Set.of());

        assertThat(r.hasError(ResolutionError.Code.AMBIGUOUS_PRODUCER)).isTrue();
        List<String> details = r.errors().stream()
                .filter(e -> e.code() == ResolutionError.Code.AMBIGUOUS_PRODUCER).findFirst().orElseThrow()
                .details();
        // details = [consumerId, type, producer..., producer...]; the producer tail must be sorted.
        List<String> producerTail = details.subList(2, details.size());
        assertThat(producerTail).containsExactly("cap.aaa", "cap.zzz");   // sorted, deterministic
    }

    @Test
    @DisplayName("hasError is precise: a successful resolution reports NO error code")
    void hasErrorFalseOnSuccess() {
        var producer = cap("cap.producer", List.of(entity("entE")), List.of(produce("o", "typeA")));
        var consumer = cap("cap.consumer", List.of(from("typeA")), List.of(produce("r", "typeR")));

        DagResolution r = resolver.resolve("cap.consumer", List.of(producer, consumer), Set.of("entE"));

        assertThat(r.ok()).isTrue();
        // Every code must be reported absent — guards against hasError() degenerating to a constant.
        for (ResolutionError.Code code : ResolutionError.Code.values()) {
            assertThat(r.hasError(code)).as("ok resolution must not report %s", code).isFalse();
        }
    }

    @Test
    @DisplayName("unknown goal: goal id not among candidates ⇒ UNKNOWN_NODE (fails fast)")
    void unknownGoalIsUnknownNode() {
        var other = cap("cap.other", List.of(), List.of(produce("o", "typeA")));

        DagResolution r = resolver.resolve("cap.absent", List.of(other), Set.of());

        assertThat(r.ok()).isFalse();
        assertThat(r.hasError(ResolutionError.Code.UNKNOWN_NODE)).isTrue();
        assertThat(r.errors().get(0).details()).contains("cap.absent");
    }

    @Test
    @DisplayName("absent OPTIONAL entity ⇒ still OK, single node, no error")
    void absentOptionalEntityIsOk() {
        var goal = cap("cap.goal",
                List.of(entity("entReq", true), entity("entOpt", false)),
                List.of(produce("g", "typeG")));

        DagResolution r = resolver.resolve("cap.goal", List.of(goal), Set.of("entReq"));   // entOpt absent

        assertThat(r.ok()).isTrue();
        assertThat(r.plan().nodes()).singleElement()
                .satisfies(n -> assertThat(((PlanNode) n).dependsOn()).isEmpty());
    }

    @Test
    @DisplayName("cycle + independent valid branch: the cycle is still reported (does not silently swallow)")
    void cycleAmongDependenciesIsReported() {
        // goal depends on a valid producer AND on a 2-cycle pair — the cycle must surface.
        var okProd = cap("cap.okprod", List.of(entity("entE")), List.of(produce("o", "typeOk")));
        var x = cap("cap.x", List.of(from("typeY")), List.of(produce("x", "typeX")));
        var y = cap("cap.y", List.of(from("typeX")), List.of(produce("y", "typeY")));
        var goal = cap("cap.goal", List.of(from("typeOk"), from("typeX")), List.of(produce("g", "typeG")));

        DagResolution r = resolver.resolve("cap.goal", List.of(okProd, x, y, goal), Set.of("entE"));

        assertThat(r.ok()).isFalse();
        assertThat(r.hasError(ResolutionError.Code.CYCLE)).isTrue();
    }
}
