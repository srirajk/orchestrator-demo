#!/usr/bin/env python3
"""Measure live goal-pick accuracy on the PRODUCTION routing path (routing spec V2 Piece 6).

The harness drives the gateway's test-profile decision endpoint ``POST /debug/route``
(``RouteDecisionController`` → ``ChatService.decideRoute``), which runs the REAL shared
pre-routing preparation — ``RoutePreparer`` (masking + relaxation) → ``resolveContextual``
→ ``buildRequestedPlan`` → per-group structural authorization — and STOPS before any agent
is invoked. Every score therefore comes from the same masking, resolver, embedding service,
Redis vector index, registry, and Cerbos PDP the chat request path uses, not from a raw
``resolve()`` shortcut. Each row is sent with ITS OWN persona's bearer token (cached per
persona), so entitlement disposition is scored against the real caller — not one admin token.

Two invariants are asserted on every response and fail the run hard if violated (debug/prod
drift, failure-mode #15): ``path == "production"`` and ``preparationVersion == <expected>``.

Per-shape scoring (unchanged in spirit from T1.6 — the label is never relabelled):
  1. out_of_scope  -> expected_goal == "abstain": correct iff the router abstains.
  2. analytics     -> expected_goal is a DAG-capable (fan-in) agent: correct iff the resolved
     DAG goal (the plan's DAG group goalId, else the highest-ranked selected fan-in agent)
     equals expected_goal.
  3. flat          -> expected_goal is a leaf/data agent: correct iff the production routing
     LEADER (resolver.primaryAgentId, i.e. the post-rerank top-1) equals expected_goal.

Gates (all must hold):
  * EXACT-CAPABILITY accuracy over ALL clear rows (canonical + near_miss + held_out +
    cross_agent_confuser) ≥ the configured floor — the confusers are now IN the gate.
  * WRONG-DOMAIN SUBSTITUTION == 0: no clear row may route to a capability in the wrong
    DOMAIN instead of abstaining or picking the right domain.
  * OUT-OF-SCOPE ABSTAIN == 100%.
  * MIN PER-CAPABILITY RECALL ≥ the configured floor (no single capability is starved).
  * (reported, and gated when a floor is set) TOP-K RECALL: the expected capability appears
    somewhere in the candidate set (selected+below-floor) — the reranker cannot recover an
    absent candidate.
  * canonical-intent poaching within tolerance (unchanged).

Per-row optional expectations (checked + reported when present):
  * ``expected_disposition`` (e.g. "COVERAGE_DENIED"/"STRUCTURAL_DENIED"/"SERVED"): asserts
    ``overallDisposition``.
  * ``expected_denied_capability``: the named capability must land in a DENIED/PARTIAL group's
    denied list (capability_entity_conflict rows: entity from domain A + capability from domain
    B, requested by a persona not entitled to domain B).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from collections import defaultdict
from pathlib import Path
from typing import Any


# ── manifest-derived label helpers (unchanged) ──────────────────────────────────────────────

def load_dag_goals(manifest_root: Path) -> set[str]:
    """Agent ids whose manifest declares a fan-in (produced-ref / `from`) consume."""
    goals: set[str] = set()
    for path in manifest_root.rglob("*.json"):
        with path.open() as f:
            manifest = json.load(f)
        consumes = ((manifest.get("io") or {}).get("consumes")) or []
        if any((c or {}).get("from") for c in consumes):
            goals.add(manifest["agent_id"])
    return goals


def load_agent_domains(manifest_root: Path) -> dict[str, str]:
    """agent_id -> domain, straight from each manifest's own `domain` field."""
    domains: dict[str, str] = {}
    for path in manifest_root.rglob("*.json"):
        with path.open() as f:
            manifest = json.load(f)
        aid = manifest.get("agent_id")
        dom = manifest.get("domain")
        if aid and dom:
            domains[aid] = dom
    return domains


# ── IAM persona tokens (cached per persona) ─────────────────────────────────────────────────

def mint_token(iam_url: str, username: str, password: str) -> str:
    req = urllib.request.Request(
        f"{iam_url.rstrip('/')}/auth/token",
        data=json.dumps({"username": username, "password": password}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read())
        token = data.get("accessToken") or data.get("access_token")
        if not token:
            raise ValueError(f"No token in IAM response for {username}: {data}")
        return token


def token_for(persona: str, iam_url: str, password: str, cache: dict[str, str]) -> str:
    if persona not in cache:
        cache[persona] = mint_token(iam_url, persona, password)
    return cache[persona]


# ── production decision endpoint ────────────────────────────────────────────────────────────

def messages_for(item: dict[str, Any]) -> list[dict[str, str]]:
    """A row is either a single `query` (one user turn) or an explicit `messages` window
    (multi-turn / facet-carry rows) sent verbatim so the production preparation sees the same
    context the chat path would."""
    if item.get("messages"):
        return item["messages"]
    return [{"role": "user", "content": item["query"]}]


def decide_route(gateway_url: str, item: dict[str, Any], token: str) -> dict[str, Any]:
    body = json.dumps({
        "model": "conduit-goal-pick-eval",
        "stream": False,
        "messages": messages_for(item),
    }).encode()
    req = urllib.request.Request(
        f"{gateway_url.rstrip('/')}/debug/route",
        data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


# ── decision projection (all signals come from the production RouteDecision) ─────────────────

def selected_candidates(decision: dict[str, Any]) -> list[dict[str, Any]]:
    return [c for c in (decision.get("candidates") or []) if c.get("selected")]


def all_candidate_ids(decision: dict[str, Any]) -> list[str]:
    return [c.get("agentId") for c in (decision.get("candidates") or []) if c.get("agentId")]


def is_abstain(decision: dict[str, Any]) -> bool:
    resolver = decision.get("resolver") or {}
    return bool(resolver.get("fallback")) or not selected_candidates(decision)


def leader_agent(decision: dict[str, Any]) -> str:
    """The production routing LEADER: resolver.primaryAgentId (post-rerank top-1), else the
    highest-scored selected candidate. Used for FLAT rows."""
    if is_abstain(decision):
        return "abstain"
    resolver = decision.get("resolver") or {}
    if resolver.get("primaryAgentId"):
        return resolver["primaryAgentId"]
    sel = sorted(selected_candidates(decision), key=lambda c: c.get("score", 0.0), reverse=True)
    return sel[0].get("agentId") if sel else "abstain"


def resolved_dag_goal(decision: dict[str, Any], dag_goals: set[str]) -> str:
    """The plan's DAG group goalId if present (production tryDag rule), else the highest-ranked
    selected fan-in agent. Used for ANALYTICS rows."""
    if is_abstain(decision):
        return "abstain"
    for grp in (decision.get("requestedGroups") or []):
        if grp.get("kind") == "DAG" and grp.get("goalId"):
            return grp["goalId"]
    for c in sorted(selected_candidates(decision), key=lambda c: c.get("score", 0.0), reverse=True):
        if c.get("agentId") in dag_goals:
            return c["agentId"]
    return "no-dag-goal-in-selected"


def query_shape(expected_goal: str, dag_goals: set[str]) -> str:
    if expected_goal == "abstain":
        return "out_of_scope"
    if expected_goal in dag_goals:
        return "analytics"
    return "flat"


def denied_capabilities(decision: dict[str, Any]) -> set[str]:
    out: set[str] = set()
    for grp in (decision.get("disposition") or []):
        for cid in (grp.get("deniedCapabilityIds") or []):
            out.add(cid)
    return out


def score_row(item: dict[str, Any], decision: dict[str, Any], dag_goals: set[str],
              agent_domains: dict[str, str]) -> dict[str, Any]:
    expected = item["expected_goal"]
    shape = query_shape(expected, dag_goals)

    if shape == "out_of_scope":
        picked = "abstain" if is_abstain(decision) else leader_agent(decision)
        correct = is_abstain(decision)
    elif shape == "analytics":
        picked = resolved_dag_goal(decision, dag_goals)
        correct = picked == expected
    else:  # flat
        picked = leader_agent(decision)
        correct = picked == expected

    expected_domain = agent_domains.get(expected)
    picked_domain = agent_domains.get(picked)
    domain_correct = (picked_domain is not None and picked_domain == expected_domain) if shape != "out_of_scope" else None
    # A wrong-DOMAIN substitution: a clear/confuser row served a capability in a DIFFERENT domain
    # instead of abstaining or picking the right domain. (out_of_scope rows can't substitute — their
    # only correct action is abstain, already scored above.)
    wrong_domain_sub = (
        shape != "out_of_scope"
        and not correct
        and picked != "abstain"
        and expected_domain is not None
        and picked_domain != expected_domain
    )

    # top-k recall: was the expected capability anywhere in the candidate set (the reranker's reach)?
    topk_hit = expected in all_candidate_ids(decision) if shape != "out_of_scope" else None

    # optional per-row disposition expectations
    disposition_ok = None
    if item.get("expected_disposition"):
        disposition_ok = decision.get("overallDisposition") == item["expected_disposition"]
    denied_cap_ok = None
    if item.get("expected_denied_capability"):
        denied_cap_ok = item["expected_denied_capability"] in denied_capabilities(decision)

    return {
        **item,
        "shape": shape,
        "picked_goal": picked,
        "correct": correct,
        "expected_domain": expected_domain,
        "picked_domain": picked_domain,
        "domain_correct": domain_correct,
        "wrong_domain_sub": wrong_domain_sub,
        "topk_hit": topk_hit,
        "disposition_ok": disposition_ok,
        "denied_cap_ok": denied_cap_ok,
        "path": decision.get("path"),
        "preparation_version": decision.get("preparationVersion"),
        "mask_mode": decision.get("maskMode"),
        "relaxation_allowed": decision.get("relaxationAllowed"),
        "overall_disposition": decision.get("overallDisposition"),
        "fallback": bool((decision.get("resolver") or {}).get("fallback")),
        "top_score": (decision.get("resolver") or {}).get("topScore"),
        "selected_agent_ids": [c.get("agentId") for c in selected_candidates(decision)],
        "candidate_ids": all_candidate_ids(decision),
    }


# ── per-agent precision/recall + poaching ────────────────────────────────────────────────────

def per_agent_pr(rows: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    """precision = correct picks of A / times A was picked; recall = correct picks of A /
    rows whose expected_goal is A. Over clear+confuser rows with a concrete (non-abstain) label."""
    picked_total: dict[str, int] = defaultdict(int)
    picked_correct: dict[str, int] = defaultdict(int)
    expected_total: dict[str, int] = defaultdict(int)
    expected_correct: dict[str, int] = defaultdict(int)
    for r in rows:
        if r["shape"] == "out_of_scope":
            continue
        exp = r["expected_goal"]
        pick = r["picked_goal"]
        expected_total[exp] += 1
        if r["correct"]:
            expected_correct[exp] += 1
        if pick and pick != "abstain":
            picked_total[pick] += 1
            if r["correct"]:
                picked_correct[pick] += 1
    agents = set(picked_total) | set(expected_total)
    out: dict[str, dict[str, Any]] = {}
    for a in sorted(agents):
        pt, pc = picked_total[a], picked_correct[a]
        et, ec = expected_total[a], expected_correct[a]
        out[a] = {
            "precision": (pc / pt) if pt else None,
            "recall": (ec / et) if et else None,
            "picked": pt, "picked_correct": pc,
            "expected": et, "expected_correct": ec,
        }
    return out


def _canonical_poaching(rows: list[dict[str, Any]], tolerance: int):
    collisions: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        if row.get("category") != "canonical":
            continue
        expected = row.get("expected_goal")
        picked = row.get("picked_goal")
        if not expected or expected == "abstain" or not picked or picked == "abstain":
            continue
        if picked != expected:
            collisions[(picked, expected)].append(row)
    over = {pair: hits for pair, hits in collisions.items() if len(hits) > tolerance}
    return not over, over


# ── gate ─────────────────────────────────────────────────────────────────────────────────────

# Categories whose rows route to ONE specific capability and are therefore gated on exact-capability
# accuracy + wrong-domain-substitution + top-k recall. The cross_agent_confuser rows are IN the gate
# (spec Piece 6). name_invariance rows share an expected_goal across phrasings, so gating them enforces
# "route identically". capability_entity_conflict / alignment_miss / entity_only are scored on their own
# axes (denial disposition, or an intended abstain) and are deliberately NOT in this set.
CLEAR_CATEGORIES = {
    "canonical", "near_miss", "held_out", "cross_agent_confuser",
    "name_invariance", "multi_reference", "same_name_cross_domain", "multi_turn",
}


def summarize(rows: list[dict[str, Any]], thresholds: dict[str, float], expected_prep_version: str) -> int:
    clear_rows = [r for r in rows if r["category"] in CLEAR_CATEGORIES]
    out_rows = [r for r in rows if r["shape"] == "out_of_scope"]

    def rate(group, key="correct"):
        hits = sum(1 for r in group if r.get(key))
        n = len(group)
        return hits, n, (hits / n if n else 0.0)

    cap_hits, cap_n, cap_acc = rate(clear_rows)          # EXACT-capability accuracy (confusers included)
    ab_hits, ab_n, ab_acc = rate(out_rows)               # out-of-scope abstain rate

    domain_rows = [r for r in clear_rows if r["domain_correct"] is not None]
    dom_hits, dom_n, dom_acc = rate(domain_rows, "domain_correct")

    wrong_domain = [r for r in clear_rows if r.get("wrong_domain_sub")]

    topk_rows = [r for r in clear_rows if r["topk_hit"] is not None]
    topk_hits, topk_n, topk_acc = rate(topk_rows, "topk_hit")

    # path / version drift — a hard, non-negotiable failure.
    drift = [r for r in rows if r.get("path") != "production" or r.get("preparation_version") != expected_prep_version]

    print("\nProduction-path routing gate (Piece 6)")
    print(f"Queries: {len(rows)}   endpoint: POST /debug/route   expected prep-version: {expected_prep_version}")
    print(f"1. EXACT-capability accuracy (clear+confuser): {cap_acc:6.1%} ({cap_hits}/{cap_n})")
    print(f"2. Out-of-scope abstain rate                 : {ab_acc:6.1%} ({ab_hits}/{ab_n})")
    print(f"3. Domain-level accuracy (agent may differ)  : {dom_acc:6.1%} ({dom_hits}/{dom_n})")
    print(f"4. Wrong-domain substitutions                : {len(wrong_domain)} (must be 0)")
    print(f"5. Top-k recall (expected cap in candidates) : {topk_acc:6.1%} ({topk_hits}/{topk_n})")
    print()

    by_category: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        by_category[row["category"]].append(row)
    print("Accuracy by category")
    for category in sorted(by_category):
        group = by_category[category]
        label = "abstain" if category == "out_of_scope" else "accuracy"
        h, n, a = rate(group)
        print(f"{category:22} {label}: {a:6.1%} ({h}/{n})")
    print()

    # per-capability recall (gate the weakest)
    pr = per_agent_pr(clear_rows)
    print("Per-capability precision / recall (clear+confuser)")
    weakest_recall = 1.0
    weakest_agent = None
    for a, m in pr.items():
        prec = f"{m['precision']:5.1%}" if m['precision'] is not None else "  n/a"
        rec = f"{m['recall']:5.1%}" if m['recall'] is not None else "  n/a"
        print(f"  {a:40} P={prec} ({m['picked_correct']}/{m['picked']})  R={rec} ({m['expected_correct']}/{m['expected']})")
        if m['recall'] is not None and m['expected'] > 0 and m['recall'] < weakest_recall:
            weakest_recall = m['recall']
            weakest_agent = a
    print(f"  → weakest per-capability recall: {weakest_recall:.1%}" + (f" ({weakest_agent})" if weakest_agent else ""))
    print()

    # optional per-row disposition expectations
    disp_rows = [r for r in rows if r.get("disposition_ok") is not None]
    denied_rows = [r for r in rows if r.get("denied_cap_ok") is not None]
    if disp_rows:
        dh, dn, da = rate(disp_rows, "disposition_ok")
        print(f"Disposition expectations met: {da:6.1%} ({dh}/{dn})")
    if denied_rows:
        eh, en, ea = rate(denied_rows, "denied_cap_ok")
        print(f"Denied-capability expectations met: {ea:6.1%} ({eh}/{en})")
    if disp_rows or denied_rows:
        print()

    misses = [r for r in rows if not r["correct"]]
    if misses:
        print("Confusion list (misses)")
        for r in misses:
            selected = ", ".join(r["selected_agent_ids"])
            flags = []
            if r.get("domain_correct"):
                flags.append("right-domain")
            if r.get("wrong_domain_sub"):
                flags.append("WRONG-DOMAIN-SUB")
            if r.get("topk_hit") is False:
                flags.append("cap-absent-from-topk")
            flag = (" [" + ",".join(flags) + "]") if flags else ""
            print(f"- {r['id']} [{r['shape']}]: expected={r['expected_goal']} picked={r['picked_goal']}{flag} "
                  f"top_score={r['top_score']} selected=[{selected}]")
        print()

    poaching_tolerance = int(thresholds.get("canonical_poaching_tolerance", 0))
    no_poaching, collisions = _canonical_poaching(rows, poaching_tolerance)
    print("Canonical-intent poaching")
    if collisions:
        for (poacher, victim), hits in sorted(collisions.items()):
            ids = ", ".join(r["id"] for r in hits)
            print(f"- {poacher} -> {victim}: {len(hits)} queries ({ids})")
    else:
        print(f"none (tolerance={poaching_tolerance})")

    # ── gate decision ──────────────────────────────────────────────────────────────────────
    cap_floor = thresholds.get("capability_accuracy", thresholds.get("domain_accuracy", 0.9))
    abstain_floor = thresholds.get("abstain_rate", 1.0)
    min_cap_recall = thresholds.get("min_capability_recall", 0.0)
    topk_floor = thresholds.get("topk_recall", 0.0)

    failures: list[str] = []
    if drift:
        drift_ids = sorted({f"{r['id']}:{r.get('path')}/{r.get('preparation_version')}" for r in drift})
        failures.append(f"debug/prod drift on {len(drift)} row(s) (path/version mismatch): "
                        + ", ".join(drift_ids))
    # Accuracy/recall floors are only gated when the dataset actually carries rows of that kind, so a
    # focused dataset (e.g. capability_entity_conflict.json — no clear rows) is judged on its own axes.
    if cap_n > 0 and cap_acc < cap_floor:
        failures.append(f"exact-capability accuracy {cap_acc:.1%} below floor {cap_floor:.1%} ({cap_hits}/{cap_n})")
    if ab_n > 0 and ab_acc < abstain_floor:
        failures.append(f"out-of-scope abstain {ab_acc:.1%} below floor {abstain_floor:.1%} ({ab_hits}/{ab_n})")
    if wrong_domain:
        failures.append(f"wrong-domain substitutions = {len(wrong_domain)} (must be 0): "
                        + ", ".join(r["id"] for r in wrong_domain))
    if pr and weakest_recall < min_cap_recall:
        failures.append(f"min per-capability recall {weakest_recall:.1%} below floor {min_cap_recall:.1%}"
                        + (f" ({weakest_agent})" if weakest_agent else ""))
    if topk_n > 0 and topk_acc < topk_floor:
        failures.append(f"top-k recall {topk_acc:.1%} below floor {topk_floor:.1%} ({topk_hits}/{topk_n})")
    # Per-row disposition expectations are HARD (a conflict row that should deny must deny).
    if disp_rows:
        dh, dn, _ = rate(disp_rows, "disposition_ok")
        if dh < dn:
            bad = ", ".join(r["id"] for r in disp_rows if not r.get("disposition_ok"))
            failures.append(f"disposition expectation unmet on {dn - dh} row(s): {bad}")
    if denied_rows:
        eh, en, _ = rate(denied_rows, "denied_cap_ok")
        if eh < en:
            bad = ", ".join(r["id"] for r in denied_rows if not r.get("denied_cap_ok"))
            failures.append(f"denied-capability expectation unmet on {en - eh} row(s): {bad}")
    if not no_poaching:
        failures.append("canonical-intent poaching above tolerance")

    if failures:
        print("\nFAIL: decision gate not met")
        for f in failures:
            print(f"- {f}")
        return 1
    print("\nPASS: decision gate met")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default="eval/goal-pick/labeled_queries.json")
    parser.add_argument("--gateway-url", default="http://localhost:8080")
    parser.add_argument("--iam-url", default="http://localhost:8084")
    parser.add_argument("--password", default=os.environ.get("CONDUIT_DEMO_PASSWORD", "Meridian@2024"))
    parser.add_argument("--manifest-root", default="registry/manifests")
    parser.add_argument("--expected-prep-version", default=os.environ.get("CONDUIT_ROUTING_PREPARATION_VERSION", "route-prep-v2"))
    parser.add_argument("--output", default="/tmp/goal-pick-measurement.json")
    args = parser.parse_args()

    dataset_path = Path(args.dataset)
    with dataset_path.open() as f:
        dataset = json.load(f)

    dag_goals = load_dag_goals(Path(args.manifest_root))
    agent_domains = load_agent_domains(Path(args.manifest_root))
    token_cache: dict[str, str] = {}
    rows: list[dict[str, Any]] = []

    for item in dataset["queries"]:
        persona = item.get("persona")
        if not persona:
            print(f"ERROR {item['id']}: row has no persona (each row must carry its own principal)", file=sys.stderr)
            return 2
        try:
            token = token_for(persona, args.iam_url, args.password, token_cache)
            decision = decide_route(args.gateway_url, item, token)
        except urllib.error.HTTPError as exc:
            body = exc.read().decode(errors="replace")
            print(f"ERROR {item['id']} (persona={persona}): HTTP {exc.code}: {body}", file=sys.stderr)
            return 2
        except Exception as exc:
            print(f"ERROR {item['id']} (persona={persona}): {exc}", file=sys.stderr)
            return 2
        rows.append(score_row(item, decision, dag_goals, agent_domains))

    output = {
        "dataset": str(dataset_path),
        "gateway_url": args.gateway_url,
        "endpoint": "/debug/route",
        "expected_prep_version": args.expected_prep_version,
        "dag_goals": sorted(dag_goals),
        "summary": {"total": len(rows), "correct": sum(1 for r in rows if r["correct"])},
        "rows": rows,
    }
    Path(args.output).write_text(json.dumps(output, indent=2) + "\n")
    print(f"Wrote {args.output}")
    return summarize(rows, dataset.get("thresholds", {}), args.expected_prep_version)


if __name__ == "__main__":
    raise SystemExit(main())
