#!/usr/bin/env python3
"""
migrate-selects-to-cel.py — F2 codemod: translate manifest JMESPath expressions to CEL dialect.

Rewrites, in place, every manifest-expression site in registry/manifests/**:

  io.consumes[].select              multiselect-hash  → {'k': has(input.v) ? input.v : null, ...}   (RootVar.INPUT)
  io.map.over                       bare/dotted field → input.<path>                                  (RootVar.INPUT)
  io.map.item_select                multiselect-hash  → {'k': has(item.v) ? item.v : null, ...}       (RootVar.ITEM)
  io.condition                      `N` cmp           → input.<field> <op> <N>                        (RootVar.INPUT)
  io.produces[].figures[].path      dotted path       → has(output.a)&&has(output.a.b) ? output.a.b : null  (OUTPUT)
  io.produces[].entities[].select   arr[].id          → output.arr.map(x, x.id)                       (OUTPUT)

The translation is UNCONDITIONALLY guarded (every field wrapped in has(...)), reproducing JMESPath's
key-present-value-null shape so InputContractValidator attributes the same producer/field and one absent
field cannot collapse a whole edge to MissingNode. The gateway's CEL engine refuses to compile any
un-migrated (bare-identifier) expression at ingest — there is no dual-language mode.

This is the SAME translator EngineParityTest embeds; a divergence is a parity-test failure, not silent
production drift. Idempotent: an already-CEL expression (contains 'has(' or 'input.'/'item.'/'output.')
is left untouched.

Usage:  python3 scripts/migrate-selects-to-cel.py [--check] [registry/manifests]
        --check exits 1 if any file WOULD change (CI drift guard); default rewrites in place.
"""
import json
import re
import sys
from pathlib import Path


def already_cel(expr: str) -> bool:
    return isinstance(expr, str) and (
        "has(" in expr or "input." in expr or "item." in expr or "output." in expr
    )


def guard_path(root: str, dotted: str) -> str:
    """a.b.c  ->  has(root.a) && has(root.a.b) && has(root.a.b.c) ? root.a.b.c : null"""
    segs = dotted.split(".")
    access = root
    guards = []
    for s in segs:
        access = f"{access}.{s}"
        guards.append(f"has({access})")
    return f"{' && '.join(guards)} ? {access} : null"


def translate_hash(expr: str, root: str) -> str:
    """{k: v, ...} -> {'k': has(root.v) ? root.v : null, ...}   (v may be dotted)"""
    body = expr.strip()[1:-1].strip()
    parts = []
    for pair in body.split(","):
        key, val = pair.split(":", 1)
        key, val = key.strip(), val.strip()
        if "." in val:
            guarded = guard_path(root, val)
            parts.append(f"'{key}': {guarded}")
        else:
            parts.append(f"'{key}': has({root}.{val}) ? {root}.{val} : null")
    return "{" + ", ".join(parts) + "}"


def translate_over(expr: str, root: str = "input") -> str:
    """failed -> input.failed   (bare/dotted field path)"""
    return f"{root}.{expr.strip()}"


def translate_condition(expr: str) -> str:
    """breach_count > `0`  ->  input.breach_count > 0   (strip JMESPath backtick literals, prefix fields)"""
    e = re.sub(r"`([^`]*)`", r"\1", expr)  # `0` -> 0
    # prefix each bare identifier chain that is not a number/keyword/already-prefixed with input.
    def prefix(m):
        tok = m.group(0)
        if tok in ("true", "false", "null", "and", "or", "not"):
            return tok
        if tok.startswith("input.") or tok.startswith("item.") or tok.startswith("output."):
            return tok
        return "input." + tok
    return re.sub(r"[A-Za-z_][A-Za-z0-9_.]*", prefix, e)


def translate_entity_select(expr: str) -> str:
    """arr[].id -> output.arr.map(x, x.id)  (JMESPath list-projection -> CEL map macro)"""
    m = re.match(r"^([\w.]+)\[\]\.([\w.]+)$", expr.strip())
    if not m:
        # bare scalar id path -> guarded navigation over output
        return guard_path("output", expr.strip())
    return f"output.{m.group(1)}.map(x, x.{m.group(2)})"


def migrate(obj) -> bool:
    """Walk one manifest dict, rewriting expressions in place. Returns True if changed."""
    changed = False
    io = obj.get("io")
    if not isinstance(io, dict):
        return False

    for consume in io.get("consumes", []) or []:
        sel = consume.get("select")
        if isinstance(sel, str) and sel.strip().startswith("{") and not already_cel(sel):
            consume["select"] = translate_hash(sel, "input")
            changed = True

    mp = io.get("map")
    if isinstance(mp, dict):
        over = mp.get("over")
        if isinstance(over, str) and not already_cel(over):
            mp["over"] = translate_over(over, "input")
            changed = True
        isel = mp.get("item_select")
        if isinstance(isel, str) and isel.strip().startswith("{") and not already_cel(isel):
            mp["item_select"] = translate_hash(isel, "item")
            changed = True

    cond = io.get("condition")
    if isinstance(cond, str) and not already_cel(cond):
        io["condition"] = translate_condition(cond)
        changed = True

    for produce in io.get("produces", []) or []:
        for fig in produce.get("figures", []) or []:
            path = fig.get("path")
            if isinstance(path, str) and not already_cel(path):
                fig["path"] = guard_path("output", path)
                changed = True
        for ent in produce.get("entities", []) or []:
            sel = ent.get("select")
            if isinstance(sel, str) and not already_cel(sel):
                ent["select"] = translate_entity_select(sel)
                changed = True

    return changed


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    check = "--check" in sys.argv
    root = Path(args[0]) if args else Path("registry/manifests")
    if not root.is_dir():
        print(f"not a directory: {root}", file=sys.stderr)
        return 2

    would_change = []
    for f in sorted(root.rglob("*.json")):
        data = json.loads(f.read_text())
        if migrate(data):
            would_change.append(f)
            if not check:
                f.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")

    if check:
        if would_change:
            print("NEEDS MIGRATION (JMESPath expressions found):")
            for f in would_change:
                print(f"  {f}")
            return 1
        print("clean — all manifest expressions are CEL dialect")
        return 0

    for f in would_change:
        print(f"migrated {f}")
    print(f"{len(would_change)} manifest(s) migrated to CEL dialect")
    return 0


if __name__ == "__main__":
    sys.exit(main())
