# Planted policy mutants — the mutation-smoke teeth test (Axiom Story B3.3)

These files are **planted over-permissive mutants**. They are **NOT production policies**
and live here, **outside** the compiled policy root (`infra/cerbos/policies/`), so `cerbos`
never loads them as part of the real policy set.

## Why

A policy test suite that no mutant can break is coverage theater. `scripts/cerbos-mutation-smoke.sh`
proves the gate has teeth: for each mutant it

1. copies the **real** policy tree to a throwaway temp dir (the working tree is never touched),
2. overlays exactly one mutant in place of its `MUTANT-TARGET`,
3. runs the full policy gate (`scripts/cerbos-policy-gate.sh`) against the temp dir,
4. asserts the gate exits **non-zero** — a test/invariant or a lint must catch the over-grant.

If any mutant **escapes** (gate stays green), the smoke goes red and names it — a real hole.

## The four mutants

| Mutant | Target | Over-grant | Caught by |
|---|---|---|---|
| `mutant_a_drop_rank0_guard` | `agent_resource.yaml` | drops the `resourceClassRecognized` fail-closed guard | `Rank0TrapPreservedTest` + `UnknownInputsDeny` (cerbos compile) |
| `mutant_b_incomplete_tenant_child` | `tenant_default_agent.yaml` | leaves a base-allowed tuple unmatched at the tenant child (fall-through → base ALLOW) | `cerbos-tenant-totality-lint.py` — **invisible to cerbos decision tests** (fall-through preserves ALLOW; this is exactly why the totality lint exists) |
| `mutant_c_remove_tenant_equality` | `business_derived_roles.yaml` | removes the tenant-equality condition from `tenant_chat_user` | `TenantEqualityBackstopTest` + `CrossTenantDenyMatrix` (cerbos compile) + `cerbos-allow-tenant-equality-lint.py` |
| `mutant_d_wildcard_read` | `insights_resource.yaml` | adds a wildcard `read` ALLOW to every role | `DecisionParityMatrixTest.insightsGatedToAdminsOnly` (cerbos compile) |

Each mutant is a **faithful copy** of its target policy with a single change (generated so the
only diff is the planted over-grant), carrying a machine-readable `MUTANT-TARGET:` marker the
smoke script parses.

## Run it

```bash
scripts/cerbos-mutation-smoke.sh
```
