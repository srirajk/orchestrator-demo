# Conduit Orchestration Architecture — the golden record

> This folder is the **canonical, do-not-lose** record of the multi-step orchestration architecture:
> every decision, why we made it, what two independent reviews found, and the target design. If you
> are picking this work up cold, read in this order. Nothing here is scratch — this is the source of
> truth we design and build against.

## Contents
1. **[DECISION-LOG.md](DECISION-LOG.md)** — every architectural decision + the reasoning + status.
   The "so we don't forget and don't regress" record.
2. **[SOLUTION-ARCHITECTURE.md](SOLUTION-ARCHITECTURE.md)** — the target design, picture-perfect:
   the request lifecycle, the edge model (transform + coverage + identity), the two-contract type
   model, the translator/predicate layer, control-flow tiers, request-response vs durable.
3. **[GURU-TEARDOWN-AND-FIXPLAN.md](GURU-TEARDOWN-AND-FIXPLAN.md)** — the two independent adversarial
   reviews reconciled (3 FATALs, serious, manageable) + the prioritized P0/P1/P2 fix plan + the
   "prove-it-to-ourselves" experiments.

## Status (one line)
Design **converged and defensible as state-of-the-art thinking**. Implementation = strong deterministic
spine (proven live through the BFF) with **three FATALs to close before any bank exposure**: per-hop
identity (fail-open today), coverage bypass on resolver-pulled producers, and no data contract between
layers. See the fix plan.

## The one-sentence thesis
A bank asks one plain-English question; an LLM picks only the **goal**; deterministic code **constructs
the pipeline at runtime** from typed manifest contracts; every **edge** transforms shape (compiled),
filters entities to the user's book (runtime), and carries the user's identity; the LLM returns only at
the end to phrase a grounded answer. **LLM at the edges, deterministic and enforced in the middle.**
