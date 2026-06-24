"""
Meridian User Management Service

Standalone service that manages RM (Relationship Manager) principals and issues JWTs.
In production this would be backed by an IdP (Keycloak, Okta, Auth0) — this service
demonstrates the concept with a Redis-backed user store and HS256 JWTs.

The gateway reads JWT claims directly — it does NOT call back to this service on the
hot path. This service is the admin plane; the gateway is the enforcement plane.

Endpoints:
  GET  /users                       list all principals
  GET  /users/{user_id}             get one principal
  POST /users                       create a new principal
  PUT  /users/{user_id}             replace a principal
  PATCH /users/{user_id}/book       add/remove relationships from the RM's book
  DELETE /users/{user_id}           delete a principal
  POST /auth/token                  issue a short-lived JWT for the given user_id (demo only)
  GET  /health                      service health
"""

import json
import os
import time
import hmac
import hashlib
import base64
from typing import Optional, List

import redis as redis_lib
from fastapi import FastAPI, HTTPException, Path
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

app = FastAPI(
    title="Meridian User Management",
    description="Manages RM principals and their entitlement books",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── Redis connection ──────────────────────────────────────────────────────────

REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
JWT_SECRET = os.getenv("JWT_SECRET", "meridian-demo-secret-change-in-production")
KEY_PREFIX = "principal:"

_redis: Optional[redis_lib.Redis] = None

def get_redis() -> redis_lib.Redis:
    global _redis
    if _redis is None:
        _redis = redis_lib.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)
    return _redis

# ── Seed principals on startup ────────────────────────────────────────────────

DEMO_PRINCIPALS = [
    {
        "id": "rm_jane",
        "name": "Jane Smith",
        "email": "jane.smith@meridianbank.com",
        "roles": ["relationship_manager"],
        "book": ["REL-00042", "REL-00099"],
        "segments": ["wealth"],
        "clearance": 2,
        "team": "wealth-private-banking"
    },
    {
        "id": "rm_okafor",
        "name": "Chidi Okafor",
        "email": "chidi.okafor@meridianbank.com",
        "roles": ["relationship_manager"],
        "book": ["REL-00188", "REL-00200"],
        "segments": ["wealth"],
        "clearance": 2,
        "team": "wealth-private-banking"
    },
    {
        "id": "admin",
        "name": "Admin User",
        "email": "admin@meridianbank.com",
        "roles": ["admin"],
        "book": [],
        "segments": ["wealth", "servicing"],
        "clearance": 5,
        "team": "platform"
    },
    {
        "id": "rm_chen",
        "name": "Emily Chen",
        "email": "emily.chen@meridianbank.com",
        "roles": ["relationship_manager"],
        "book": ["REL-00042"],
        "segments": ["wealth"],
        "clearance": 2,
        "team": "wealth-ultra-hnw"
    }
]

@app.on_event("startup")
def seed():
    r = get_redis()
    for p in DEMO_PRINCIPALS:
        key = KEY_PREFIX + p["id"]
        existing = r.hgetall(key)
        if not existing:
            r.hset(key, mapping={
                "id":        p["id"],
                "name":      p.get("name", p["id"]),
                "email":     p.get("email", ""),
                "roles":     json.dumps(p["roles"]),
                "book":      json.dumps(p["book"]),
                "segments":  json.dumps(p.get("segments", [])),
                "clearance": str(p.get("clearance", 2)),
                "team":      p.get("team", ""),
            })
    print(f"[user-mgmt] Seeded {len(DEMO_PRINCIPALS)} demo principals")

# ── Models ────────────────────────────────────────────────────────────────────

class PrincipalView(BaseModel):
    id: str
    name: str = ""
    email: str = ""
    roles: List[str] = []
    book: List[str] = []
    segments: List[str] = []
    clearance: int = 2
    team: str = ""

class CreatePrincipalRequest(BaseModel):
    name: str
    email: str = ""
    roles: List[str] = ["relationship_manager"]
    book: List[str] = []
    segments: List[str] = ["wealth"]
    clearance: int = 2
    team: str = ""

class BookPatchRequest(BaseModel):
    add: List[str] = []
    remove: List[str] = []

class TokenRequest(BaseModel):
    user_id: str

# ── Helper ────────────────────────────────────────────────────────────────────

def redis_to_principal(user_id: str, fields: dict) -> PrincipalView:
    return PrincipalView(
        id=fields.get("id", user_id),
        name=fields.get("name", user_id),
        email=fields.get("email", ""),
        roles=json.loads(fields.get("roles", "[]")),
        book=json.loads(fields.get("book", "[]")),
        segments=json.loads(fields.get("segments", "[]")),
        clearance=int(fields.get("clearance", "2")),
        team=fields.get("team", ""),
    )

def write_principal(user_id: str, p: PrincipalView):
    get_redis().hset(KEY_PREFIX + user_id, mapping={
        "id":        user_id,
        "name":      p.name,
        "email":     p.email,
        "roles":     json.dumps(p.roles),
        "book":      json.dumps(p.book),
        "segments":  json.dumps(p.segments),
        "clearance": str(p.clearance),
        "team":      p.team,
    })

# ── Minimal HS256 JWT (no external lib needed) ────────────────────────────────

def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

def issue_jwt(user_id: str, principal: PrincipalView, ttl_seconds: int = 3600) -> str:
    """Issue a demo HS256 JWT containing all claim attributes."""
    header = _b64url(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    now = int(time.time())
    payload = _b64url(json.dumps({
        "sub":      user_id,
        "name":     principal.name,
        "email":    principal.email,
        "roles":    principal.roles,
        "book":     principal.book,
        "segments": principal.segments,
        "clearance":principal.clearance,
        "team":     principal.team,
        "iat":      now,
        "exp":      now + ttl_seconds,
        "iss":      "meridian-user-mgmt",
    }).encode())
    signing_input = f"{header}.{payload}"
    sig = _b64url(hmac.new(JWT_SECRET.encode(), signing_input.encode(), hashlib.sha256).digest())
    return f"{signing_input}.{sig}"

# ── Routes ────────────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    try:
        get_redis().ping()
        return {"status": "ok", "redis": "connected"}
    except Exception as e:
        return {"status": "degraded", "redis": str(e)}

@app.get("/users", response_model=List[PrincipalView])
def list_users():
    r = get_redis()
    keys = r.keys(KEY_PREFIX + "*")
    principals = []
    for key in sorted(keys):
        fields = r.hgetall(key)
        if fields:
            user_id = key.removeprefix(KEY_PREFIX)
            principals.append(redis_to_principal(user_id, fields))
    return principals

@app.get("/users/{user_id}", response_model=PrincipalView)
def get_user(user_id: str = Path(...)):
    fields = get_redis().hgetall(KEY_PREFIX + user_id)
    if not fields:
        raise HTTPException(status_code=404, detail=f"User '{user_id}' not found")
    return redis_to_principal(user_id, fields)

@app.post("/users", response_model=PrincipalView, status_code=201)
def create_user(user_id: str, body: CreatePrincipalRequest):
    r = get_redis()
    if r.exists(KEY_PREFIX + user_id):
        raise HTTPException(status_code=409, detail=f"User '{user_id}' already exists")
    p = PrincipalView(id=user_id, name=body.name, email=body.email,
                      roles=body.roles, book=body.book, segments=body.segments,
                      clearance=body.clearance, team=body.team)
    write_principal(user_id, p)
    return p

@app.put("/users/{user_id}", response_model=PrincipalView)
def upsert_user(user_id: str, body: CreatePrincipalRequest):
    p = PrincipalView(id=user_id, name=body.name, email=body.email,
                      roles=body.roles, book=body.book, segments=body.segments,
                      clearance=body.clearance, team=body.team)
    write_principal(user_id, p)
    return p

@app.patch("/users/{user_id}/book", response_model=PrincipalView)
def patch_book(user_id: str, body: BookPatchRequest):
    fields = get_redis().hgetall(KEY_PREFIX + user_id)
    if not fields:
        raise HTTPException(status_code=404, detail=f"User '{user_id}' not found")
    current = redis_to_principal(user_id, fields)
    book = set(current.book)
    book.update(body.add)
    book -= set(body.remove)
    current.book = sorted(book)
    write_principal(user_id, current)
    return current

@app.delete("/users/{user_id}")
def delete_user(user_id: str):
    if not get_redis().exists(KEY_PREFIX + user_id):
        raise HTTPException(status_code=404, detail=f"User '{user_id}' not found")
    get_redis().delete(KEY_PREFIX + user_id)
    return {"deleted": user_id}

@app.post("/auth/token")
def issue_token(body: TokenRequest):
    """
    Demo-only: issue a JWT for a given user_id.
    In production this would be a full OAuth2 password/client-credentials flow via your IdP.
    The JWT contains all user attributes as claims so the gateway can enforce entitlements
    without a database lookup on the hot path.
    """
    fields = get_redis().hgetall(KEY_PREFIX + body.user_id)
    if not fields:
        raise HTTPException(status_code=404, detail=f"User '{body.user_id}' not found")
    principal = redis_to_principal(body.user_id, fields)
    token = issue_jwt(body.user_id, principal)
    return {
        "access_token": token,
        "token_type": "Bearer",
        "expires_in": 3600,
        "user": principal,
        "note": "demo-only HS256 — use a real IdP (Keycloak/Auth0/Okta) in production"
    }
