#!/usr/bin/env python3
"""
Cerbos DECISION PARITY MATRIX  (Axiom Story B2.1 — THE safety gate).

Proves the base/tenant scoped migration is BYTE-IDENTICAL for the current single-tenant
world: it probes the FULL persona × resource-kind × action matrix against two live Cerbos
servers — one loading the PRE-migration policies, one loading the POST-migration policies —
and asserts every cell resolves to the identical EFFECT_*. Any single flip ⇒ exit 1.

The principal roster mirrors scripts/seed-iam-users.py (every SEEDED persona) with the
attributes the gateway's CerbosEntitlementAdapter actually sends: segments (per-segment
classification map), domains (EMPTY — the JWT sets no `domains` claim), admin_domains (EMPTY),
and NO tenant_id (the adapter does not send it today — this is why the tenant-equality
backstop is parity-neutral). A small synthetic IAM roster (tenant_id + IAM roles) additionally
exercises the iam-resource derived-role paths that no chat persona reaches.

Usage:
    cerbos-parity-matrix.py --pre-url http://localhost:3620 --post-url http://localhost:3621
    cerbos-parity-matrix.py --url http://localhost:3621 --scope-repro   # base "" vs "default"
Exit 0 = every cell identical, 1 = at least one flip.
"""
import argparse
import json
import sys
import urllib.request

# ── PRINCIPALS ────────────────────────────────────────────────────────────────
# Gateway-faithful attrs: segments map; domains=[]; admin_domains=[]; NO tenant_id.
PII, CONF, INT = "confidential-pii", "confidential", "internal"


def chat(pid, segments):
    return {"id": pid, "roles": ["chat_user"],
            "attr": {"segments": segments, "domains": [], "admin_domains": []}}


PRINCIPALS = [
    # ── Seeded chat personas (scripts/seed-iam-users.py) ──
    chat("rm_jane", {"wealth": PII, "servicing": CONF}),
    chat("rm_carlos", {"wealth": PII}),
    chat("rm_guest", {"wealth": PII}),
    chat("uw_sam", {"insurance": PII}),
    chat("uw_dana", {"insurance": PII}),
    chat("analyst_amy", {"wealth": CONF}),
    chat("rm_nakamura", {"wealth": PII}),
    chat("comm_banker_okoro", {"servicing": CONF}),
    chat("wealth_adv_bianchi", {"wealth": CONF}),
    chat("ops_analyst_singh", {"servicing": PII}),
    chat("ins_uw_costa", {"insurance": PII}),
    chat("hr_partner_lund", {"hr": CONF}),
    chat("treasury_moreau", {"servicing": CONF}),
    chat("multi_rm_fischer", {"wealth": PII, "insurance": CONF}),
    # ── Seeded insights admin ──
    {"id": "insights_admin", "roles": ["conduit_admin"],
     "attr": {"segments": {}, "domains": [], "admin_domains": []}},
    # ── Synthetic IAM roster (NOT seeded chat personas) — exercises iam-resource paths.
    #    Carries tenant_id so same_tenant / own_resource / auditor derived roles can fire. ──
    {"id": "iam_tenant_admin", "roles": ["tenant_admin"], "attr": {"tenant_id": "default"}},
    {"id": "iam_auditor", "roles": ["auditor"], "attr": {"tenant_id": "default"}},
    {"id": "iam_policy_author", "roles": ["policy_author"], "attr": {"tenant_id": "default"}},
    {"id": "iam_owner_self", "roles": ["user"], "attr": {"tenant_id": "default"}},
    {"id": "iam_crosstenant_admin", "roles": ["tenant_admin"], "attr": {"tenant_id": "other"}},
]

# ── RESOURCES ─────────────────────────────────────────────────────────────────
def agent(rid, domain, audience, mode, klass):
    return {"kind": "agent", "id": rid,
            "attr": {"domain": domain, "audience": audience,
                     "access_mode": mode, "data_classification": klass},
            "actions": ["invoke", "invoke_membership"]}


RESOURCES = [
    # agents: domain × classification × audience × access_mode + the rank-0 trap
    agent("ag_wealth_pii", "wealth-management", "segment", "read", "confidential-pii"),
    agent("ag_wealth_conf", "wealth-management", "segment", "read", "confidential"),
    agent("ag_wealth_internal", "wealth-management", "segment", "read", "internal"),
    agent("ag_servicing_conf", "asset-servicing", "segment", "read", "confidential"),
    agent("ag_servicing_pii", "asset-servicing", "segment", "read", "confidential-pii"),
    agent("ag_insurance_pii", "insurance", "segment", "read", "confidential-pii"),
    agent("ag_insurance_conf", "insurance", "segment", "read", "confidential"),
    agent("ag_enterprise", "hr", "enterprise", "read", "internal"),
    agent("ag_write", "wealth-management", "segment", "write", "confidential"),
    agent("ag_typo_class", "wealth-management", "segment", "read", "public"),  # rank-0 trap
    # relationship
    {"kind": "relationship", "id": "REL-00042", "attr": {}, "actions": ["read"]},
    # domain (id == org-domain name)
    {"kind": "domain", "id": "wealth-private-banking", "attr": {}, "actions": ["view", "manage_members"]},
    {"kind": "domain", "id": "insurance-underwriting", "attr": {}, "actions": ["view"]},
    # insights
    {"kind": "insights", "id": "insights", "attr": {}, "actions": ["read"]},
    # iam-resource (tenant_id + owner_id + resource_type)
    {"kind": "iam-resource", "id": "u-self", "attr": {"resource_type": "user", "tenant_id": "default", "owner_id": "iam_owner_self"},
     "actions": ["read", "update", "delete", "list"]},
    {"kind": "iam-resource", "id": "u-other", "attr": {"resource_type": "user", "tenant_id": "default", "owner_id": "someone_else"},
     "actions": ["read", "update"]},
    {"kind": "iam-resource", "id": "pol-1", "attr": {"resource_type": "policy", "tenant_id": "default"},
     "actions": ["create", "read", "approve_policy", "deploy_policy"]},
    {"kind": "iam-resource", "id": "audit-1", "attr": {"resource_type": "audit_log", "tenant_id": "default"},
     "actions": ["read", "list", "export"]},
    {"kind": "iam-resource", "id": "u-crosstenant", "attr": {"resource_type": "user", "tenant_id": "other", "owner_id": "iam_owner_self"},
     "actions": ["read", "update"]},
]


def check(base_url, principal, resource, scope):
    res = {"kind": resource["kind"], "id": resource["id"], "attr": resource["attr"]}
    if scope is not None:
        res["scope"] = scope
    body = json.dumps({
        "principal": {"id": principal["id"], "roles": principal["roles"], "attr": principal["attr"]},
        "resources": [{"resource": res, "actions": resource["actions"]}],
    }).encode()
    req = urllib.request.Request(base_url + "/api/check/resources", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as r:
        out = json.load(r)
    results = out.get("results") or []
    if not results:
        return {a: "<NO-RESULT>" for a in resource["actions"]}
    return dict(results[0]["actions"])


def matrix(base_url, scope):
    m = {}
    for p in PRINCIPALS:
        for res in RESOURCES:
            effects = check(base_url, p, res, scope)
            for action, effect in effects.items():
                m[(p["id"], res["kind"], res["id"], action)] = effect
    return m


def diff(a, b, label_a, label_b):
    flips = []
    for key in sorted(a):
        if a[key] != b.get(key):
            flips.append((key, a[key], b.get(key)))
    print(f"\ncerbos-parity-matrix: {len(a)} cells compared ({label_a} vs {label_b}).")
    if flips:
        print(f"\n❌ {len(flips)} DECISION FLIP(S) — migration is NOT byte-identical:")
        for (pid, kind, rid, action), ea, eb in flips:
            print(f"  FLIP  principal={pid} {kind}/{rid} action={action}: {label_a}={ea}  {label_b}={eb}")
        return 1
    print(f"✅ ALL {len(a)} CELLS IDENTICAL — byte-identical decisions.")
    return 0


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--pre-url")
    ap.add_argument("--post-url")
    ap.add_argument("--url")
    ap.add_argument("--scope-repro", action="store_true",
                    help="probe --url at base scope '' vs tenant scope 'default' and diff")
    ap.add_argument("--dump", action="store_true", help="print the full matrix table")
    args = ap.parse_args(argv[1:])

    if args.scope_repro:
        base = matrix(args.url, "")
        deflt = matrix(args.url, "default")
        rc = diff(base, deflt, "base(scope='')", "tenant(scope='default')")
        return rc

    pre = matrix(args.pre_url, "")   # gateway sends no scope → base ("")
    post = matrix(args.post_url, "")
    if args.dump:
        for key in sorted(post):
            print(f"  {key} -> {post[key]}")
    return diff(pre, post, "PRE", "POST")


if __name__ == "__main__":
    sys.exit(main(sys.argv))
