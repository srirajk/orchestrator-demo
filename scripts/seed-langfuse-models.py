#!/usr/bin/env python3
"""
seed-langfuse-models.py — Register Conduit's model prices in Langfuse's model config.

Config-driven (World B): prices come ONLY from registry/model-prices.json — never from
Java or from this script. Once a model is registered as a PROJECT-scoped model with a
populated `prices` map, Langfuse auto-computes per-observation cost from the token counts
that the gateway already emits (llm.token_count.* on the synthesis/generation spans).

Idempotent: any existing PROJECT-scoped model with the same modelName is deleted first,
then re-created, so re-running always converges to the config. Langfuse's built-in
(managed) models are left untouched — a project model with the same matchPattern takes
precedence, so we override e.g. the managed `gpt-4o-mini` (whose `prices` map ships empty
and therefore yields cost 0).

Everything is a PARAMETER — no hardcoded URLs or keys:
  --langfuse-url   (default env LANGFUSE_URL or http://localhost:3030)
  --public-key     (default env LANGFUSE_PROJECT_PUBLIC_KEY)
  --secret-key     (default env LANGFUSE_PROJECT_SECRET_KEY)
  --config         (default registry/model-prices.json next to this script's repo root)

Usage:
  LANGFUSE_PROJECT_PUBLIC_KEY=pk-... LANGFUSE_PROJECT_SECRET_KEY=sk-... \
    python3 scripts/seed-langfuse-models.py --langfuse-url http://localhost:3030
"""
import argparse
import base64
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CONFIG = REPO_ROOT / "registry" / "model-prices.json"


def _auth_header(public_key: str, secret_key: str) -> str:
    raw = f"{public_key}:{secret_key}".encode()
    return "Basic " + base64.b64encode(raw).decode()


def _request(method: str, url: str, auth: str, body: dict | None = None) -> dict:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", auth)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            payload = r.read().decode()
            return json.loads(payload) if payload else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode()[:300]
        raise RuntimeError(f"{method} {url} -> HTTP {e.code}: {detail}") from e


def _match_pattern(model_name: str) -> str:
    # Exact, case-insensitive match on the model name (dots escaped).
    return "(?i)^(" + re.escape(model_name) + ")$"


def _existing_project_models(base: str, auth: str) -> list[dict]:
    out, page = [], 1
    while True:
        d = _request("GET", f"{base}/api/public/models?limit=100&page={page}", auth)
        rows = d.get("data", [])
        out.extend(rows)
        meta = d.get("meta", {})
        if page >= meta.get("totalPages", 1) or not rows:
            break
        page += 1
    return [m for m in out if not m.get("isLangfuseManaged", False)]


def main() -> int:
    ap = argparse.ArgumentParser(description="Register model prices in Langfuse.")
    ap.add_argument("--langfuse-url", default=os.environ.get("LANGFUSE_URL", "http://localhost:3030"))
    ap.add_argument("--public-key", default=os.environ.get("LANGFUSE_PROJECT_PUBLIC_KEY", ""))
    ap.add_argument("--secret-key", default=os.environ.get("LANGFUSE_PROJECT_SECRET_KEY", ""))
    ap.add_argument("--config", default=str(DEFAULT_CONFIG))
    args = ap.parse_args()

    if not args.public_key or not args.secret_key:
        print("ERROR: Langfuse public/secret key required (flags or "
              "LANGFUSE_PROJECT_PUBLIC_KEY / LANGFUSE_PROJECT_SECRET_KEY env).", file=sys.stderr)
        return 2

    base = args.langfuse_url.rstrip("/")
    auth = _auth_header(args.public_key, args.secret_key)

    cfg = json.loads(Path(args.config).read_text())
    unit = cfg.get("unit", "TOKENS")
    models: dict = cfg.get("models", {})
    if not models:
        print("ERROR: config has no models", file=sys.stderr)
        return 2

    print(f"[seed-models] Langfuse={base}  config={args.config}  models={len(models)}")

    existing = _existing_project_models(base, auth)
    by_name: dict[str, list[str]] = {}
    for m in existing:
        by_name.setdefault(m.get("modelName", ""), []).append(m.get("id"))

    created = 0
    for name, spec in models.items():
        # Delete any prior project-scoped definition(s) for idempotency.
        for mid in by_name.get(name, []):
            try:
                _request("DELETE", f"{base}/api/public/models/{mid}", auth)
                print(f"[seed-models]   deleted prior project model {name} ({mid})")
            except RuntimeError as e:
                print(f"[seed-models]   warn: could not delete {name} ({mid}): {e}")

        in_price = float(spec["inputPerMillion"]) / 1_000_000.0
        out_price = float(spec["outputPerMillion"]) / 1_000_000.0
        body = {
            "modelName": name,
            "matchPattern": _match_pattern(name),
            "unit": unit,
            "inputPrice": in_price,
            "outputPrice": out_price,
        }
        tok = spec.get("tokenizer")
        if tok:
            body["tokenizerId"] = tok
            body["tokenizerConfig"] = {"tokenizerModel": name}
        res = _request("POST", f"{base}/api/public/models", auth, body)
        print(f"[seed-models]   registered {name}: in=${spec['inputPerMillion']}/M "
              f"out=${spec['outputPerMillion']}/M  id={res.get('id')} prices={res.get('prices')}")
        created += 1

    print(f"[seed-models] done — {created} model price(s) registered. "
          f"Cost is computed for FRESH traffic only (Langfuse prices at ingestion).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
