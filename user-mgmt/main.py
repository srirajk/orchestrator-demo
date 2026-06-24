"""
Meridian User Management Service

Standalone service that manages RM (Relationship Manager) principals and issues JWTs.
In production this would be backed by an IdP (Keycloak, Okta, Auth0) — this service
demonstrates the concept with a Redis-backed user store and RS256 JWTs.

The gateway reads JWT claims directly — it does NOT call back to this service on the
hot path. This service is the admin plane; the gateway is the enforcement plane.

Endpoints:
  GET  /users                       list all principals
  GET  /users/{user_id}             get one principal
  POST /users                       create a new principal
  PUT  /users/{user_id}             replace a principal
  PATCH /users/{user_id}/book       add/remove relationships from the RM's book
  DELETE /users/{user_id}           delete a principal
  POST /auth/token                  issue a short-lived RS256 JWT for the given user_id (demo only)
  GET  /health                      service health
  GET  /.well-known/jwks.json       RSA public key in JWK Set format

Domain / Membership endpoints:
  POST   /domains                                create domain
  GET    /domains                                list all domains
  GET    /domains/{domain_id}                    get one domain
  PUT    /domains/{domain_id}/relationships       update relationships list
  POST   /domains/{domain_id}/members            add user to domain
  DELETE /domains/{domain_id}/members/{user_id}  remove user from domain
  GET    /domains/{domain_id}/members            list members of domain
  GET    /users/{user_id}/domains                list domains the user belongs to

Phase 10 — Domain admin endpoints:
  POST   /domains/{domain_id}/admins            make a user a domain admin
  DELETE /domains/{domain_id}/admins/{user_id}  remove domain admin grant
  GET    /domains/{domain_id}/admins            list domain admins
"""

import base64
import json
import math
import os
import time
from pathlib import Path
from typing import List, Optional

import redis as redis_lib
import yaml
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import FastAPI, HTTPException, Path as FPath
from fastapi.middleware.cors import CORSMiddleware
from jose import jwt
from pydantic import BaseModel

app = FastAPI(
    title="Meridian User Management",
    description="Manages RM principals, domain memberships, and issues RS256 JWTs",
    version="3.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── RSA keypair management ─────────────────────────────────────────────────────

_rsa_private_key: rsa.RSAPrivateKey = None  # type: ignore[assignment]
_private_key_pem: str = ""

KEY_ID = "meridian-key-1"


def _load_or_generate_keypair() -> None:
    global _rsa_private_key, _private_key_pem

    env_pem_b64 = os.getenv("RS256_PRIVATE_KEY_PEM", "")
    if env_pem_b64:
        pem_bytes = base64.b64decode(env_pem_b64)
        _rsa_private_key = serialization.load_pem_private_key(pem_bytes, password=None)
        _private_key_pem = pem_bytes.decode()
        print("[user-mgmt] Loaded RS256 private key from RS256_PRIVATE_KEY_PEM env var")
    else:
        _rsa_private_key = rsa.generate_private_key(
            public_exponent=65537,
            key_size=2048,
        )
        _private_key_pem = _rsa_private_key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        ).decode()
        print("[user-mgmt] Generated fresh RS256 keypair (ephemeral — tokens invalidate on restart)")


def _int_to_b64url(n: int) -> str:
    """Encode a big integer as base64url (no padding), big-endian bytes."""
    length = math.ceil(n.bit_length() / 8)
    return base64.urlsafe_b64encode(n.to_bytes(length, "big")).rstrip(b"=").decode()


# ── Redis connection ──────────────────────────────────────────────────────────

REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
KEY_PREFIX = "principal:"
DOMAIN_PREFIX = "domain:"
MEMBERSHIP_PREFIX = "membership:"
USER_DOMAINS_PREFIX = "user_domains:"
DOMAIN_ADMINS_PREFIX = "domain_admins:"      # set of user_ids who admin this domain
USER_ADMIN_DOMAINS_PREFIX = "user_admin_domains:"  # set of domain_ids this user admins

_redis: Optional[redis_lib.Redis] = None


def get_redis() -> redis_lib.Redis:
    global _redis
    if _redis is None:
        _redis = redis_lib.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)
    return _redis


# ── Domain helpers ────────────────────────────────────────────────────────────

def get_user_domains(user_id: str) -> List[str]:
    """Return list of domain_ids the user belongs to (member of)."""
    r = get_redis()
    members = r.smembers(USER_DOMAINS_PREFIX + user_id)
    return sorted(members) if members else []


def get_user_admin_domains(user_id: str) -> List[str]:
    """Return list of domain_ids the user is an admin of."""
    r = get_redis()
    ad = r.smembers(USER_ADMIN_DOMAINS_PREFIX + user_id)
    return sorted(ad) if ad else []


def get_book_from_domains(user_id: str) -> List[str]:
    """Union of relationships across all domains the user is a member of."""
    r = get_redis()
    domain_ids = get_user_domains(user_id)
    book: set = set()
    for domain_id in domain_ids:
        raw = r.hget(DOMAIN_PREFIX + domain_id, "relationships")
        if raw:
            rels = json.loads(raw)
            book.update(rels)
    return sorted(book)


# ── Org seed loading ──────────────────────────────────────────────────────────

ORG_YAML_PATH = Path(__file__).parent / "seed" / "org.yaml"


def _load_org_yaml() -> Optional[dict]:
    if ORG_YAML_PATH.exists():
        with open(ORG_YAML_PATH) as f:
            return yaml.safe_load(f)
    return None


def _seed_from_yaml(org: dict) -> None:
    r = get_redis()
    principals = org.get("principals", [])
    domains = org.get("domains", [])
    domain_admins = org.get("domain_admins", [])

    # Seed principals (idempotent: create if not exists; always sync roles)
    for p in principals:
        uid = p["id"]
        key = KEY_PREFIX + uid
        if not r.exists(key):
            r.hset(key, mapping={
                "id":        uid,
                "name":      p.get("name", uid),
                "email":     p.get("email", ""),
                "roles":     json.dumps(p.get("roles", ["relationship_manager"])),
                "book":      json.dumps(p.get("book", [])),
                "segments":  json.dumps(p.get("segments", [])),
                "clearance": str(p.get("clearance", 2)),
                "team":      p.get("team", ""),
            })
        else:
            # Always sync roles — allows upgrading admin → platform_admin on restart
            r.hset(key, "roles", json.dumps(p.get("roles", ["relationship_manager"])))
    print(f"[user-mgmt] Seeded {len(principals)} principals from org.yaml")

    # Seed domains and memberships
    for d in domains:
        domain_key = DOMAIN_PREFIX + d["id"]
        if not r.exists(domain_key):
            r.hset(domain_key, mapping={
                "id":            d["id"],
                "name":          d["name"],
                "relationships": json.dumps(d.get("relationships", [])),
            })
        for member_id in d.get("members", []):
            r.sadd(USER_DOMAINS_PREFIX + member_id, d["id"])
            r.sadd(MEMBERSHIP_PREFIX + d["id"], member_id)
    print(f"[user-mgmt] Seeded {len(domains)} domains from org.yaml")

    # Seed domain admin grants
    for grant in domain_admins:
        uid = grant["user_id"]
        for domain_id in grant.get("domains", []):
            r.sadd(USER_ADMIN_DOMAINS_PREFIX + uid, domain_id)
            r.sadd(DOMAIN_ADMINS_PREFIX + domain_id, uid)
    if domain_admins:
        print(f"[user-mgmt] Seeded {len(domain_admins)} domain admin grants from org.yaml")


def _seed_inline() -> None:
    """Fallback seed when no org.yaml is found."""
    r = get_redis()

    demo_principals = [
        {
            "id": "rm_jane",
            "name": "Jane Smith",
            "email": "jane.smith@meridianbank.com",
            "roles": ["relationship_manager"],
            "book": ["REL-00042", "REL-00099"],
            "segments": ["wealth"],
            "clearance": 2,
            "team": "wealth-private-banking",
        },
        {
            "id": "rm_okafor",
            "name": "Chidi Okafor",
            "email": "chidi.okafor@meridianbank.com",
            "roles": ["relationship_manager"],
            "book": ["REL-00188", "REL-00200"],
            "segments": ["wealth"],
            "clearance": 2,
            "team": "wealth-private-banking",
        },
        {
            "id": "admin",
            "name": "Admin User",
            "email": "admin@meridianbank.com",
            "roles": ["platform_admin"],
            "book": [],
            "segments": ["wealth", "servicing"],
            "clearance": 5,
            "team": "platform",
        },
        {
            "id": "rm_chen",
            "name": "Emily Chen",
            "email": "emily.chen@meridianbank.com",
            "roles": ["relationship_manager"],
            "book": ["REL-00042"],
            "segments": ["wealth"],
            "clearance": 2,
            "team": "wealth-ultra-hnw",
        },
    ]

    demo_domains = [
        {
            "id": "wealth-private-banking",
            "name": "Wealth Private Banking",
            "relationships": ["REL-00042", "REL-00099"],
            "members": ["rm_jane", "rm_chen"],
        },
        {
            "id": "intl-wealth",
            "name": "International Wealth",
            "relationships": ["REL-00188", "REL-00200"],
            "members": ["rm_okafor"],
        },
        {
            "id": "admin-domain",
            "name": "Admin Domain",
            "relationships": [],
            "members": ["admin"],
        },
    ]

    for p in demo_principals:
        uid = p["id"]
        key = KEY_PREFIX + uid
        if not r.exists(key):
            r.hset(key, mapping={
                "id":        uid,
                "name":      p.get("name", uid),
                "email":     p.get("email", ""),
                "roles":     json.dumps(p.get("roles", [])),
                "book":      json.dumps(p.get("book", [])),
                "segments":  json.dumps(p.get("segments", [])),
                "clearance": str(p.get("clearance", 2)),
                "team":      p.get("team", ""),
            })
    print(f"[user-mgmt] Seeded {len(demo_principals)} demo principals (inline fallback)")

    for d in demo_domains:
        domain_key = DOMAIN_PREFIX + d["id"]
        if not r.exists(domain_key):
            r.hset(domain_key, mapping={
                "id":            d["id"],
                "name":          d["name"],
                "relationships": json.dumps(d.get("relationships", [])),
            })
        for member_id in d.get("members", []):
            r.sadd(USER_DOMAINS_PREFIX + member_id, d["id"])
            r.sadd(MEMBERSHIP_PREFIX + d["id"], member_id)
    print(f"[user-mgmt] Seeded {len(demo_domains)} demo domains (inline fallback)")


@app.on_event("startup")
def seed():
    _load_or_generate_keypair()
    org = _load_org_yaml()
    if org:
        print(f"[user-mgmt] Loading org seed from {ORG_YAML_PATH}")
        _seed_from_yaml(org)
    else:
        print("[user-mgmt] No org.yaml found — using inline seed")
        _seed_inline()


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
    admin_domains: List[str] = []


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


class DomainCreate(BaseModel):
    id: str
    name: str
    relationships: List[str] = []


class RelationshipsUpdate(BaseModel):
    relationships: List[str]


class MemberAdd(BaseModel):
    user_id: str


class AdminGrant(BaseModel):
    user_id: str


# ── Helpers ───────────────────────────────────────────────────────────────────

def redis_to_principal(user_id: str, fields: dict) -> PrincipalView:
    ad = get_user_admin_domains(user_id)
    return PrincipalView(
        id=fields.get("id", user_id),
        name=fields.get("name", user_id),
        email=fields.get("email", ""),
        roles=json.loads(fields.get("roles", "[]")),
        book=json.loads(fields.get("book", "[]")),
        segments=json.loads(fields.get("segments", "[]")),
        clearance=int(fields.get("clearance", "2")),
        team=fields.get("team", ""),
        admin_domains=ad,
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


# ── RS256 JWT issuance ────────────────────────────────────────────────────────

def issue_jwt(user_id: str, principal: PrincipalView, ttl_seconds: int = 3600) -> str:
    """Issue a RS256 JWT containing all claim attributes.
    Book is derived from domain memberships; admin_domains from domain admin grants."""
    now = int(time.time())
    derived_book = get_book_from_domains(user_id)
    effective_book = derived_book if derived_book else principal.book
    domains = get_user_domains(user_id)
    admin_domains = get_user_admin_domains(user_id)

    payload = {
        "sub":          user_id,
        "name":         principal.name,
        "email":        principal.email,
        "roles":        principal.roles,
        "book":         effective_book,
        "segments":     principal.segments,
        "clearance":    principal.clearance,
        "domains":      domains,
        "admin_domains": admin_domains,
        "iat":          now,
        "exp":          now + ttl_seconds,
        "iss":          "meridian-user-mgmt",
        "aud":          "meridian-gateway",
    }
    return jwt.encode(
        payload,
        _private_key_pem,
        algorithm="RS256",
        headers={"kid": KEY_ID},
    )


# ── Routes: JWKS ──────────────────────────────────────────────────────────────

@app.get("/.well-known/jwks.json")
def jwks():
    """Return RSA public key in JWK Set format for gateway JWT verification."""
    pub = _rsa_private_key.public_key()
    pub_numbers = pub.public_numbers()
    return {
        "keys": [
            {
                "kty": "RSA",
                "use": "sig",
                "alg": "RS256",
                "kid": KEY_ID,
                "n": _int_to_b64url(pub_numbers.n),
                "e": _int_to_b64url(pub_numbers.e),
            }
        ]
    }


# ── Routes: health ────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    try:
        get_redis().ping()
        return {
            "status": "ok",
            "redis": "connected",
            "jwt_algorithm": "RS256",
            "key_id": KEY_ID,
            "org_seed": str(ORG_YAML_PATH) if ORG_YAML_PATH.exists() else "inline",
        }
    except Exception as e:
        return {
            "status": "degraded",
            "redis": str(e),
            "jwt_algorithm": "RS256",
            "key_id": KEY_ID,
        }


# ── Routes: users ─────────────────────────────────────────────────────────────

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
def get_user(user_id: str = FPath(...)):
    fields = get_redis().hgetall(KEY_PREFIX + user_id)
    if not fields:
        raise HTTPException(status_code=404, detail=f"User '{user_id}' not found")
    return redis_to_principal(user_id, fields)


@app.get("/users/{user_id}/domains")
def get_user_domains_endpoint(user_id: str = FPath(...)):
    """List all domains the user belongs to (member of)."""
    if not get_redis().exists(KEY_PREFIX + user_id):
        raise HTTPException(status_code=404, detail=f"User '{user_id}' not found")
    domain_ids = get_user_domains(user_id)
    domains = []
    r = get_redis()
    for domain_id in domain_ids:
        raw = r.hgetall(DOMAIN_PREFIX + domain_id)
        if raw:
            domains.append({
                "id": raw.get("id", domain_id),
                "name": raw.get("name", ""),
                "relationships": json.loads(raw.get("relationships", "[]")),
            })
    return {
        "user_id": user_id,
        "domains": domains,
        "admin_domains": get_user_admin_domains(user_id),
    }


@app.post("/users", response_model=PrincipalView, status_code=201)
def create_user(user_id: str, body: CreatePrincipalRequest):
    r = get_redis()
    if r.exists(KEY_PREFIX + user_id):
        raise HTTPException(status_code=409, detail=f"User '{user_id}' already exists")
    p = PrincipalView(
        id=user_id,
        name=body.name,
        email=body.email,
        roles=body.roles,
        book=body.book,
        segments=body.segments,
        clearance=body.clearance,
        team=body.team,
    )
    write_principal(user_id, p)
    return p


@app.put("/users/{user_id}", response_model=PrincipalView)
def upsert_user(user_id: str, body: CreatePrincipalRequest):
    p = PrincipalView(
        id=user_id,
        name=body.name,
        email=body.email,
        roles=body.roles,
        book=body.book,
        segments=body.segments,
        clearance=body.clearance,
        team=body.team,
    )
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


# ── Routes: auth ──────────────────────────────────────────────────────────────

@app.post("/auth/token")
def issue_token(body: TokenRequest):
    """
    Demo-only: issue a RS256 JWT for a given user_id.
    The JWT contains roles, book (derived from domain memberships), and admin_domains.
    The public key is available at /.well-known/jwks.json for verification.
    """
    fields = get_redis().hgetall(KEY_PREFIX + body.user_id)
    if not fields:
        raise HTTPException(status_code=404, detail=f"User '{body.user_id}' not found")
    principal = redis_to_principal(body.user_id, fields)
    derived_book = get_book_from_domains(body.user_id)
    admin_domains = get_user_admin_domains(body.user_id)
    token = issue_jwt(body.user_id, principal)
    return {
        "access_token": token,
        "token_type": "Bearer",
        "expires_in": 3600,
        "algorithm": "RS256",
        "key_id": KEY_ID,
        "jwks_uri": "/.well-known/jwks.json",
        "derived_book": derived_book if derived_book else principal.book,
        "admin_domains": admin_domains,
        "user": principal,
        "note": "RS256 JWT — verify with public key from /.well-known/jwks.json",
    }


# ── Routes: domains ───────────────────────────────────────────────────────────

@app.post("/domains", status_code=201)
def create_domain(body: DomainCreate):
    r = get_redis()
    domain_key = DOMAIN_PREFIX + body.id
    if r.exists(domain_key):
        raise HTTPException(status_code=409, detail=f"Domain '{body.id}' already exists")
    r.hset(domain_key, mapping={
        "id":            body.id,
        "name":          body.name,
        "relationships": json.dumps(body.relationships),
    })
    return {"id": body.id, "name": body.name, "relationships": body.relationships}


@app.get("/domains")
def list_domains():
    r = get_redis()
    keys = r.keys(DOMAIN_PREFIX + "*")
    domains = []
    for key in sorted(keys):
        raw = r.hgetall(key)
        if raw:
            domain_id = key.removeprefix(DOMAIN_PREFIX)
            admins = sorted(r.smembers(DOMAIN_ADMINS_PREFIX + domain_id) or set())
            domains.append({
                "id": raw.get("id", ""),
                "name": raw.get("name", ""),
                "relationships": json.loads(raw.get("relationships", "[]")),
                "admins": admins,
            })
    return domains


@app.get("/domains/{domain_id}")
def get_domain(domain_id: str = FPath(...)):
    r = get_redis()
    raw = r.hgetall(DOMAIN_PREFIX + domain_id)
    if not raw:
        raise HTTPException(status_code=404, detail=f"Domain '{domain_id}' not found")
    admins = sorted(r.smembers(DOMAIN_ADMINS_PREFIX + domain_id) or set())
    return {
        "id": raw.get("id", domain_id),
        "name": raw.get("name", ""),
        "relationships": json.loads(raw.get("relationships", "[]")),
        "admins": admins,
    }


@app.put("/domains/{domain_id}/relationships")
def update_domain_relationships(domain_id: str, body: RelationshipsUpdate):
    r = get_redis()
    domain_key = DOMAIN_PREFIX + domain_id
    if not r.exists(domain_key):
        raise HTTPException(status_code=404, detail=f"Domain '{domain_id}' not found")
    r.hset(domain_key, "relationships", json.dumps(body.relationships))
    return {"domain_id": domain_id, "relationships": body.relationships}


@app.post("/domains/{domain_id}/members", status_code=201)
def add_domain_member(domain_id: str, body: MemberAdd):
    r = get_redis()
    if not r.exists(DOMAIN_PREFIX + domain_id):
        raise HTTPException(status_code=404, detail=f"Domain '{domain_id}' not found")
    if not r.exists(KEY_PREFIX + body.user_id):
        raise HTTPException(status_code=404, detail=f"User '{body.user_id}' not found")
    r.sadd(USER_DOMAINS_PREFIX + body.user_id, domain_id)
    r.sadd(MEMBERSHIP_PREFIX + domain_id, body.user_id)
    return {"domain_id": domain_id, "user_id": body.user_id, "added": True}


@app.delete("/domains/{domain_id}/members/{user_id}")
def remove_domain_member(domain_id: str, user_id: str):
    r = get_redis()
    if not r.exists(DOMAIN_PREFIX + domain_id):
        raise HTTPException(status_code=404, detail=f"Domain '{domain_id}' not found")
    r.srem(USER_DOMAINS_PREFIX + user_id, domain_id)
    r.srem(MEMBERSHIP_PREFIX + domain_id, user_id)
    return {"domain_id": domain_id, "user_id": user_id, "removed": True}


@app.get("/domains/{domain_id}/members")
def list_domain_members(domain_id: str = FPath(...)):
    r = get_redis()
    if not r.exists(DOMAIN_PREFIX + domain_id):
        raise HTTPException(status_code=404, detail=f"Domain '{domain_id}' not found")
    members = sorted(r.smembers(MEMBERSHIP_PREFIX + domain_id) or set())
    return {"domain_id": domain_id, "members": members}


# ── Routes: domain admins (Phase 10) ─────────────────────────────────────────

@app.post("/domains/{domain_id}/admins", status_code=201)
def grant_domain_admin(domain_id: str, body: AdminGrant):
    """Grant a user domain_admin role for a specific domain."""
    r = get_redis()
    if not r.exists(DOMAIN_PREFIX + domain_id):
        raise HTTPException(status_code=404, detail=f"Domain '{domain_id}' not found")
    if not r.exists(KEY_PREFIX + body.user_id):
        raise HTTPException(status_code=404, detail=f"User '{body.user_id}' not found")
    r.sadd(DOMAIN_ADMINS_PREFIX + domain_id, body.user_id)
    r.sadd(USER_ADMIN_DOMAINS_PREFIX + body.user_id, domain_id)
    return {"domain_id": domain_id, "user_id": body.user_id, "granted": True}


@app.delete("/domains/{domain_id}/admins/{user_id}")
def revoke_domain_admin(domain_id: str, user_id: str):
    """Revoke a user's domain_admin grant for a specific domain."""
    r = get_redis()
    r.srem(DOMAIN_ADMINS_PREFIX + domain_id, user_id)
    r.srem(USER_ADMIN_DOMAINS_PREFIX + user_id, domain_id)
    return {"domain_id": domain_id, "user_id": user_id, "revoked": True}


@app.get("/domains/{domain_id}/admins")
def list_domain_admins(domain_id: str = FPath(...)):
    """List all users who have domain_admin rights for this domain."""
    r = get_redis()
    if not r.exists(DOMAIN_PREFIX + domain_id):
        raise HTTPException(status_code=404, detail=f"Domain '{domain_id}' not found")
    admins = sorted(r.smembers(DOMAIN_ADMINS_PREFIX + domain_id) or set())
    return {"domain_id": domain_id, "admins": admins}
