"""
Wealth Coverage Service — mock implementation of the DISCOVER / CHECK / RESOLVE pipeline.

Endpoints
---------
GET  /coverage/{principal_id}                → discover all resources in principal's book
GET  /coverage/{principal_id}/resources/{id} → check single resource
POST /entities/resolve                       → resolve free-text reference to canonical ID
GET  /health                                 → liveness probe

Port: 8086
"""

from __future__ import annotations

import logging
from typing import Optional

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from data import check, discover, resolve
from jwt_verify import verify_bearer_token

log = logging.getLogger("wealth-coverage")
logging.basicConfig(level=logging.INFO)

app = FastAPI(
    title="Wealth Coverage Service",
    description="DISCOVER / CHECK / RESOLVE pipeline for wealth-domain relationships",
    version="1.0.0",
)


@app.middleware("http")
async def jwt_auth_middleware(request: Request, call_next):
    if request.url.path in ("/health", "/openapi.json", "/docs", "/redoc"):
        return await call_next(request)
    allowed, error, claims = verify_bearer_token(request.headers.get("Authorization"))
    if not allowed:
        log.warning("wealth-coverage: rejected request — %s (path=%s)", error, request.url.path)
        return JSONResponse(status_code=401, content={"detail": error})
    response = await call_next(request)
    if claims and claims.get("sub"):
        response.headers["X-Conduit-Verified-Sub"] = claims["sub"]
    return response


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
    type: str = "relationship"
    # RESOLVE is principal-agnostic (World-B invariant 5): resolution is book-INDEPENDENT and
    # scoped only by tenant, exactly like DISCOVER/CHECK. principal_id is optional and used for
    # audit only — never to filter candidates. The CHECK gate is the sole authorization boundary.
    principal_id: Optional[str] = None


# ── endpoints ────────────────────────────────────────────────────────────────


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "service": "wealth-coverage"}


@app.get(
    "/coverage/{principal_id}",
    response_model=list[CoverageResource],
    summary="DISCOVER — list all relationships in the principal's book",
)
def discover_resources(principal_id: str, request: Request) -> list[CoverageResource]:
    tenant_id = request.headers.get("X-Tenant-Id", "default")
    log.info("DISCOVER principal=%s tenant=%s", principal_id, tenant_id)
    resources = discover(principal_id)
    return [CoverageResource(**r) for r in resources]


@app.get(
    "/coverage/{principal_id}/resources/{resource_id}",
    response_model=CoverageCheckResult,
    summary="CHECK — verify a principal may access a specific relationship",
)
def check_resource(principal_id: str, resource_id: str, request: Request) -> CoverageCheckResult:
    tenant_id = request.headers.get("X-Tenant-Id", "default")
    log.info("CHECK principal=%s resource=%s tenant=%s", principal_id, resource_id, tenant_id)
    result = check(principal_id, resource_id)
    return CoverageCheckResult(**result)


@app.post(
    "/entities/resolve",
    response_model=CoverageResolveResult,
    summary="RESOLVE — map free-text reference to canonical relationship ID",
)
def resolve_entity(body: ResolveRequest, request: Request) -> CoverageResolveResult:
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

    uvicorn.run("main:app", host="0.0.0.0", port=8086, reload=False)
