# Adversarial Teardown + Fix Plan

> Two independent principal-architect reviews, both reading the real code (not the deck), told to find
> what the other misses. They **converge** on the same three fatals — the strongest possible signal.
> All three security-relevant findings were **verified in code** by the lead before inclusion.
> Verdict (both): **NOT sound as-is — structural flaws, not "known gaps." Good spine; three
> load-bearing beams are dotted lines, two of them security holes.**

## The three FATALs (converged + verified)

### F1 — Per-hop identity is fail-open (whole agent path, flat + DAG). VERIFIED.
The token lives in thread-local `SecurityContext`; the pipeline runs on virtual threads; no
`DelegatingSecurityContextExecutor` / inheritable strategy exists (grep: none). `McpAdapter` even
comments *"SecurityContextHolder is not automatically propagated"* then reads it anyway → **null →
no `Authorization` header**. Agents **fail open** (`jwt_verify.py:77`: no token → allow). So "identity
verified at every hop" is **false today**, and the green suite can't catch it (agents accept no-token).
Confused-deputy on the entire agent path. **Fix:** capture token → immutable request-context DATA →
thread through harness/adapters; agents fail **closed**; e2e with `verify=required` asserts a real JWT
per hop.

### F2 — Book-of-business coverage bypass on resolver-pulled producers. VERIFIED.
`tryDag` re-gates only via `filterAgents` = `cerbos.checkAgents` (**structural**). The coverage CHECK
runs once, on the *routed goal* entity, never per pulled producer per the entity it consumes. Safe today
only because the one demo topology reuses the single checked entity. A producer keyed on a *different*
entity → cross-book leak, reachable by **adding a manifest** (the product's own extensibility story).
**Fix:** coverage as a **runtime per-edge filter** (see SOLUTION-ARCHITECTURE §3.2 / DECISION D12):
for every entity-typed value crossing an edge, CHECK against its coverage service, keep the covered
subset, raw output never crosses un-filtered, filter before aggregation.

### F3 — No data contract between layers → confident WRONG numbers. VERIFIED (the 422).
`Blackboard.bind` passes producer output **verbatim** (1 producer) or merges-by-name (N); `io` is a bare
string; output-schema introspection is stubbed → **no wire contract**. Failure isn't a crash — it's a
**wrong risk number**: a truncated/partial holdings payload → HHI on 3-of-12 positions → *"well
diversified"* on a concentrated book. Plus the 1-vs-N branch **flips on runtime survivorship** (an
optional producer failing changes the consumer's shape). And grounding validates number *presence*, not
*attribution*, and is diagnostic-only. **Fix:** the translator + canonical/versioned type layer (D7/D8),
the completeness contract, and the grounding provenance hard-gate (D15).

## Serious (both converge)
- **S-fallback** — 8 distinct conditions silently drop to flat with no user/trace signal. "Quietly did
  the dumb thing and looked confident." → emit reason-coded `dag_abandoned` events; surface
  computed-vs-assembled.
- **S-latency** — strict layer barrier (`allOf` per layer) + one 60s global deadline → p99 of a deep DAG
  is a batch job. → dataflow release + per-critical-path budget + stream partial.
- **S-types-at-scale** — string-equality, no version/owner: a *second* `wealth.holdings` producer
  silently kills every concentration DAG (AMBIGUOUS→flat). → namespace + version + priority.
- **S-compound** — `tryDag` needs exactly one fan-in goal; compound queries fall to flat *and the flat
  path is broken for concentration* (gets an entity input it can't use). → multi-goal or clarify-split.
- **S-injection** — a producer's output flows verbatim as a downstream agent's INPUT with no boundary;
  untrusted output becomes trusted input. → narrow to declared schema fields at every hop (D7 gives this).

## Manageable (log before GA)
- **M-embeddings** — tests run on hash-embeddings; demo runs on MiniLM → routing validated against the
  wrong vector space. Test on the shipped model.
- **M-replay** — plan is runtime-only + probabilistic ends; can't deterministically replay for a
  regulator. Persist the audit record (SOLUTION-ARCHITECTURE §8).
- **M-index-freshness** — router KNN index vs `registry.listAll()` can skew during reload; needs atomic
  versioned swap.
- **M-ambiguity-scope** — resolver reasons over ALL agents then re-gates; a producer the user *can't* see
  can create an ambiguity that kills a DAG they *should* get. Compute ambiguity over the entitled set.
- **M-multiturn** — flat path has an extractor-drop backstop; `tryDag` doesn't → multi-turn DAG drops to
  flat more often. Add the backstop.
- **M-cost/cold-start** — two LLM calls on the critical path; cache identical extractions; fast-path
  id-shaped inputs.
- **M-cancellation** — SLA timeout cancels the Java future, not the server-side call; fine for read-only,
  document the assumption before any write agent.

## The tell (both said it independently)
Every green test passes because the **one** demo topology has matching shapes, coinciding coverage
entities, and fail-open agents. **Change one manifest and a fatal fires.**

## Prioritized fix plan
**P0 — security, before any bank exposure or a second producer:**
1. **Identity propagation + fail-closed agents** (F1). *[widest blast radius — do first]*
2. **Coverage as a runtime per-edge entity filter** (F2).

**P1 — correctness, before "production":**
3. **Data contract:** output-schema introspection + translator (CEL/JMESPath, cached, validated) +
   field projection + versioned/namespaced types (F3/S-types/S-injection).
4. **Completeness contract:** analytics refuse to compute on incomplete input (F3).
5. **Grounding:** runtime provenance hard-gate on attribution + deterministic number rendering (F4).

**P2 — robustness/UX:**
6. Reason-coded fallback events + computed-vs-assembled provenance (S-fallback).
7. Dataflow release + per-critical-path deadline + partial streaming (S-latency).
8. Type namespacing/versioning + priority tie-break (S-types); compound-query handling (S-compound);
   test-vs-demo embedding parity (M-embeddings); audit record (M-replay).

## Prove the fatals to ourselves (cheap, empirical — do these)
- Add a second `wealth.holdings` producer → watch every concentration query silently go flat. (S-types)
- Give holdings a different output key than concentration expects → watch the wrong-number / 422. (F3)
- Flip agents to `verify=required` → watch the hops 401. (F1)
