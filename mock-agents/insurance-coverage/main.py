"""
Insurance Coverage Service — mock implementation of the DISCOVER / CHECK / RESOLVE pipeline.

The insurance parallel of the wealth-coverage service: it answers the two
authorization questions for an underwriter's book of policies.

Endpoints
---------
GET  /coverage/{principal_id}                → discover all policies in principal's book
GET  /coverage/{principal_id}/resources/{id} → check single policy
POST /entities/resolve                       → resolve free-text reference to canonical ID
GET  /health                                 → liveness probe

Port: 8088
"""

from __future__ import annotations

import logging
from typing import Optional

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from data import check, discover, owner_tenant, resolve
from jwt_verify import verify_bearer_token, verify_tenant_binding

log = logging.getLogger("insurance-coverage")
logging.basicConfig(level=logging.INFO)

app = FastAPI(
    title="Insurance Coverage Service",
    description="DISCOVER / CHECK / RESOLVE pipeline for insurance-domain policies",
    version="1.0.0",
)


@app.middleware("http")
async def jwt_auth_middleware(request: Request, call_next):
    if request.url.path in ("/health", "/openapi.json", "/docs", "/redoc"):
        return await call_next(request)
    allowed, error, claims = verify_bearer_token(request.headers.get("Authorization"))
    if not allowed:
        log.warning("insurance-coverage: rejected request — %s (path=%s)", error, request.url.path)
        return JSONResponse(status_code=401, content={"detail": error})
    # Stash the verified claims so the data endpoints can run the A5 tenant-binding gate
    # (token tenant_id ⇔ X-Tenant-Id header ⇔ book-owner tenant).
    request.state.claims = claims
    response = await call_next(request)
    if claims and claims.get("sub"):
        response.headers["X-Conduit-Verified-Sub"] = claims["sub"]
    return response


def _enforce_tenant(request: Request, book_owner_tenant: Optional[str]) -> None:
    """A5 second gate. Rejects with HTTP 403 (from THIS service) when the verified token
    tenant, the X-Tenant-Id header, and the requested book's owning tenant do not all
    agree. ``book_owner_tenant=None`` for principal-agnostic RESOLVE."""
    claims = getattr(request.state, "claims", None)
    header_tenant = request.headers.get("X-Tenant-Id")
    ok, reason = verify_tenant_binding(claims, header_tenant, book_owner_tenant)
    if not ok:
        log.warning("insurance-coverage: tenant binding rejected — %s (path=%s)", reason, request.url.path)
        raise HTTPException(status_code=403, detail=reason)


# ── response models ──────────────────────────────────────────────────────────


class CoverageResource(BaseModel):
    id: str
    label: str
    sub_domain: str


class CoverageCheckResult(BaseModel):
    allowed: bool
    reason: str


class ResolveCandidate(BaseModel):
    id: str
    name: str


class CoverageResolveResult(BaseModel):
    resolved: bool
    id: Optional[str] = None
    canonical_name: Optional[str] = None
    candidates: Optional[list[ResolveCandidate]] = None


# ── request models ───────────────────────────────────────────────────────────


class ResolveRequest(BaseModel):
    reference: str
    type: str = "policy"
    # RESOLVE is principal-agnostic (World-B invariant 5): resolution is book-INDEPENDENT and
    # scoped only by tenant, exactly like DISCOVER/CHECK. principal_id is optional and used for
    # audit only — never to filter candidates. The CHECK gate is the sole authorization boundary.
    principal_id: Optional[str] = None


# ── endpoints ────────────────────────────────────────────────────────────────


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "service": "insurance-coverage"}


@app.get(
    "/coverage/{principal_id}",
    response_model=list[CoverageResource],
    summary="DISCOVER — list all policies in the principal's book",
)
def discover_resources(principal_id: str, request: Request) -> list[CoverageResource]:
    _enforce_tenant(request, owner_tenant(principal_id))
    tenant_id = request.headers.get("X-Tenant-Id", "default")
    log.info("DISCOVER principal=%s tenant=%s", principal_id, tenant_id)
    resources = discover(principal_id)
    return [CoverageResource(**r) for r in resources]


@app.get(
    "/coverage/{principal_id}/resources/{resource_id}",
    response_model=CoverageCheckResult,
    summary="CHECK — verify a principal may access a specific policy",
)
def check_resource(principal_id: str, resource_id: str, request: Request) -> CoverageCheckResult:
    _enforce_tenant(request, owner_tenant(principal_id))
    tenant_id = request.headers.get("X-Tenant-Id", "default")
    log.info("CHECK principal=%s resource=%s tenant=%s", principal_id, resource_id, tenant_id)
    result = check(principal_id, resource_id)
    return CoverageCheckResult(**result)


@app.post(
    "/entities/resolve",
    response_model=CoverageResolveResult,
    summary="RESOLVE — map free-text reference to canonical policy ID",
)
def resolve_entity(body: ResolveRequest, request: Request) -> CoverageResolveResult:
    # RESOLVE is principal-agnostic (World-B invariant 5): there is no book owner to bind,
    # so only the token⇔header tenant agreement is enforced (book_owner_tenant=None).
    _enforce_tenant(request, None)
    tenant_id = request.headers.get("X-Tenant-Id", "default")
    log.info(
        "RESOLVE reference=%r type=%s tenant=%s principal=%s (audit-only)",
        body.reference,
        body.type,
        tenant_id,
        body.principal_id,
    )
    result = resolve(body.reference, body.type, body.principal_id)
    candidates = (
        [ResolveCandidate(**c) for c in result["candidates"]]
        if result.get("candidates") is not None
        else None
    )
    return CoverageResolveResult(
        resolved=result["resolved"],
        id=result.get("id"),
        canonical_name=result.get("canonical_name"),
        candidates=candidates,
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8088, reload=False)
