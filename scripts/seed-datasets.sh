#!/bin/sh
# seed-datasets.sh — boot provisioner step: seed the Langfuse golden datasets
# (conduit-routing, conduit-synthesis) idempotently, retrying until they actually
# land. Health=200 is not enough — Langfuse can accept the request before its
# project/store is ready to persist items (it then "seeds 0"). So we seed AND
# verify the datasets are listed, retrying until they are.
set -u

for i in $(seq 1 60); do
  python3 /app/langfuse_seed_datasets.py 2>&1 || true

  present=$(python3 - <<'PY'
import os, json, base64, urllib.request
try:
    k = base64.b64encode(
        (os.environ["LANGFUSE_PUBLIC_KEY"] + ":" + os.environ["LANGFUSE_SECRET_KEY"]).encode()
    ).decode()
    req = urllib.request.Request(os.environ["LANGFUSE_HOST"] + "/api/public/datasets")
    req.add_header("Authorization", "Basic " + k)
    data = json.load(urllib.request.urlopen(req, timeout=5)).get("data", [])
    names = {d.get("name") for d in data}
    print(len({"conduit-routing", "conduit-synthesis"} & names))
except Exception:
    print(0)
PY
)

  if [ "${present:-0}" -ge 2 ]; then
    echo "[seed-datasets] datasets present ($present/2) — done"
    exit 0
  fi
  echo "[seed-datasets] langfuse/project not ready yet (present=$present) — retry $i/60 in 6s"
  sleep 6
done

echo "[seed-datasets] gave up after 60 tries"
exit 1
