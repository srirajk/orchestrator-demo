# Conduit demo seeder — one small, purpose-built image that seeds ALL demo data over the
# Docker network with NO host dependency (no docker exec, no host python, no docker socket).
# The single orchestration script scripts/seed-all.sh runs every step, in order, idempotently:
#   1. Principals        → Redis   (redis-cli HSET; scripts/seed-users.sh)
#   2. Langfuse prices   → python3 seed-langfuse-models.py   (BEFORE any traffic)
#   3. BFF conversations → python3 seed-conversations-via-bff.py  (real OIDC via the BFF)
#   4. Langfuse datasets → python3 /eval/langfuse_seed_datasets.py (conduit-routing/-synthesis)
#   5. Langfuse dashboard→ psql  seed-data/langfuse-dashboard.sql  (into langfuse-db)
# One image with: python3 + requests + langfuse (datasets SDK), curl, redis-cli, psql.
FROM python:3.12-slim

# curl        — Langfuse readiness probe
# bash        — run the entry + step scripts
# redis-tools — redis-cli for the principal HSET block
# postgresql-client — psql/pg_isready for the dashboard SQL
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        curl bash ca-certificates redis-tools postgresql-client \
    && rm -rf /var/lib/apt/lists/*

# requests → seed-conversations-via-bff.py ; langfuse → langfuse_seed_datasets.py.
# (seed-langfuse-models.py uses only stdlib urllib.)
RUN pip install --no-cache-dir requests langfuse

# The compose service mounts ./scripts:/scripts:ro, ./registry:/registry:ro and
# ./eval:/eval:ro, then invokes bash /scripts/seed-all.sh, so no COPY is required here.
WORKDIR /scripts

CMD ["bash", "/scripts/seed-all.sh"]
