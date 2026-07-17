#!/usr/bin/env python3
"""
Cerbos tenant-scope TOTALITY lint  (Axiom scope-posture, contract #2).

Empirically (cerbos 0.53.0, see docs/adr/ADR-axiom-scope-posture.md): under
SCOPE_PERMISSIONS_REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS, a tenant (scoped) policy that
is SILENT on a base-allowed (resource, action, role) tuple does NOT deny it — the
tuple FALLS THROUGH and inherits the base ceiling's ALLOW (fail-open).

This lint rejects any tenant policy that leaves a base-allowed tuple UNMATCHED. A
tuple is "covered" iff the tenant policy has a rule (ALLOW or DENY) whose actions
include the action (or "*") AND whose roles include the role (or "*"). An uncovered
tuple is a fall-through hole → FAIL.

Usage:
    python3 scripts/cerbos-tenant-totality-lint.py [POLICIES_DIR ...]
Defaults to infra/cerbos/policies. Exit 0 = clean, 1 = violations found.
"""
import sys, glob, os
import yaml

WILDCARD = "*"


def load_docs(path):
    with open(path) as f:
        for doc in yaml.safe_load_all(f):
            if isinstance(doc, dict) and "resourcePolicy" in doc:
                yield doc["resourcePolicy"], path


def rule_tuples(rp):
    """Yield (action, role, effect) the policy has an explicit opinion on."""
    for rule in rp.get("rules", []) or []:
        effect = rule.get("effect", "")
        roles = rule.get("roles", []) or []
        actions = rule.get("actions", []) or []
        for a in actions:
            for r in roles:
                yield a, r, effect


def scope_ancestors(scope):
    """Strict ancestors of a dot-delimited scope, nearest first, incl. root ''."""
    parts = scope.split(".") if scope else []
    out = []
    for i in range(len(parts) - 1, 0, -1):
        out.append(".".join(parts[:i]))
    out.append("")  # root
    return out


def covers(tenant_rules, action, role):
    for a, r, _eff in tenant_rules:
        if (a == action or a == WILDCARD) and (r == role or r == WILDCARD):
            return True
    return False


def main(argv):
    dirs = argv[1:] or ["infra/cerbos/policies"]
    files = []
    for d in dirs:
        if os.path.isfile(d):
            files.append(d)
        else:
            files += sorted(glob.glob(os.path.join(d, "**", "*.yaml"), recursive=True))
            files += sorted(glob.glob(os.path.join(d, "**", "*.yml"), recursive=True))

    # Index policies by (resource, scope).
    by_key = {}   # (resource, scope) -> {"allows": set[(a,r)], "rules": [(a,r,eff)], "sp": str, "path": str}
    for f in files:
        if f.endswith("_test.yaml") or f.endswith("_test.yml"):
            continue
        for rp, path in load_docs(f):
            resource = rp.get("resource")
            scope = rp.get("scope", "") or ""
            entry = by_key.setdefault((resource, scope), {"allows": set(), "rules": [], "sp": None, "path": path})
            entry["sp"] = rp.get("scopePermissions")
            for a, r, eff in rule_tuples(rp):
                entry["rules"].append((a, r, eff))
                if eff == "EFFECT_ALLOW":
                    entry["allows"].add((a, r))

    violations = []
    tenant_count = 0
    # A "tenant" policy = any resourcePolicy with a non-empty scope.
    for (resource, scope), entry in sorted(by_key.items()):
        if scope == "":
            continue  # base/root — it IS the ceiling, nothing above it
        tenant_count += 1
        # Ceiling = union of ALLOWs from every strict ancestor scope for this resource.
        ceiling = set()
        for anc in scope_ancestors(scope):
            anc_entry = by_key.get((resource, anc))
            if anc_entry:
                ceiling |= anc_entry["allows"]
        # scopePermissions sanity (warn only).
        if entry["sp"] != "SCOPE_PERMISSIONS_REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS":
            print(f"  WARN  {resource}@{scope} ({os.path.relpath(entry['path'])}): "
                  f"scopePermissions is {entry['sp']!r}, expected "
                  f"SCOPE_PERMISSIONS_REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS")
        # Totality: every ceiling tuple must be explicitly covered by this tenant.
        for (action, role) in sorted(ceiling):
            if not covers(entry["rules"], action, role):
                violations.append((resource, scope, action, role, os.path.relpath(entry["path"])))

    print(f"\ncerbos-tenant-totality-lint: {tenant_count} tenant-scoped policy(ies) checked, "
          f"{len(violations)} uncovered base-allowed tuple(s).")
    if violations:
        print("\nFALL-THROUGH HOLES (base-allowed tuple with no explicit tenant rule → inherits parent ALLOW):")
        for resource, scope, action, role, path in violations:
            print(f"  FAIL  {resource}@{scope}: action={action!r} role={role!r} "
                  f"uncovered → add an explicit EFFECT_DENY (or move to grants) in {path}")
        return 1
    print("OK — every base-allowed tuple is explicitly covered by its tenant scope(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
