"""
Entity-ID format validators for Insurance HTTP agents.

Format contracts
----------------
- Policies : ``POL-\\d+``  e.g. POL-77001
- Claims   : ``CLM-\\d+``  e.g. CLM-5501

Return contract
---------------
Each helper returns **None** when the format is valid, or a **JSONResponse(422)**
when the format is wrong.  Callers do::

    err = validate_policy_id(policy_id, AGENT_ID)
    if err is not None:
        return err

Status-code semantics (distinct from HTTP 422 for missing required fields):
  400 — both ids absent  (caller raises this; not in scope here)
  404 — valid-format id but not found in canned store
  422 — id present but format is wrong (wrong prefix, missing digits, etc.)
"""
import re
from fastapi.responses import JSONResponse
from shared.error_schema import error_response

_POLICY_RE = re.compile(r"^POL-\d+$")
_CLAIM_RE  = re.compile(r"^CLM-\d+$")


def validate_policy_id(policy_id: str, agent_id: str) -> JSONResponse | None:
    """Return a 422 JSONResponse if *policy_id* does not match ``POL-\\d+``, else None."""
    if not _POLICY_RE.match(policy_id):
        return error_response(
            422,
            f"invalid format: expected POL-NNNNN, got {policy_id!r}",
            agent_id,
        )
    return None


def validate_claim_id(claim_id: str, agent_id: str) -> JSONResponse | None:
    """Return a 422 JSONResponse if *claim_id* does not match ``CLM-\\d+``, else None."""
    if not _CLAIM_RE.match(claim_id):
        return error_response(
            422,
            f"invalid format: expected CLM-NNNNN, got {claim_id!r}",
            agent_id,
        )
    return None
