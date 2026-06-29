"""
Meridian User Management Service — Postgres-backed

Manages RM principals, domain memberships, teams, roles, and issues RS256 JWTs.
Data is stored in Postgres via async SQLAlchemy. Redis is used ONLY for short-lived
OAuth authorization codes (SETEX 300 s).

Endpoints (same API surface as the Redis-backed version):
  GET    /users                              list all principals
  GET    /users/{user_id}                    get one principal
  POST   /users                              create principal
  PUT    /users/{user_id}                    replace principal
  PATCH  /users/{user_id}/book              add/remove relationships from book
  DELETE /users/{user_id}                   delete principal
  POST   /users/{user_id}/resources         add a personal resource of any type
  DELETE /users/{user_id}/resources/{type}/{resource_id}  remove a personal resource
  GET    /users/{user_id}/book              list relationship book
  GET    /users/{user_id}/relationships/{rel_id}/access  point-in-time access check
  GET    /users/{user_id}/teams             list team memberships
  GET    /users/{user_id}/domains           list domain memberships + admin_domains

  POST   /auth/token                        issue RS256 JWT (demo)
  POST   /auth/login                        credential login for Admin UI
  GET    /health                            health (Postgres + Redis)
  GET    /.well-known/jwks.json             RSA public key set
  GET    /.well-known/openid-configuration  OIDC discovery
  GET    /oauth/authorize                   show login form
  POST   /oauth/authorize                   validate credentials + issue auth code
  POST   /oauth/token                       exchange auth code for tokens
  GET    /oauth/userinfo                    return profile claims

  POST   /roles                             create role
  GET    /roles                             list roles
  GET    /roles/{role_id}                   get role
  PUT    /roles/{role_id}                   update role
  DELETE /roles/{role_id}                   delete role
  POST   /users/{user_id}/roles             assign role to user
  DELETE /users/{user_id}/roles/{role_id}   remove role from user

  POST   /teams                             create team
  GET    /teams                             list teams
  GET    /teams/{team_id}                   get team + members
  PUT    /teams/{team_id}                   update team
  DELETE /teams/{team_id}                   delete team
  POST   /teams/{team_id}/members           add member
  DELETE /teams/{team_id}/members/{user_id} remove member
  GET    /teams/{team_id}/members           list members

  POST   /domains                           create domain
  GET    /domains                           list domains
  GET    /domains/{domain_id}               get domain
  PUT    /domains/{domain_id}/relationships update relationships
  POST   /domains/{domain_id}/members       add member
  DELETE /domains/{domain_id}/members/{user_id}  remove member
  GET    /domains/{domain_id}/members       list members
  POST   /domains/{domain_id}/admins        grant domain admin
  DELETE /domains/{domain_id}/admins/{user_id}   revoke domain admin
  GET    /domains/{domain_id}/admins        list domain admins

  POST   /admin/policies/generate           generate Cerbos policy via LLM
  POST   /admin/policies/validate           validate policy YAML
  POST   /admin/policies/apply              write policy to Cerbos dir
  GET    /admin/policies                    list policy files
  GET    /admin/policy-resources            list resource types from Cerbos YAML
  GET    /admin/segments                    list unique segments from principal attributes
"""

import base64
import html
import json
import math
import os
import pathlib
import secrets
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, List, Optional

import bcrypt as _bcrypt_lib
import redis as redis_lib
import yaml
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import Depends, FastAPI, Form, HTTPException, Path as FPath, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, RedirectResponse
from jose import jwt
from pydantic import BaseModel
from sqlalchemy import delete, func, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from db import AsyncSessionLocal, get_db, init_db
from models import (
    Group,
    GroupMember,
    PersonalResource,
    Principal,
    PrincipalRole,
    Role,
)

# ── Bcrypt rounds (override via env for faster tests) ──────────────────────────

_BCRYPT_ROUNDS = int(os.getenv("BCRYPT_ROUNDS", "12"))


def _hash_password(password: str) -> str:
    """Hash a password with bcrypt (using the bcrypt library directly)."""
    salt = _bcrypt_lib.gensalt(rounds=_BCRYPT_ROUNDS)
    return _bcrypt_lib.hashpw(password.encode("utf-8"), salt).decode("utf-8")


def _verify_password(password: str, stored_hash: str) -> bool:
    """Verify a bcrypt password hash. Returns False on any error."""
    try:
        return _bcrypt_lib.checkpw(password.encode("utf-8"), stored_hash.encode("utf-8"))
    except Exception:
        return False


# ── FastAPI app ────────────────────────────────────────────────────────────────

app = FastAPI(
    title="Meridian User Management",
    description="Manages RM principals, domain memberships, and issues RS256 JWTs",
    version="4.0.0",
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
        print(
            "[user-mgmt] WARNING: using ephemeral RS256 key — tokens will be invalid "
            "after restart. Set RS256_PRIVATE_KEY_PEM for production."
        )


def _int_to_b64url(n: int) -> str:
    length = math.ceil(n.bit_length() / 8)
    return base64.urlsafe_b64encode(n.to_bytes(length, "big")).rstrip(b"=").decode()


# ── OIDC configuration ─────────────────────────────────────────────────────────

OIDC_ISSUER = os.getenv("OIDC_ISSUER", "http://user-mgmt:8084")
OIDC_BASE_URL = os.getenv("OIDC_BASE_URL", "http://localhost:8084")
OIDC_CLIENT_ID = os.getenv("OIDC_CLIENT_ID", "meridian-librechat")
OIDC_CLIENT_SECRET = os.getenv("OIDC_CLIENT_SECRET", "meridian-client-secret")
DEMO_PASSWORD = os.getenv("DEMO_PASSWORD", "Meridian@2024")

_REGISTERED_REDIRECT_URIS: set = set()


def _init_registered_redirect_uris() -> None:
    uris = {
        os.getenv(
            "OIDC_CLIENT_REDIRECT_URI",
            "http://localhost:3080/oauth/openid/callback",
        ),
    }
    _REGISTERED_REDIRECT_URIS.update(uris)


# ── Redis connection (auth codes only) ────────────────────────────────────────

REDIS_URL = os.getenv("REDIS_URL", "")
REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))

_redis: Optional[redis_lib.Redis] = None


def get_redis() -> redis_lib.Redis:
    global _redis
    if _redis is None:
        if REDIS_URL:
            _redis = redis_lib.from_url(REDIS_URL, decode_responses=True)
        else:
            _redis = redis_lib.Redis(host=REDIS_HOST, port=REDIS_PORT, decode_responses=True)
    return _redis


# ── Org seed ──────────────────────────────────────────────────────────────────

ORG_YAML_PATH = Path(__file__).parent / "seed" / "org.yaml"

_DEFAULT_ROLES = [
    {
        "id": "relationship_manager",
        "name": "Relationship Manager",
        "description": "RM users who manage client portfolios and relationships",
        "permissions": ["invoke:wealth-agents", "invoke:servicing-agents", "read:relationship-data"],
        "min_clearance": 2,
    },
    {
        "id": "platform_admin",
        "name": "Platform Administrator",
        "description": "Full administrative access across all domains and agents",
        "permissions": ["invoke:*", "register:agent", "deregister:agent", "admin:*"],
        "min_clearance": 5,
    },
    {
        "id": "domain_admin",
        "name": "Domain Administrator",
        "description": "Admin scoped to specific business domains",
        "permissions": ["invoke:domain-agents", "register:agent", "deregister:agent"],
        "min_clearance": 3,
    },
    {
        "id": "attending_physician",
        "name": "Attending Physician",
        "description": "Hospital physician with patient data access",
        "permissions": ["read:patient-data"],
        "min_clearance": 1,
    },
    {
        "id": "associate",
        "name": "Associate",
        "description": "Law firm associate with matter data access",
        "permissions": ["read:matter-data"],
        "min_clearance": 1,
    },
]

_DEFAULT_TEAMS = [
    {
        "id": "wealth-private-banking",
        "name": "Wealth Private Banking",
        "description": "High-net-worth private banking relationship managers",
        "default_roles": ["relationship_manager"],
        "segments": ["wealth"],
        "allowed_domains": ["wealth-private-banking"],
    },
    {
        "id": "intl-wealth",
        "name": "International Wealth",
        "description": "International wealth management team",
        "default_roles": ["relationship_manager"],
        "segments": ["wealth"],
        "allowed_domains": ["intl-wealth"],
    },
    {
        "id": "platform",
        "name": "Platform Team",
        "description": "Platform administrators and engineering operations",
        "default_roles": ["platform_admin"],
        "segments": ["wealth", "servicing"],
        "allowed_domains": [],
    },
    {
        "id": "wealth-ultra-hnw",
        "name": "Wealth Ultra High Net Worth",
        "description": "Ultra HNW wealth management",
        "default_roles": ["relationship_manager"],
        "segments": ["wealth"],
        "allowed_domains": [],
    },
    {
        "id": "servicing-ops",
        "name": "Servicing Operations",
        "description": "Asset servicing operations team",
        "default_roles": ["relationship_manager"],
        "segments": ["servicing"],
        "allowed_domains": ["servicing-ops"],
    },
]


async def _seed_database() -> None:
    """Idempotent seed: insert default data if the principals table is empty."""
    async with AsyncSessionLocal() as db:
        count_result = await db.execute(select(func.count(Principal.id)))
        count = count_result.scalar() or 0
        if count > 0:
            # Already seeded — but still sync roles so admin upgrades propagate
            await _sync_roles(db)
            await db.commit()
            return

        # 1. Seed default roles
        for role_data in _DEFAULT_ROLES:
            db.add(
                Role(
                    id=role_data["id"],
                    name=role_data["name"],
                    description=role_data["description"],
                    permissions=role_data["permissions"],
                    min_clearance=role_data["min_clearance"],
                )
            )

        await db.flush()

        # 2. Seed from org.yaml or inline — must happen before teams so we can detect conflicts
        org: Optional[dict] = None
        if ORG_YAML_PATH.exists():
            with open(ORG_YAML_PATH) as f:
                org = yaml.safe_load(f)
            print(f"[user-mgmt] Loading org seed from {ORG_YAML_PATH}")
        else:
            print("[user-mgmt] No org.yaml found — using inline seed")
            org = _inline_org()

        # Collect domain IDs from org so we can avoid team ID conflicts
        domain_ids_in_org: set[str] = {d["id"] for d in (org or {}).get("domains", [])}

        # Build team metadata map (for embedding into domain groups below)
        team_meta_map: dict[str, dict] = {
            t["id"]: {
                "default_roles": t["default_roles"],
                "segments": t["segments"],
                "allowed_domains": t["allowed_domains"],
            }
            for t in _DEFAULT_TEAMS
        }

        # 3. Seed teams that do NOT conflict with domain IDs
        for team_data in _DEFAULT_TEAMS:
            if team_data["id"] not in domain_ids_in_org:
                db.add(
                    Group(
                        id=team_data["id"],
                        type="team",
                        name=team_data["name"],
                        slug=team_data["id"],
                        description=team_data["description"],
                        metadata_={
                            "default_roles": team_data["default_roles"],
                            "segments": team_data["segments"],
                            "allowed_domains": team_data["allowed_domains"],
                        },
                    )
                )

        await db.flush()

        if org:
            await _seed_from_org(db, org, team_meta_map)

        await db.commit()
        print("[user-mgmt] Database seeded successfully")


async def _sync_roles(db: AsyncSession) -> None:
    """Update role definitions from _DEFAULT_ROLES (allows upgrades on restart)."""
    for role_data in _DEFAULT_ROLES:
        result = await db.execute(select(Role).where(Role.id == role_data["id"]))
        role = result.scalar_one_or_none()
        if role:
            role.name = role_data["name"]
            role.description = role_data["description"]
            role.permissions = role_data["permissions"]
            role.min_clearance = role_data["min_clearance"]


async def _seed_from_org(db: AsyncSession, org: dict, team_meta_map: Optional[dict] = None) -> None:
    principals_data = org.get("principals", [])
    domains_data = org.get("domains", [])
    domain_admins_data = org.get("domain_admins", [])

    # Build a map of user_id → admin domains for backfill
    uid_to_admin_domains: dict[str, list] = {}
    for grant in domain_admins_data:
        uid = grant["user_id"]
        uid_to_admin_domains.setdefault(uid, []).extend(grant.get("domains", []))

    # Insert principals
    for p in principals_data:
        uid = p["id"]
        admin_domains = sorted(uid_to_admin_domains.get(uid, []))
        principal = Principal(
            id=uid,
            name=p.get("name", uid),
            email=p.get("email", ""),
            password_hash=_hash_password(p.get("password", DEMO_PASSWORD)),
            attributes={
                "clearance": p.get("clearance", 2),
                "segments": p.get("segments", []),
                "team": p.get("team", ""),
                "admin_domains": admin_domains,
            },
        )
        db.add(principal)

    await db.flush()

    # Insert role assignments
    for p in principals_data:
        uid = p["id"]
        for role_id in p.get("roles", ["relationship_manager"]):
            db.add(PrincipalRole(principal_id=uid, role_id=role_id))

    # Insert personal resources (book items)
    for p in principals_data:
        uid = p["id"]
        for rel_id in p.get("book", []):
            db.add(
                PersonalResource(
                    principal_id=uid,
                    resource_type="relationship",
                    resource_id=rel_id,
                )
            )

    await db.flush()

    # Insert domain groups + memberships
    # Domains that share an ID with a team get combined: they inherit team metadata too.
    for d in domains_data:
        domain_id = d["id"]
        # Admins for this domain
        domain_admin_uids = [
            grant["user_id"]
            for grant in domain_admins_data
            if domain_id in grant.get("domains", [])
        ]
        # Include team metadata if this domain also acts as a team
        combined_meta: dict = {
            "relationships": d.get("relationships", []),
            "admins": domain_admin_uids,
        }
        if team_meta_map and domain_id in team_meta_map:
            combined_meta.update(team_meta_map[domain_id])
        group = Group(
            id=domain_id,
            type="domain",
            name=d["name"],
            slug=domain_id,
            description="",
            metadata_=combined_meta,
        )
        db.add(group)

    await db.flush()

    # Track inserted (group_id, principal_id) pairs to avoid duplicates
    inserted_memberships: set[tuple[str, str]] = set()

    for d in domains_data:
        domain_id = d["id"]
        for member_id in d.get("members", []):
            key = (domain_id, member_id)
            if key not in inserted_memberships:
                db.add(GroupMember(group_id=domain_id, principal_id=member_id))
                inserted_memberships.add(key)

    # Back-fill team memberships from user.team string (skip if already covered by domain)
    for p in principals_data:
        uid = p["id"]
        team_id = p.get("team", "")
        if team_id:
            key = (team_id, uid)
            if key not in inserted_memberships:
                db.add(GroupMember(group_id=team_id, principal_id=uid))
                inserted_memberships.add(key)


def _inline_org() -> dict:
    """Fallback seed when no org.yaml is found."""
    return {
        "principals": [
            {
                "id": "rm_jane",
                "name": "Jane Smith",
                "email": "jane.smith@meridianbank.com",
                "roles": ["relationship_manager"],
                "book": ["REL-00042", "REL-00099"],
                "segments": ["wealth", "servicing"],
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
                "team": "intl-wealth",
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
            {
                "id": "rm_diaz",
                "name": "Carlos Diaz",
                "email": "carlos.diaz@meridianbank.com",
                "roles": ["relationship_manager"],
                "book": ["REL-00300"],
                "segments": ["wealth", "servicing"],
                "clearance": 3,
                "team": "servicing-ops",
            },
        ],
        "domains": [
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
        ],
        "domain_admins": [],
    }


# ── Startup ───────────────────────────────────────────────────────────────────

@app.on_event("startup")
async def startup() -> None:
    _load_or_generate_keypair()
    _init_registered_redirect_uris()
    await init_db()
    await _seed_database()


# ── Pydantic models (same API shapes) ─────────────────────────────────────────

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


class RoleCreate(BaseModel):
    id: str
    name: str
    description: str = ""
    permissions: List[str] = []
    clearance_required: int = 1


class TeamCreate(BaseModel):
    id: str
    name: str
    description: str = ""
    default_roles: List[str] = []
    segments: List[str] = []
    allowed_domains: List[str] = []


class LoginRequest(BaseModel):
    username: str
    password: str


class PolicyIntent(BaseModel):
    resource: str = "agent"
    subject_roles: List[str] = []
    actions: List[str] = []
    conditions: dict = {}
    policy_name: str = ""
    description: str = ""


class PolicyValidateRequest(BaseModel):
    yaml: str


class PolicyApplyRequest(BaseModel):
    yaml: str
    filename: str


class PersonalResourceCreate(BaseModel):
    resource_type: str = "relationship"
    resource_id: str


# ── Async DB helpers ──────────────────────────────────────────────────────────

async def _get_user_roles(user_id: str, db: AsyncSession) -> List[str]:
    result = await db.execute(
        select(PrincipalRole.role_id)
        .where(PrincipalRole.principal_id == user_id)
        .order_by(PrincipalRole.role_id)
    )
    return [row[0] for row in result.all()]


async def _get_user_book(user_id: str, db: AsyncSession) -> List[str]:
    """Personal resources of type 'relationship' for a user."""
    result = await db.execute(
        select(PersonalResource.resource_id)
        .where(PersonalResource.principal_id == user_id)
        .where(PersonalResource.resource_type == "relationship")
        .order_by(PersonalResource.resource_id)
    )
    return [row[0] for row in result.all()]


async def _get_user_domain_ids(user_id: str, db: AsyncSession) -> List[str]:
    """Domain group IDs (type='domain') the user is a member of."""
    result = await db.execute(
        select(Group.id)
        .join(GroupMember, Group.id == GroupMember.group_id)
        .where(GroupMember.principal_id == user_id)
        .where(Group.type == "domain")
        .order_by(Group.id)
    )
    return [row[0] for row in result.all()]


async def _principal_to_view(p: Principal, db: AsyncSession) -> PrincipalView:
    roles = await _get_user_roles(p.id, db)
    book = await _get_user_book(p.id, db)
    attrs = p.attributes or {}
    return PrincipalView(
        id=p.id,
        name=p.name,
        email=p.email,
        roles=roles,
        book=book,
        segments=attrs.get("segments", []),
        clearance=attrs.get("clearance", 2),
        team=attrs.get("team", ""),
        admin_domains=sorted(attrs.get("admin_domains", [])),
    )


# ── JWT issuance ──────────────────────────────────────────────────────────────

async def issue_jwt(
    user_id: str,
    principal_row: Principal,
    db: AsyncSession,
    ttl_seconds: int = 3600,
    issuer: str = "meridian-user-mgmt",
    audience: str = "meridian-gateway",
) -> str:
    """Issue a RS256 JWT with all claim attributes drawn from Postgres."""
    now = int(time.time())
    book = await _get_user_book(user_id, db)
    domains = await _get_user_domain_ids(user_id, db)
    roles = await _get_user_roles(user_id, db)
    attrs = principal_row.attributes or {}
    admin_domains = sorted(attrs.get("admin_domains", []))

    payload = {
        "sub": user_id,
        "name": principal_row.name,
        "email": principal_row.email,
        "roles": roles,
        "book": book,
        "segments": attrs.get("segments", []),
        "clearance": attrs.get("clearance", 2),
        "domains": domains,
        "admin_domains": admin_domains,
        "iat": now,
        "exp": now + ttl_seconds,
        "iss": issuer,
        "aud": audience,
    }
    return jwt.encode(
        payload,
        _private_key_pem,
        algorithm="RS256",
        headers={"kid": KEY_ID},
    )


def issue_id_token(
    user_id: str,
    principal_row: Principal,
    client_id: str,
    ttl_seconds: int = 3600,
) -> str:
    """Issue an OIDC id_token for LibreChat (iss=OIDC_ISSUER, aud=client_id)."""
    now = int(time.time())
    payload = {
        "sub": user_id,
        "name": principal_row.name,
        "email": principal_row.email,
        "iat": now,
        "exp": now + ttl_seconds,
        "iss": OIDC_ISSUER,
        "aud": client_id,
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


# ── Routes: OIDC ──────────────────────────────────────────────────────────────

_LOGIN_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Meridian Bank — Sign In</title>
  <style>
    *{{box-sizing:border-box}}
    body{{font-family:-apple-system,system-ui,sans-serif;background:#0a1628;
         display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0}}
    .card{{background:#fff;border-radius:12px;padding:40px;width:380px;
           box-shadow:0 4px 32px rgba(0,0,0,.5)}}
    h1{{color:#0a1628;font-size:22px;margin:0 0 4px}}
    p{{color:#666;font-size:13px;margin:0 0 24px}}
    input{{width:100%;padding:10px 12px;border:1px solid #d1d5db;border-radius:6px;
           font-size:14px;margin-bottom:14px;outline:none}}
    input:focus{{border-color:#1e40af;box-shadow:0 0 0 2px rgba(30,64,175,.15)}}
    button{{width:100%;padding:12px;background:#1e40af;color:#fff;border:none;
            border-radius:6px;font-size:15px;font-weight:600;cursor:pointer}}
    button:hover{{background:#1d3a9f}}
    .err{{background:#fef2f2;border:1px solid #fca5a5;color:#dc2626;
          border-radius:6px;padding:10px 14px;font-size:13px;margin-bottom:14px;text-align:center}}
    .hint{{margin-top:16px;font-size:12px;color:#9ca3af;text-align:center}}
  </style>
</head>
<body>
  <div class="card">
    <h1>Meridian AI Gateway</h1>
    <p>Sign in with your Meridian credentials</p>
    {error_block}
    <form method="POST" action="{action_url}">
      <input type="hidden" name="client_id"    value="{client_id}">
      <input type="hidden" name="redirect_uri" value="{redirect_uri}">
      <input type="hidden" name="state"        value="{state}">
      <input type="text"     name="username" placeholder="User ID  (e.g. rm_jane)" required autofocus>
      <input type="password" name="password" placeholder="Password" required>
      <button type="submit">Sign In</button>
    </form>
    <p class="hint">Demo credentials: any user ID from the org · password: <b>Meridian@2024</b></p>
  </div>
</body>
</html>"""


@app.get("/.well-known/openid-configuration")
def openid_configuration():
    return {
        "issuer": OIDC_ISSUER,
        "authorization_endpoint": f"{OIDC_BASE_URL}/oauth/authorize",
        "token_endpoint": f"{OIDC_ISSUER}/oauth/token",
        "userinfo_endpoint": f"{OIDC_ISSUER}/oauth/userinfo",
        "jwks_uri": f"{OIDC_ISSUER}/.well-known/jwks.json",
        "response_types_supported": ["code"],
        "subject_types_supported": ["public"],
        "id_token_signing_alg_values_supported": ["RS256"],
        "scopes_supported": ["openid", "profile", "email"],
        "token_endpoint_auth_methods_supported": ["client_secret_post", "client_secret_basic"],
        "claims_supported": ["sub", "name", "email", "iss", "aud", "exp", "iat"],
    }


@app.get("/oauth/authorize", response_class=HTMLResponse)
def authorize_get(
    response_type: str = "code",
    client_id: str = "",
    redirect_uri: str = "",
    state: str = "",
    scope: str = "openid",
    error: str = "",
):
    error_block = f'<div class="err">{html.escape(error)}</div>' if error else ""
    action_url = f"{OIDC_BASE_URL}/oauth/authorize"
    return _LOGIN_HTML.format(
        error_block=error_block,
        action_url=action_url,
        client_id=client_id,
        redirect_uri=redirect_uri,
        state=state,
    )


@app.post("/oauth/authorize")
async def authorize_post(
    username: str = Form(...),
    password: str = Form(...),
    client_id: str = Form(...),
    redirect_uri: str = Form(...),
    state: str = Form(""),
    db: AsyncSession = Depends(get_db),
):
    """Validate credentials, generate auth code in Redis, redirect to callback."""
    if redirect_uri not in _REGISTERED_REDIRECT_URIS:
        raise HTTPException(status_code=400, detail="invalid redirect_uri")

    result = await db.execute(select(Principal).where(Principal.id == username))
    principal_row = result.scalar_one_or_none()

    _error_redirect = (
        f"{OIDC_BASE_URL}/oauth/authorize?"
        f"client_id={client_id}&redirect_uri={redirect_uri}&state={state}"
        f"&error=Invalid+user+ID+or+password"
    )

    if not principal_row:
        return RedirectResponse(_error_redirect, status_code=302)

    if not _verify_password(password, principal_row.password_hash or ""):
        return RedirectResponse(_error_redirect, status_code=302)

    # Store auth code in Redis (expires in 5 minutes)
    code = secrets.token_urlsafe(32)
    get_redis().setex(
        f"auth:code:{code}",
        300,
        json.dumps({
            "user_id": username,
            "client_id": client_id,
            "redirect_uri": redirect_uri,
        }),
    )
    print(f"[user-mgmt/oidc] auth code issued for user={username} client={client_id}")

    sep = "&" if "?" in redirect_uri else "?"
    return RedirectResponse(
        f"{redirect_uri}{sep}code={code}&state={state}",
        status_code=302,
    )


@app.post("/oauth/token")
async def token_endpoint(
    request: Request,
    grant_type: str = Form("authorization_code"),
    code: str = Form(""),
    redirect_uri: str = Form(""),
    client_id: str = Form(""),
    client_secret: str = Form(""),
    db: AsyncSession = Depends(get_db),
):
    """Exchange authorization code for id_token + access_token."""
    # Support client_secret_basic (Authorization header)
    auth_header = request.headers.get("Authorization", "")
    if auth_header.startswith("Basic "):
        try:
            decoded = base64.b64decode(auth_header[6:]).decode()
            client_id_h, client_secret_h = decoded.split(":", 1)
            if not client_id:
                client_id = client_id_h
            if not client_secret:
                client_secret = client_secret_h
        except Exception:
            pass

    if grant_type != "authorization_code":
        raise HTTPException(status_code=400, detail="unsupported_grant_type")

    if client_id != OIDC_CLIENT_ID or client_secret != OIDC_CLIENT_SECRET:
        raise HTTPException(status_code=401, detail="invalid_client")

    # Consume auth code from Redis (atomic: get then delete)
    raw = get_redis().get(f"auth:code:{code}")
    if not raw:
        raise HTTPException(status_code=400, detail="invalid_grant: unknown code")
    get_redis().delete(f"auth:code:{code}")

    entry = json.loads(raw)
    if entry["client_id"] != client_id:
        raise HTTPException(status_code=400, detail="invalid_grant: client mismatch")
    if entry["redirect_uri"] != redirect_uri:
        raise HTTPException(status_code=400, detail="invalid_grant: redirect_uri mismatch")

    user_id = entry["user_id"]
    result = await db.execute(select(Principal).where(Principal.id == user_id))
    principal_row = result.scalar_one_or_none()
    if not principal_row:
        raise HTTPException(status_code=400, detail="invalid_grant: user not found")

    id_token = issue_id_token(user_id, principal_row, client_id=client_id, ttl_seconds=3600)
    access_token = await issue_jwt(
        user_id, principal_row, db, ttl_seconds=3600,
        issuer=OIDC_ISSUER, audience="meridian-gateway",
    )

    print(f"[user-mgmt/oidc] tokens issued for user={user_id}")
    return {
        "access_token": access_token,
        "id_token": id_token,
        "token_type": "Bearer",
        "expires_in": 3600,
    }


@app.get("/oauth/userinfo")
async def userinfo_endpoint(request: Request, db: AsyncSession = Depends(get_db)):
    """Return user profile claims. Requires a valid Bearer access_token."""
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="missing token")
    token = auth[7:].strip()
    try:
        pub_pem = _rsa_private_key.public_key().public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        ).decode()
        claims = jwt.decode(
            token, pub_pem, algorithms=["RS256"],
            options={"verify_exp": True, "verify_aud": False},
        )
        user_id = claims.get("sub")
    except Exception:
        raise HTTPException(status_code=401, detail="invalid token")

    result = await db.execute(select(Principal).where(Principal.id == user_id))
    p = result.scalar_one_or_none()
    if not p:
        raise HTTPException(status_code=404, detail="user not found")
    roles = await _get_user_roles(user_id, db)
    return {"sub": user_id, "name": p.name, "email": p.email, "roles": roles}


# ── Routes: health ────────────────────────────────────────────────────────────

@app.get("/health")
async def health(db: AsyncSession = Depends(get_db)):
    try:
        await db.execute(text("SELECT 1"))
        pg_ok = True
    except Exception:
        pg_ok = False

    redis_ok = True
    try:
        get_redis().ping()
    except Exception:
        redis_ok = False

    return {
        "status": "ok" if pg_ok else "degraded",
        "postgres": "connected" if pg_ok else "disconnected",
        "redis": "connected" if redis_ok else "disconnected",
        "jwt_algorithm": "RS256",
        "key_id": KEY_ID,
    }


# ── Routes: users ─────────────────────────────────────────────────────────────

@app.get("/users", response_model=List[PrincipalView])
async def list_users(db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(Principal).order_by(Principal.id))
    principals = result.scalars().all()
    views = []
    for p in principals:
        views.append(await _principal_to_view(p, db))
    return views


@app.get("/users/{user_id}", response_model=PrincipalView)
async def get_user(user_id: str = FPath(...), db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(Principal).where(Principal.id == user_id))
    p = result.scalar_one_or_none()
    if not p:
        raise HTTPException(status_code=404, detail=f"User '{user_id}' not found")
    return await _principal_to_view(p, db)


@app.post("/users", response_model=PrincipalView, status_code=201)
async def create_user(
    user_id: str,
    body: CreatePrincipalRequest,
    db: AsyncSession = Depends(get_db),
):
    existing = await db.execute(select(Principal).where(Principal.id == user_id))
    if existing.scalar_one_or_none():
        raise HTTPException(status_code=409, detail=f"User '{user_id}' already exists")

    p = Principal(
        id=user_id,
        name=body.name,
        email=body.email,
        password_hash=_hash_password(DEMO_PASSWORD),
        attributes={
            "clearance": body.clearance,
            "segments": body.segments,
            "team": body.team,
            "admin_domains": [],
        },
    )
    db.add(p)
    await db.flush()

    for role_id in body.roles:
        db.add(PrincipalRole(principal_id=user_id, role_id=role_id))
    for rel_id in body.book:
        db.add(PersonalResource(principal_id=user_id, resource_type="relationship", resource_id=rel_id))

    await db.commit()
    await db.refresh(p)
    return await _principal_to_view(p, db)


@app.put("/users/{user_id}", response_model=PrincipalView)
async def upsert_user(
    user_id: str,
    body: CreatePrincipalRequest,
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(select(Principal).where(Principal.id == user_id))
    p = result.scalar_one_or_none()

    if p:
        p.name = body.name
        p.email = body.email
        p.attributes = {
            "clearance": body.clearance,
            "segments": body.segments,
            "team": body.team,
            "admin_domains": (p.attributes or {}).get("admin_domains", []),
        }
        # Replace roles
        await db.execute(delete(PrincipalRole).where(PrincipalRole.principal_id == user_id))
        await db.execute(
            delete(PersonalResource)
            .where(PersonalResource.principal_id == user_id)
            .where(PersonalResource.resource_type == "relationship")
        )
    else:
        p = Principal(
            id=user_id,
            name=body.name,
            email=body.email,
            password_hash=_hash_password(DEMO_PASSWORD),
            attributes={
                "clearance": body.clearance,
                "segments": body.segments,
                "team": body.team,
                "admin_domains": [],
            },
        )
        db.add(p)
        await db.flush()

    for role_id in body.roles:
        db.add(PrincipalRole(principal_id=user_id, role_id=role_id))
    for rel_id in body.book:
        db.add(PersonalResource(principal_id=user_id, resource_type="relationship", resource_id=rel_id))

    await db.commit()
    await db.refresh(p)
    return await _principal_to_view(p, db)


@app.patch("/users/{user_id}/book", response_model=PrincipalView)
async def patch_book(
    user_id: str,
    body: BookPatchRequest,
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(select(Principal).where(Principal.id == user_id))
    p = result.scalar_one_or_none()
    if not p:
        raise HTTPException(status_code=404, detail=f"User '{user_id}' not found")

    # Remove specified relationships
    if body.remove:
        await db.execute(
            delete(PersonalResource)
            .where(PersonalResource.principal_id == user_id)
            .where(PersonalResource.resource_type == "relationship")
            .where(PersonalResource.resource_id.in_(body.remove))
        )

    # Add new ones (skip duplicates)
    for rel_id in body.add:
        existing_check = await db.execute(
            select(PersonalResource)
            .where(PersonalResource.principal_id == user_id)
            .where(PersonalResource.resource_type == "relationship")
            .where(PersonalResource.resource_id == rel_id)
        )
        if not existing_check.scalar_one_or_none():
            db.add(PersonalResource(
                principal_id=user_id,
                resource_type="relationship",
                resource_id=rel_id,
            ))

    await db.commit()
    await db.refresh(p)
    return await _principal_to_view(p, db)


@app.delete("/users/{user_id}")
async def delete_user(user_id: str, db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(Principal).where(Principal.id == user_id))
    p = result.scalar_one_or_none()
    if not p:
        raise HTTPException(status_code=404, detail=f"User '{user_id}' not found")
    await db.delete(p)
    await db.commit()
    return {"deleted": user_id}


@app.get("/users/{user_id}/book")
async def get_user_book(user_id: str = FPath(...), db: AsyncSession = Depends(get_db)):
    """Return the user's personal relationship book (from personal_resources table)."""
    result = await db.execute(select(Principal).where(Principal.id == user_id))
    if not result.scalar_one_or_none():
        raise HTTPException(status_code=404, detail=f"User '{user_id}' not found")
    book = await _get_user_book(user_id, db)
    return {"user_id": user_id, "relationships": book}


@app.get("/users/{user_id}/relationships/{rel_id}/access")
async def check_relationship_access(
    user_id: str,
    rel_id: str,
    resource_type: str = "relationship",
    db: AsyncSession = Depends(get_db),
):
    """Point-in-time check: does this user have this resource in their personal resources?"""
    result = await db.execute(select(Principal).where(Principal.id == user_id))
    if not result.scalar_one_or_none():
        raise HTTPException(status_code=404, detail=f"User '{user_id}' not found")

    count_result = await db.execute(
        select(func.count(PersonalResource.id))
        .where(PersonalResource.principal_id == user_id)
        .where(PersonalResource.resource_type == resource_type)
        .where(PersonalResource.resource_id == rel_id)
    )
    count = count_result.scalar() or 0
    return {"user_id": user_id, "relationship_id": rel_id, "allowed": count > 0}


@app.post("/users/{user_id}/resources", status_code=201)
async def add_personal_resource(
    user_id: str,
    body: PersonalResourceCreate,
    db: AsyncSession = Depends(get_db),
):
    """Add a personal resource of any type to a user's grant set."""
    result = await db.execute(select(Principal).where(Principal.id == user_id))
    if not result.scalar_one_or_none():
        raise HTTPException(status_code=404, detail=f"User '{user_id}' not found")

    # Idempotent: skip if already exists
    existing_check = await db.execute(
        select(PersonalResource)
        .where(PersonalResource.principal_id == user_id)
        .where(PersonalResource.resource_type == body.resource_type)
        .where(PersonalResource.resource_id == body.resource_id)
    )
    if not existing_check.scalar_one_or_none():
        db.add(PersonalResource(
            principal_id=user_id,
            resource_type=body.resource_type,
            resource_id=body.resource_id,
        ))
        await db.commit()

    return {
        "user_id": user_id,
        "resource_type": body.resource_type,
        "resource_id": body.resource_id,
        "added": True,
    }


@app.delete("/users/{user_id}/resources/{resource_type}/{resource_id}")
async def remove_personal_resource(
    user_id: str,
    resource_type: str,
    resource_id: str,
    db: AsyncSession = Depends(get_db),
):
    """Remove a specific personal resource grant."""
    await db.execute(
        delete(PersonalResource)
        .where(PersonalResource.principal_id == user_id)
        .where(PersonalResource.resource_type == resource_type)
        .where(PersonalResource.resource_id == resource_id)
    )
    await db.commit()
    return {"user_id": user_id, "resource_type": resource_type, "resource_id": resource_id, "removed": True}


@app.get("/users/{user_id}/teams")
async def get_user_teams_endpoint(user_id: str = FPath(...), db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(Principal).where(Principal.id == user_id))
    p = result.scalar_one_or_none()
    if not p:
        raise HTTPException(status_code=404, detail=f"User '{user_id}' not found")

    # Teams: explicit type="team" groups, PLUS domain groups that act as teams
    # (when a domain and team share the same ID, the domain row has both roles).
    # We identify "acting as team" by the presence of a "default_roles" key in metadata_.
    teams_result = await db.execute(
        select(Group)
        .join(GroupMember, Group.id == GroupMember.group_id)
        .where(GroupMember.principal_id == user_id)
        .order_by(Group.id)
    )
    teams = []
    for t in teams_result.scalars().all():
        if t.type == "team" or (t.metadata_ or {}).get("default_roles") is not None:
            teams.append({"id": t.id, "name": t.name, "description": t.description})
    return {"user_id": user_id, "teams": teams}


@app.get("/users/{user_id}/domains")
async def get_user_domains_endpoint(user_id: str = FPath(...), db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(Principal).where(Principal.id == user_id))
    p = result.scalar_one_or_none()
    if not p:
        raise HTTPException(status_code=404, detail=f"User '{user_id}' not found")

    domains_result = await db.execute(
        select(Group)
        .join(GroupMember, Group.id == GroupMember.group_id)
        .where(GroupMember.principal_id == user_id)
        .where(Group.type == "domain")
        .order_by(Group.id)
    )
    domains = []
    for g in domains_result.scalars().all():
        meta = g.metadata_ or {}
        domains.append({
            "id": g.id,
            "name": g.name,
            "relationships": meta.get("relationships", []),
        })

    attrs = p.attributes or {}
    return {
        "user_id": user_id,
        "domains": domains,
        "admin_domains": sorted(attrs.get("admin_domains", [])),
    }


# ── Routes: auth ──────────────────────────────────────────────────────────────

@app.post("/auth/token")
async def issue_token(body: TokenRequest, db: AsyncSession = Depends(get_db)):
    """Demo-only: issue a RS256 JWT for a given user_id."""
    result = await db.execute(select(Principal).where(Principal.id == body.user_id))
    principal_row = result.scalar_one_or_none()
    if not principal_row:
        raise HTTPException(status_code=404, detail=f"User '{body.user_id}' not found")

    book = await _get_user_book(body.user_id, db)
    admin_domains = sorted((principal_row.attributes or {}).get("admin_domains", []))
    token = await issue_jwt(body.user_id, principal_row, db)

    return {
        "access_token": token,
        "token_type": "Bearer",
        "expires_in": 3600,
        "algorithm": "RS256",
        "key_id": KEY_ID,
        "jwks_uri": "/.well-known/jwks.json",
        "derived_book": book,
        "admin_domains": admin_domains,
        "user": await _principal_to_view(principal_row, db),
        "note": "RS256 JWT — verify with public key from /.well-known/jwks.json",
    }


@app.post("/auth/login")
async def login(body: LoginRequest, db: AsyncSession = Depends(get_db)):
    """Credential-based login for the Admin UI."""
    result = await db.execute(select(Principal).where(Principal.id == body.username))
    principal_row = result.scalar_one_or_none()
    if not principal_row or not _verify_password(body.password, principal_row.password_hash or ""):
        raise HTTPException(status_code=401, detail="Invalid credentials")
    token = await issue_jwt(body.username, principal_row, db)
    return {
        "access_token": token,
        "token_type": "Bearer",
        "expires_in": 3600,
        "user": await _principal_to_view(principal_row, db),
    }


# ── Routes: roles ─────────────────────────────────────────────────────────────

@app.post("/roles", status_code=201)
async def create_role(body: RoleCreate, db: AsyncSession = Depends(get_db)):
    existing = await db.execute(select(Role).where(Role.id == body.id))
    if existing.scalar_one_or_none():
        raise HTTPException(status_code=409, detail=f"Role '{body.id}' already exists")
    role = Role(
        id=body.id,
        name=body.name,
        description=body.description,
        permissions=body.permissions,
        min_clearance=body.clearance_required,
    )
    db.add(role)
    await db.commit()
    return body.model_dump()


@app.get("/roles")
async def list_roles(db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(Role).order_by(Role.id))
    roles = result.scalars().all()
    return [
        {
            "id": r.id,
            "name": r.name,
            "description": r.description,
            "permissions": r.permissions or [],
            "clearance_required": r.min_clearance or 0,
        }
        for r in roles
    ]


@app.get("/roles/{role_id}")
async def get_role(role_id: str = FPath(...), db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(Role).where(Role.id == role_id))
    r = result.scalar_one_or_none()
    if not r:
        raise HTTPException(status_code=404, detail=f"Role '{role_id}' not found")
    return {
        "id": r.id,
        "name": r.name,
        "description": r.description,
        "permissions": r.permissions or [],
        "clearance_required": r.min_clearance or 0,
    }


@app.put("/roles/{role_id}")
async def update_role(role_id: str, body: RoleCreate, db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(Role).where(Role.id == role_id))
    r = result.scalar_one_or_none()
    if not r:
        raise HTTPException(status_code=404, detail=f"Role '{role_id}' not found")
    r.name = body.name
    r.description = body.description
    r.permissions = body.permissions
    r.min_clearance = body.clearance_required
    await db.commit()
    return {**body.model_dump(), "id": role_id}


@app.delete("/roles/{role_id}", status_code=204)
async def delete_role(role_id: str = FPath(...), db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(Role).where(Role.id == role_id))
    r = result.scalar_one_or_none()
    if not r:
        raise HTTPException(status_code=404, detail=f"Role '{role_id}' not found")
    await db.delete(r)
    await db.commit()


@app.post("/users/{user_id}/roles")
async def assign_user_role(user_id: str, body: dict, db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(Principal).where(Principal.id == user_id))
    if not result.scalar_one_or_none():
        raise HTTPException(status_code=404, detail=f"User '{user_id}' not found")
    role_id = body.get("role_id", "")
    if not role_id:
        raise HTTPException(status_code=400, detail="role_id is required")
    # Idempotent
    existing_check = await db.execute(
        select(PrincipalRole)
        .where(PrincipalRole.principal_id == user_id)
        .where(PrincipalRole.role_id == role_id)
    )
    if not existing_check.scalar_one_or_none():
        db.add(PrincipalRole(principal_id=user_id, role_id=role_id))
        await db.commit()
    roles = await _get_user_roles(user_id, db)
    return {"user_id": user_id, "roles": roles}


@app.delete("/users/{user_id}/roles/{role_id}", status_code=204)
async def remove_user_role(user_id: str, role_id: str, db: AsyncSession = Depends(get_db)):
    await db.execute(
        delete(PrincipalRole)
        .where(PrincipalRole.principal_id == user_id)
        .where(PrincipalRole.role_id == role_id)
    )
    await db.commit()


# ── Routes: teams ─────────────────────────────────────────────────────────────

@app.post("/teams", status_code=201)
async def create_team(body: TeamCreate, db: AsyncSession = Depends(get_db)):
    existing = await db.execute(
        select(Group).where(Group.id == body.id).where(Group.type == "team")
    )
    if existing.scalar_one_or_none():
        raise HTTPException(status_code=409, detail=f"Team '{body.id}' already exists")
    group = Group(
        id=body.id,
        type="team",
        name=body.name,
        slug=body.id,
        description=body.description,
        metadata_={
            "default_roles": body.default_roles,
            "segments": body.segments,
            "allowed_domains": body.allowed_domains,
        },
    )
    db.add(group)
    await db.commit()
    return {**body.model_dump(), "member_count": 0}


@app.get("/teams")
async def list_teams(db: AsyncSession = Depends(get_db)):
    result = await db.execute(
        select(Group).where(Group.type == "team").order_by(Group.id)
    )
    teams = result.scalars().all()
    out = []
    for t in teams:
        count_result = await db.execute(
            select(func.count(GroupMember.principal_id)).where(GroupMember.group_id == t.id)
        )
        meta = t.metadata_ or {}
        out.append({
            "id": t.id,
            "name": t.name,
            "description": t.description,
            "default_roles": meta.get("default_roles", []),
            "segments": meta.get("segments", []),
            "allowed_domains": meta.get("allowed_domains", []),
            "member_count": count_result.scalar() or 0,
        })
    return out


@app.get("/teams/{team_id}")
async def get_team(team_id: str = FPath(...), db: AsyncSession = Depends(get_db)):
    result = await db.execute(
        select(Group).where(Group.id == team_id).where(Group.type == "team")
    )
    t = result.scalar_one_or_none()
    if not t:
        raise HTTPException(status_code=404, detail=f"Team '{team_id}' not found")

    members_result = await db.execute(
        select(Principal)
        .join(GroupMember, Principal.id == GroupMember.principal_id)
        .where(GroupMember.group_id == team_id)
        .order_by(Principal.id)
    )
    members = [
        {"id": p.id, "name": p.name, "email": p.email}
        for p in members_result.scalars().all()
    ]
    meta = t.metadata_ or {}
    return {
        "id": t.id,
        "name": t.name,
        "description": t.description,
        "default_roles": meta.get("default_roles", []),
        "segments": meta.get("segments", []),
        "allowed_domains": meta.get("allowed_domains", []),
        "members": members,
        "member_count": len(members),
    }


@app.put("/teams/{team_id}")
async def update_team(team_id: str, body: TeamCreate, db: AsyncSession = Depends(get_db)):
    result = await db.execute(
        select(Group).where(Group.id == team_id).where(Group.type == "team")
    )
    t = result.scalar_one_or_none()
    if not t:
        raise HTTPException(status_code=404, detail=f"Team '{team_id}' not found")
    t.name = body.name
    t.description = body.description
    t.metadata_ = {
        "default_roles": body.default_roles,
        "segments": body.segments,
        "allowed_domains": body.allowed_domains,
    }
    await db.commit()
    return {**body.model_dump(), "id": team_id}


@app.delete("/teams/{team_id}", status_code=204)
async def delete_team(team_id: str = FPath(...), db: AsyncSession = Depends(get_db)):
    result = await db.execute(
        select(Group).where(Group.id == team_id).where(Group.type == "team")
    )
    t = result.scalar_one_or_none()
    if not t:
        raise HTTPException(status_code=404, detail=f"Team '{team_id}' not found")
    await db.delete(t)
    await db.commit()


@app.post("/teams/{team_id}/members", status_code=201)
async def add_team_member(team_id: str, body: MemberAdd, db: AsyncSession = Depends(get_db)):
    t_result = await db.execute(
        select(Group).where(Group.id == team_id).where(Group.type == "team")
    )
    team = t_result.scalar_one_or_none()
    if not team:
        raise HTTPException(status_code=404, detail=f"Team '{team_id}' not found")

    p_result = await db.execute(select(Principal).where(Principal.id == body.user_id))
    if not p_result.scalar_one_or_none():
        raise HTTPException(status_code=404, detail=f"User '{body.user_id}' not found")

    # Idempotent: skip if already a member
    existing_check = await db.execute(
        select(GroupMember)
        .where(GroupMember.group_id == team_id)
        .where(GroupMember.principal_id == body.user_id)
    )
    if not existing_check.scalar_one_or_none():
        db.add(GroupMember(group_id=team_id, principal_id=body.user_id))

        # Assign team's default roles
        meta = team.metadata_ or {}
        for role_id in meta.get("default_roles", []):
            role_check = await db.execute(
                select(PrincipalRole)
                .where(PrincipalRole.principal_id == body.user_id)
                .where(PrincipalRole.role_id == role_id)
            )
            if not role_check.scalar_one_or_none():
                db.add(PrincipalRole(principal_id=body.user_id, role_id=role_id))

        await db.commit()

    return {"team_id": team_id, "user_id": body.user_id, "added": True}


@app.delete("/teams/{team_id}/members/{user_id}", status_code=204)
async def remove_team_member(team_id: str, user_id: str, db: AsyncSession = Depends(get_db)):
    await db.execute(
        delete(GroupMember)
        .where(GroupMember.group_id == team_id)
        .where(GroupMember.principal_id == user_id)
    )
    await db.commit()


@app.get("/teams/{team_id}/members")
async def list_team_members(team_id: str = FPath(...), db: AsyncSession = Depends(get_db)):
    t_result = await db.execute(
        select(Group).where(Group.id == team_id).where(Group.type == "team")
    )
    if not t_result.scalar_one_or_none():
        raise HTTPException(status_code=404, detail=f"Team '{team_id}' not found")

    members_result = await db.execute(
        select(Principal)
        .join(GroupMember, Principal.id == GroupMember.principal_id)
        .where(GroupMember.group_id == team_id)
        .order_by(Principal.id)
    )
    members = []
    for p in members_result.scalars().all():
        roles = await _get_user_roles(p.id, db)
        members.append({"id": p.id, "name": p.name, "email": p.email, "roles": roles})
    return {"team_id": team_id, "members": members}


# ── Routes: domains ───────────────────────────────────────────────────────────

@app.post("/domains", status_code=201)
async def create_domain(body: DomainCreate, db: AsyncSession = Depends(get_db)):
    existing = await db.execute(
        select(Group).where(Group.id == body.id).where(Group.type == "domain")
    )
    if existing.scalar_one_or_none():
        raise HTTPException(status_code=409, detail=f"Domain '{body.id}' already exists")
    group = Group(
        id=body.id,
        type="domain",
        name=body.name,
        slug=body.id,
        description="",
        metadata_={"relationships": body.relationships, "admins": []},
    )
    db.add(group)
    await db.commit()
    return {"id": body.id, "name": body.name, "relationships": body.relationships}


@app.get("/domains")
async def list_domains(db: AsyncSession = Depends(get_db)):
    result = await db.execute(
        select(Group).where(Group.type == "domain").order_by(Group.id)
    )
    domains = result.scalars().all()
    return [
        {
            "id": g.id,
            "name": g.name,
            "relationships": (g.metadata_ or {}).get("relationships", []),
            "admins": sorted((g.metadata_ or {}).get("admins", [])),
        }
        for g in domains
    ]


@app.get("/domains/{domain_id}")
async def get_domain(domain_id: str = FPath(...), db: AsyncSession = Depends(get_db)):
    result = await db.execute(
        select(Group).where(Group.id == domain_id).where(Group.type == "domain")
    )
    g = result.scalar_one_or_none()
    if not g:
        raise HTTPException(status_code=404, detail=f"Domain '{domain_id}' not found")
    meta = g.metadata_ or {}
    return {
        "id": g.id,
        "name": g.name,
        "relationships": meta.get("relationships", []),
        "admins": sorted(meta.get("admins", [])),
    }


@app.put("/domains/{domain_id}/relationships")
async def update_domain_relationships(
    domain_id: str,
    body: RelationshipsUpdate,
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Group).where(Group.id == domain_id).where(Group.type == "domain")
    )
    g = result.scalar_one_or_none()
    if not g:
        raise HTTPException(status_code=404, detail=f"Domain '{domain_id}' not found")
    meta = g.metadata_ or {}
    g.metadata_ = {**meta, "relationships": body.relationships}
    await db.commit()
    return {"domain_id": domain_id, "relationships": body.relationships}


@app.post("/domains/{domain_id}/members", status_code=201)
async def add_domain_member(domain_id: str, body: MemberAdd, db: AsyncSession = Depends(get_db)):
    d_result = await db.execute(
        select(Group).where(Group.id == domain_id).where(Group.type == "domain")
    )
    if not d_result.scalar_one_or_none():
        raise HTTPException(status_code=404, detail=f"Domain '{domain_id}' not found")

    p_result = await db.execute(select(Principal).where(Principal.id == body.user_id))
    if not p_result.scalar_one_or_none():
        raise HTTPException(status_code=404, detail=f"User '{body.user_id}' not found")

    existing_check = await db.execute(
        select(GroupMember)
        .where(GroupMember.group_id == domain_id)
        .where(GroupMember.principal_id == body.user_id)
    )
    if not existing_check.scalar_one_or_none():
        db.add(GroupMember(group_id=domain_id, principal_id=body.user_id))
        await db.commit()

    return {"domain_id": domain_id, "user_id": body.user_id, "added": True}


@app.delete("/domains/{domain_id}/members/{user_id}")
async def remove_domain_member(domain_id: str, user_id: str, db: AsyncSession = Depends(get_db)):
    d_result = await db.execute(
        select(Group).where(Group.id == domain_id).where(Group.type == "domain")
    )
    if not d_result.scalar_one_or_none():
        raise HTTPException(status_code=404, detail=f"Domain '{domain_id}' not found")
    await db.execute(
        delete(GroupMember)
        .where(GroupMember.group_id == domain_id)
        .where(GroupMember.principal_id == user_id)
    )
    await db.commit()
    return {"domain_id": domain_id, "user_id": user_id, "removed": True}


@app.get("/domains/{domain_id}/members")
async def list_domain_members(domain_id: str = FPath(...), db: AsyncSession = Depends(get_db)):
    d_result = await db.execute(
        select(Group).where(Group.id == domain_id).where(Group.type == "domain")
    )
    if not d_result.scalar_one_or_none():
        raise HTTPException(status_code=404, detail=f"Domain '{domain_id}' not found")

    members_result = await db.execute(
        select(GroupMember.principal_id)
        .where(GroupMember.group_id == domain_id)
        .order_by(GroupMember.principal_id)
    )
    members = [row[0] for row in members_result.all()]
    return {"domain_id": domain_id, "members": members}


# ── Routes: domain admins ─────────────────────────────────────────────────────

@app.post("/domains/{domain_id}/admins", status_code=201)
async def grant_domain_admin(
    domain_id: str,
    body: AdminGrant,
    db: AsyncSession = Depends(get_db),
):
    d_result = await db.execute(
        select(Group).where(Group.id == domain_id).where(Group.type == "domain")
    )
    domain_group = d_result.scalar_one_or_none()
    if not domain_group:
        raise HTTPException(status_code=404, detail=f"Domain '{domain_id}' not found")

    p_result = await db.execute(select(Principal).where(Principal.id == body.user_id))
    principal_row = p_result.scalar_one_or_none()
    if not principal_row:
        raise HTTPException(status_code=404, detail=f"User '{body.user_id}' not found")

    # Update domain metadata_["admins"]
    meta = dict(domain_group.metadata_ or {})
    admins = list(meta.get("admins", []))
    if body.user_id not in admins:
        admins.append(body.user_id)
    domain_group.metadata_ = {**meta, "admins": sorted(admins)}

    # Update principal attributes["admin_domains"]
    attrs = dict(principal_row.attributes or {})
    admin_domains = list(attrs.get("admin_domains", []))
    if domain_id not in admin_domains:
        admin_domains.append(domain_id)
    principal_row.attributes = {**attrs, "admin_domains": sorted(admin_domains)}

    await db.commit()
    return {"domain_id": domain_id, "user_id": body.user_id, "granted": True}


@app.delete("/domains/{domain_id}/admins/{user_id}")
async def revoke_domain_admin(domain_id: str, user_id: str, db: AsyncSession = Depends(get_db)):
    d_result = await db.execute(
        select(Group).where(Group.id == domain_id).where(Group.type == "domain")
    )
    domain_group = d_result.scalar_one_or_none()
    if domain_group:
        meta = dict(domain_group.metadata_ or {})
        admins = [a for a in meta.get("admins", []) if a != user_id]
        domain_group.metadata_ = {**meta, "admins": admins}

    p_result = await db.execute(select(Principal).where(Principal.id == user_id))
    principal_row = p_result.scalar_one_or_none()
    if principal_row:
        attrs = dict(principal_row.attributes or {})
        admin_domains = [d for d in attrs.get("admin_domains", []) if d != domain_id]
        principal_row.attributes = {**attrs, "admin_domains": admin_domains}

    await db.commit()
    return {"domain_id": domain_id, "user_id": user_id, "revoked": True}


@app.get("/domains/{domain_id}/admins")
async def list_domain_admins(domain_id: str = FPath(...), db: AsyncSession = Depends(get_db)):
    d_result = await db.execute(
        select(Group).where(Group.id == domain_id).where(Group.type == "domain")
    )
    g = d_result.scalar_one_or_none()
    if not g:
        raise HTTPException(status_code=404, detail=f"Domain '{domain_id}' not found")
    admins = sorted((g.metadata_ or {}).get("admins", []))
    return {"domain_id": domain_id, "admins": admins}


# ── Routes: policy ────────────────────────────────────────────────────────────

import pathlib as _pathlib

from policy_agent import (
    apply_policy as _apply_policy,
    generate_policy as _gen_policy,
    list_policies as _list_policies,
    validate_policy_yaml,
)

CERBOS_POLICIES_DIR = os.getenv("CERBOS_POLICIES_DIR", "/cerbos-policies")


@app.post("/admin/policies/generate")
async def policy_generate(body: PolicyIntent, db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(Role).order_by(Role.id))
    roles_list = result.scalars().all()
    existing_roles = [
        {"id": r.id, "name": r.name, "description": r.description}
        for r in roles_list
    ]
    return _gen_policy(body.model_dump(), existing_roles)


@app.post("/admin/policies/validate")
def policy_validate(body: PolicyValidateRequest):
    return validate_policy_yaml(body.yaml)


@app.post("/admin/policies/apply")
def policy_apply(body: PolicyApplyRequest):
    v = validate_policy_yaml(body.yaml)
    if not v["valid"]:
        raise HTTPException(
            status_code=400,
            detail={"message": "Policy is invalid", "errors": v["errors"]},
        )
    result = _apply_policy(body.yaml, body.filename)
    if not result["applied"]:
        raise HTTPException(status_code=500, detail=result.get("error", "Failed to apply policy"))
    return result


@app.get("/admin/policies")
def admin_list_policies():
    return {"policies": _list_policies()}


@app.get("/admin/policy-resources")
def list_policy_resources():
    """Scan Cerbos policy files and return the unique resource types defined."""
    resources: list[str] = []
    d = _pathlib.Path(CERBOS_POLICIES_DIR)
    if d.exists():
        for f in sorted(d.glob("*.yaml")):
            try:
                doc = yaml.safe_load(f.read_text()) or {}
                rp = doc.get("resourcePolicy", {}) if isinstance(doc, dict) else {}
                resource = rp.get("resource")
                if resource and resource not in resources:
                    resources.append(resource)
            except Exception:
                pass
    # Always include defaults so the UI has something to show
    for default in ("agent", "relationship", "domain"):
        if default not in resources:
            resources.append(default)
    return {"resources": sorted(resources)}


@app.get("/admin/segments")
async def list_segments(db: AsyncSession = Depends(get_db)):
    """Return all unique segment values from principal attributes."""
    result = await db.execute(select(Principal.attributes))
    segments: set[str] = set()
    for (attrs,) in result.all():
        if attrs and isinstance(attrs, dict):
            for seg in attrs.get("segments") or []:
                if isinstance(seg, str):
                    segments.add(seg)
    return {"segments": sorted(segments)}
