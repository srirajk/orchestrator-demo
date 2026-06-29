#!/usr/bin/env python3
"""Audit a Meridian agent directory for production compliance."""
import sys, os, ast, re
from pathlib import Path

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
    score = passed / total
    print(f"\nCompliance Score: {score:.0%}")
    sys.exit(0 if score == 1.0 else 1)

if __name__ == "__main__":
    main()
