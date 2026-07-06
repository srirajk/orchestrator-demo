# Conduit demo seeder — a small, purpose-built image that seeds the demo over the
# Docker network with NO host dependency (no docker exec, no host python, no docker
# socket). It does EXACTLY two things, in order, via scripts/seed-demo.sh:
#   1. Langfuse   → python3 seed-langfuse-models.py  (register prices BEFORE any traffic)
#   2. convos     → python3 seed-conversations-via-bff.py  (real OIDC login via the BFF)
# It does NOT seed Redis principals — that is the separate `seed-users` service.
# The two-step orchestration lives in scripts/seed-demo-container.sh.
FROM python:3.12-slim

# curl for the Langfuse readiness probe; bash to run the entry script.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl bash ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# seed-conversations-via-bff.py needs requests; the Langfuse seeder uses stdlib urllib.
RUN pip install --no-cache-dir requests

# The compose service mounts ./scripts:/scripts:ro and ./registry:/registry:ro and
# invokes bash /scripts/seed-demo-container.sh, so no COPY is required here.
WORKDIR /scripts

CMD ["bash", "/scripts/seed-demo-container.sh"]
