"""
Central configuration for the security+correctness E2E harness.

Every URL/credential is overridable via env var so the harness can point at a
different stack (e.g. CI) without code changes. Defaults match the local
`docker compose -p orchestrator-demo` core profile.
"""
from __future__ import annotations
import os

BFF_URL = os.getenv("CONDUIT_BFF_URL", "http://localhost:8099")
GATEWAY_URL = os.getenv("CONDUIT_GATEWAY_URL", "http://localhost:8080")
IAM_URL = os.getenv("CONDUIT_IAM_URL", "http://localhost:8084")
WEALTH_HTTP_URL = os.getenv("CONDUIT_WEALTH_HTTP_URL", "http://localhost:8081")
INSURANCE_HTTP_URL = os.getenv("CONDUIT_INSURANCE_HTTP_URL", "http://localhost:8087")

DEMO_PASSWORD = os.getenv("CONDUIT_DEMO_PASSWORD", "Meridian@2024")

# rm_jane is entitled to Whitman Family Office (REL-00042) + Calderon Trust (REL-00099).
# rm_carlos is entitled to REL-00099 ONLY — NOT REL-00042 (see scripts/seed-data/principals.json).
USER_ENTITLED = "rm_jane"
USER_NOT_ENTITLED = "rm_carlos"

# uw_sam — insurance underwriter, entitled to POL-77001/POL-77002 only (POL-88003 is
# uw_dana's — see scripts/seed-users.sh).
USER_INSURANCE_UNDERWRITER = "uw_sam"

WHITMAN_RELATIONSHIP_ID = "REL-00042"
WHITMAN_NAME = "Whitman Family Office"

CONTINENTAL_FREIGHT_POLICY_ID = "POL-77001"
CONTINENTAL_FREIGHT_NAME = "Continental Freight"

# How long we're willing to wait for a full fan-out + synthesis turn.
CHAT_TIMEOUT_S = int(os.getenv("CONDUIT_CHAT_TIMEOUT_S", "150"))

# Services that must answer before we run anything — fail fast rather than hang.
REQUIRED_SERVICES = {
    "conduit-chat (BFF)": f"{BFF_URL}/actuator/health",
    "conduit-gateway": f"{GATEWAY_URL}/actuator/health",
    "iam-service": f"{IAM_URL}/login",
    "wealth-http agent": f"{WEALTH_HTTP_URL}/health",
    "insurance-http agent": f"{INSURANCE_HTTP_URL}/health",
}
