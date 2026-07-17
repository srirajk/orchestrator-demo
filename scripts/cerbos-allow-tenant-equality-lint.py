#!/usr/bin/env python3
"""
Cerbos ALLOW tenant-equality lint  (Axiom Story B2.3 — the static expansion report).

Every EFFECT_ALLOW rule in a resource policy must be TENANT-SCOPED: it must reference a
derived role whose condition carries the tenant-equality backstop
(P.attr.tenant_id == R.attr.tenant_id), OR contain that equality directly in its own inline
condition. A raw-role ALLOW with neither is a cross-tenant BYPASS and fails the lint.

The single documented exception is the cross-tenant SUPERUSER role set (platform_admin) —
intentionally cross-tenant, and matching the pre-existing iam_resource posture.

Prints a per-rule expansion report (how each ALLOW is tenant-scoped) then the verdict.

Usage:
    python3 scripts/cerbos-allow-tenant-equality-lint.py [POLICIES_DIR ...]
Defaults to infra/cerbos/policies. Exit 0 = clean, 1 = a raw-role ALLOW bypass was found.
"""
import glob
import os
import re
import sys

import yaml

# Cross-tenant superuser roles — intentionally NOT tenant-scoped (documented allowlist).
CROSS_TENANT_SUPERUSER = {"platform_admin"}

# Tenant-equality signature in a CEL condition string (order-insensitive, whitespace-lax).
_TE = re.compile(r"P\.attr\.tenant_id\s*==\s*R\.attr\.tenant_id"
                 r"|R\.attr\.tenant_id\s*==\s*P\.attr\.tenant_id")


def _iter_strings(node):
    """Yield every string leaf in a nested dict/list condition subtree."""
    if isinstance(node, str):
        yield node
    elif isinstance(node, dict):
        for v in node.values():
            yield from _iter_strings(v)
    elif isinstance(node, list):
        for v in node:
            yield from _iter_strings(v)


def _has_tenant_equality(node) -> bool:
    """True if any CEL expr string in the condition subtree contains a P/R tenant_id
    equality. Walks the parsed structure (no YAML re-dump — dump line-wrapping could split
    the expression and defeat the match)."""
    if node is None:
        return False
    return any(_TE.search(" ".join(s.split())) for s in _iter_strings(node))


def load_docs(path):
    with open(path) as f:
        for doc in yaml.safe_load_all(f):
            if isinstance(doc, dict):
                yield doc, path


def derived_tenant_safe(files):
    """Map derivedRole name -> bool(condition carries tenant-equality)."""
    safe = {}
    for f in files:
        if f.endswith("_test.yaml") or f.endswith("_test.yml"):
            continue
        for doc, _ in load_docs(f):
            if "derivedRoles" not in doc:
                continue
            for d in doc["derivedRoles"].get("definitions", []) or []:
                name = d.get("name")
                if name:
                    safe[name] = _has_tenant_equality(d.get("condition"))
    return safe


def main(argv):
    dirs = argv[1:] or ["infra/cerbos/policies"]
    files = []
    for d in dirs:
        if os.path.isfile(d):
            files.append(d)
        else:
            for ext in ("yaml", "yml"):
                files += sorted(glob.glob(os.path.join(d, "**", f"*.{ext}"), recursive=True))

    derived_safe = derived_tenant_safe(files)
    violations = []
    allow_rules = 0
    print("ALLOW tenant-equality expansion report:")
    for f in files:
        if f.endswith("_test.yaml") or f.endswith("_test.yml"):
            continue
        for doc, path in load_docs(f):
            rp = doc.get("resourcePolicy")
            if not rp:
                continue
            resource = rp.get("resource")
            scope = rp.get("scope", "") or "(root)"
            # Tenant restriction children (REQUIRE_PARENTAL_CONSENT) can only NARROW the base;
            # they cannot out-grant it (ADR-axiom-scope-posture F3) and the base ceiling already
            # carries tenant-equality — so their raw-role ALLOWs are gated, not bypasses. B2.3
            # enforces tenant-equality on the CEILING (root / OVERRIDE_PARENT) policies.
            if rp.get("scopePermissions") == "SCOPE_PERMISSIONS_REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS":
                continue
            for rule in rp.get("rules", []) or []:
                if rule.get("effect") != "EFFECT_ALLOW":
                    continue
                allow_rules += 1
                roles = list(rule.get("roles", []) or [])
                droles = list(rule.get("derivedRoles", []) or [])
                inline_te = _has_tenant_equality(rule.get("condition"))
                reasons = []
                ok = True

                # Raw roles: gated by an inline tenant-equality, else must all be superuser.
                if roles:
                    if inline_te:
                        reasons.append(f"roles{roles} via inline tenant-equality")
                    elif all(r in CROSS_TENANT_SUPERUSER for r in roles):
                        reasons.append(f"roles{roles} = cross-tenant superuser (exempt)")
                    else:
                        ok = False
                        bad = [r for r in roles if r not in CROSS_TENANT_SUPERUSER]
                        reasons.append(f"RAW-ROLE BYPASS roles{bad} (no tenant-equality)")

                # Derived roles: each must carry tenant-equality (or an inline one on the rule).
                for dr in droles:
                    if derived_safe.get(dr) or inline_te:
                        reasons.append(f"derivedRole '{dr}' carries tenant-equality")
                    else:
                        ok = False
                        reasons.append(f"DERIVED BYPASS '{dr}' (no tenant-equality in its condition)")

                mark = "OK  " if ok else "FAIL"
                print(f"  {mark} {resource}@{scope} {rule.get('actions')}: {'; '.join(reasons)}")
                if not ok:
                    violations.append((resource, scope, rule.get("actions"), os.path.relpath(path)))

    print(f"\ncerbos-allow-tenant-equality-lint: {allow_rules} ALLOW rule(s) checked, "
          f"{len(violations)} raw/derived cross-tenant bypass(es).")
    if violations:
        print("\nBYPASSES (an ALLOW that is not tenant-scoped and not a documented superuser):")
        for resource, scope, actions, path in violations:
            print(f"  FAIL  {resource}@{scope} actions={actions} in {path}")
        return 1
    print("OK — every ALLOW is tenant-scoped (derived-role backstop / inline equality) or a documented superuser.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
