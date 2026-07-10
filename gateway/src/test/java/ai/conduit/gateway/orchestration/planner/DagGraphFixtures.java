package ai.conduit.gateway.orchestration.planner;

import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.AgentManifest.Consume;
import ai.conduit.gateway.registry.model.AgentManifest.Io;
import ai.conduit.gateway.registry.model.AgentManifest.Produce;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Shared, domain-free builders for the {@link DagResolver} test battery (property, determinism,
 * metamorphic, adversarial, scale). Every symbol produced here is a generic placeholder
 * ({@code n###}, {@code t#}, {@code ent#}) — no domain vocabulary, matching World B invariants.
 */
final class DagGraphFixtures {

    private DagGraphFixtures() {}

    // ── minimal manifest builders (canonical 20-arg constructor, io at slot 15) ────────────────

    static AgentManifest cap(String id, List<Consume> consumes, List<Produce> produces) {
        return new AgentManifest(
                id, id, null, null, null, null, null, null, null, "http",
                null, null, null, null, new Io(consumes, produces),
                null, null, null, true, null);
    }

    static Consume from(String type)                 { return new Consume(null, type, true); }
    static Consume from(String type, boolean req)    { return new Consume(null, type, req); }
    static Consume entity(String key)                { return new Consume(key, null, true); }
    static Consume entity(String key, boolean req)   { return new Consume(key, null, req); }
    static Produce produce(String name, String type) { return new Produce(name, type); }

    static String pad(int i) { return String.format("n%04d", i); }

    // ── random acyclic, single-producer-per-type, entities-available graph generator ──────────

    /**
     * A generated capability graph plus everything a test needs to check the resolver against an
     * independent oracle: the candidate list (already shuffled), a randomly chosen goal, and the
     * entity keys declared available.
     */
    record RandomGraph(List<AgentManifest> candidates, String goal, Set<String> available, int n) {}

    /**
     * Generate a random DAG that a correct resolver MUST resolve to {@code ok==true}:
     * <ul>
     *   <li>node {@code i} produces exactly one <b>unique</b> type {@code "t"+i} → single producer
     *       per type, so no {@code AMBIGUOUS_PRODUCER} is ever possible;</li>
     *   <li>node {@code i} may consume {@code from "t"+j} only for {@code j < i} → strictly acyclic;</li>
     *   <li>every consumed {@code from} type is produced by some node → no {@code MISSING_PRODUCER};</li>
     *   <li>every <i>required</i> entity is placed in the available set → no {@code UNMET_REQUIRED_INPUT};
     *       optional entities may be absent (exercised, never fatal).</li>
     * </ul>
     * The candidate list is shuffled so callers also exercise order-independence.
     */
    static RandomGraph randomAcyclicGraph(Random rnd) {
        int n = 3 + rnd.nextInt(10); // 3..12 nodes
        List<AgentManifest> caps = new ArrayList<>(n);
        Set<String> available = new HashSet<>();

        for (int i = 0; i < n; i++) {
            List<Consume> consumes = new ArrayList<>();
            for (int j = 0; j < i; j++) {
                if (rnd.nextDouble() < 0.35) consumes.add(from("t" + j));   // back-edge only → acyclic
            }
            if (rnd.nextDouble() < 0.40) {
                String e = "ent" + i;
                available.add(e);
                consumes.add(entity(e, true));                              // required + available
            }
            if (rnd.nextDouble() < 0.30) {
                consumes.add(entity("optEnt" + i, false));                  // optional + absent → fine
            }
            List<Produce> produces = List.of(produce("p" + i, "t" + i));   // unique type per node
            caps.add(cap(pad(i), consumes, produces));
        }

        String goal = pad(rnd.nextInt(n));
        Collections.shuffle(caps, rnd);
        return new RandomGraph(caps, goal, available, n);
    }
}
