#!/usr/bin/env python3
"""Audit a Meridian agent directory for production compliance."""
import sys, os, ast, re, json
from pathlib import Path

# ── CEL expression lint (F2 migration) ────────────────────────────────────────────────────────────
# Manifest expressions (io.consumes[].select, io.map.over/item_select, io.condition,
# io.produces[].figures[].path, io.produces[].entities[].select) are CEL rooted at input/item/output.
# The gateway REFUSES to start on a legacy JMESPath-dialect string (a bare identifier is an undeclared
# reference at ingest). Flag the tell-tale JMESPath shapes so they are caught before deployment.
EXPR_KEYS = {"select", "over", "item_select", "condition", "path"}


def _is_jmespath_dialect(expr) -> bool:
    if not isinstance(expr, str) or not expr.strip():
        return False
    if "has(" in expr or expr.startswith(("input.", "item.", "output.")) or ".map(" in expr:
        return False  # already CEL
    if re.search(r"\{\s*[A-Za-z_]\w*\s*:", expr):   # unquoted multiselect-hash {failed: failed}
        return True
    if "`" in expr:                                  # backtick literal: breach_count > `0`
        return True
    if "[]." in expr:                                # list projection: items[].id
        return True
    if re.fullmatch(r"[A-Za-z_][\w.]*", expr.strip()):  # bare/dotted path: aging.max
        return True
    return False


def _walk_expressions(node, hits):
    if isinstance(node, dict):
        for k, v in node.items():
            if k in EXPR_KEYS and _is_jmespath_dialect(v):
                hits.append(f"{k}: {v}")
            _walk_expressions(v, hits)
    elif isinstance(node, list):
        for item in node:
            _walk_expressions(item, hits)


def check_cel_expressions(path: Path):
    """Return (ok, offenders) — offenders are legacy JMESPath-dialect manifest expressions."""
    offenders = []
    for f in path.rglob("*.json"):
        try:
            data = json.loads(f.read_text(errors="ignore"))
        except Exception:
            continue
        if not (isinstance(data, dict) and "io" in data):
            continue
        hits = []
        _walk_expressions(data.get("io"), hits)
        offenders += [f"{f.name}: {h}" for h in hits]
    return (len(offenders) == 0, offenders)

REQUIRED_PATTERNS = {
    # agent_span() is this project's OTel instrumentation pattern
    "otel_middleware": (r"FastAPIInstrumentor|OTLPSpanExporter|agent_span|opentelemetry", "OTel instrumentation"),
    "jwt_verify": (r"jwt_verify|JWTVerif|verify_token|Authorization.*Bearer", "JWT verification"),
    "health_endpoint": (r'"/health"|@app.get.*health|@router.get.*health', "Health endpoint"),
    "fault_knobs": (r"_delay_ms|_fail|delay_ms|force_fail", "Fault knobs"),
    "error_schema": (r"agent_id.*trace_id|trace_id.*agent_id|ErrorResponse", "Standard error schema"),
    "canned_data": (r"canned_data|HOLDINGS|SETTLEMENTS|CUSTODY", "Canned data pattern"),
    "structured_logging": (r"convId|conv_id|conversationId|traceId|trace_id", "Structured logging"),
}

def check_agent(path: Path) -> dict:
    results = {}
    all_code = ""
    for f in path.rglob("*.py"):
        all_code += f.read_text(errors="ignore")
    for key, (pattern, label) in REQUIRED_PATTERNS.items():
        found = bool(re.search(pattern, all_code))
        results[key] = {"label": label, "found": found}
    return results

def main():
    path = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    results = check_agent(path)
    passed = sum(1 for v in results.values() if v["found"])
    total = len(results)
    print(f"\nMeridian Agent Compliance: {passed}/{total}")
    print("-" * 50)
    for k, v in results.items():
        status = "✅" if v["found"] else "❌"
        print(f"  {status} {v['label']}")
    cel_ok, offenders = check_cel_expressions(path)
    print(f"  {'✅' if cel_ok else '❌'} CEL manifest expressions (no legacy JMESPath dialect)")
    for o in offenders:
        print(f"       ↳ legacy JMESPath expression: {o}")

    score = passed / total
    print(f"\nCompliance Score: {score:.0%}")
    sys.exit(0 if (score == 1.0 and cel_ok) else 1)

if __name__ == "__main__":
    main()
