#!/usr/bin/env python3
"""
seed-iam-users.py — Idempotent Python seeder for Conduit's IAM (Axiom) demo data.

This is the SINGLE source of truth for the demo login identities (users), their per-segment
classification map (segments), roles, teams (groups), and the tenant they live in. It replaces
the demo-DATA that used to accrete across Flyway migrations V4/V5/V7/V8 (those files are now
neutralized no-ops — see their headers). The STRUCTURAL schema (V1 DDL) stays in Flyway.

Why Python (not SQL): the user wants demo data out of Flyway migrations, and BCrypt password
hashes cannot be computed in SQL. This seeder computes BCrypt(SEED_PASSWORD) itself — no
placeholder + startup-DataSeeder dance required (it also fixes any leftover SEED_REPLACE_ME rows
from the structural V1/V2/V3 migrations so it is self-sufficient).

Design goals the Axiom multi-tenant work (story A1) builds on, NOT rewrites:
  * `tenant` is a first-class record (TENANTS) — every principal carries an explicit `tenant_id`
    field (today always 'default'). Adding a tenant = one row in TENANTS + flipping a persona's
    tenant_id. No schema change, no code change.
  * `team` is a first-class record (TEAMS = IAM `groups`) with explicit persona membership.
  * The persona -> segment -> team -> tenant mapping is a readable Python data structure
    (PRINCIPALS), not inline SQL.

Idempotent: upserts (ON CONFLICT). Safe to run any number of times. Passwords are only (re)written
when a row is new or still carries the SEED_REPLACE_ME placeholder — an already-hashed password
survives re-runs unchanged.

Target: IAM Postgres (the OIDC login source of truth). NOTE: `scripts/seed-users.sh` seeds a
SEPARATE store — the gateway's Redis principal hashes for the legacy X-User-Id / LibreChat
fallback path. That is a different bounded context and is intentionally untouched here.

Env (defaults are the in-network compose values):
  IAM_DB_HOST (postgres)  IAM_DB_PORT (5432)  IAM_DB_NAME (meridian)
  IAM_DB_USER (meridian)  IAM_DB_PASSWORD (meridian_dev)
  SEED_PASSWORD (Meridian@2024)  — the shared demo password for every seeded persona
"""

from __future__ import annotations

import os
import sys
from dataclasses import dataclass, field
from typing import Any

import bcrypt
import psycopg
from psycopg.types.json import Jsonb

# ── The one place the multi-tenant story (A1) extends ────────────────────────────────
DEFAULT_TENANT = "default"

# Per-segment classification ladder (matches the tenant classification_schema).
# internal < confidential < confidential-pii. The per-segment tier is the ceiling a
# principal holds IN that business line; there is no numeric clearance.
CLASS_INTERNAL = "internal"
CLASS_CONF = "confidential"
CLASS_PII = "confidential-pii"


@dataclass(frozen=True)
class Tenant:
    id: str
    name: str
    slug: str
    classification_schema: list[dict[str, Any]]


@dataclass(frozen=True)
class Role:
    name: str
    permissions: list[str]
    description: str


@dataclass(frozen=True)
class Team:
    """An IAM `group` — a first-class team a principal belongs to."""
    name: str
    domain_id: str
    description: str
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class Principal:
    id: str
    username: str
    email: str
    display_name: str
    title: str
    department: str
    classification: str
    # segments: per-segment classification MAP {segment -> tier}. Empty {} for non-chat
    # admins (they authorize via role/permission, not segments).
    segments: dict[str, str]
    roles: list[str]
    teams: list[str]
    tenant_id: str = DEFAULT_TENANT
    admin_domains: list[str] = field(default_factory=list)
    # Optional personal_resources ("book") rows: {resource_type, resource_id, metadata}.
    # The coverage SERVICE — not IAM personal_resources — is the real chat entitlement gate;
    # these exist only for admin-UI display / legacy servicing demos.
    resources: list[dict[str, Any]] = field(default_factory=list)


# ── TENANTS ──────────────────────────────────────────────────────────────────────────
TENANTS: list[Tenant] = [
    Tenant(
        id="default",
        name="Default Tenant",
        slug="default",
        classification_schema=[
            {"name": "internal", "rank": 1,
             "description": "Internal staff — general, non-sensitive business data"},
            {"name": "confidential", "rank": 2,
             "description": "Confidential — relationship managers and above"},
            {"name": "confidential-pii", "rank": 3,
             "description": "Confidential + PII — senior staff; personal and trading data"},
        ],
    ),
]

# ── ROLES (only the runtime roles these personas use; V1 already ships the admin roles) ─
ROLES: list[Role] = [
    Role("chat_user",
         ["relationships:read", "holdings:read", "risk:read"],
         "Runtime chat user — front door to enterprise intelligence (ABAC over segments)"),
    Role("conduit_admin",
         ["insights:read"],
         "Conduit Insights administrator — read-only access to the native analytics boards"),
]

# ── TEAMS (IAM groups) ─────────────────────────────────────────────────────────────────
TEAMS: list[Team] = [
    Team("wealth-private-banking", "wealth", "Wealth Private Banking domain team",
         {"segments": ["wealth"], "allowedDomains": ["wealth"], "defaultRoles": ["chat_user"]}),
    Team("insurance-underwriting", "insurance", "Insurance Underwriting domain team",
         {"segments": ["insurance"], "allowedDomains": ["insurance"], "defaultRoles": ["chat_user"]}),
    Team("asset-servicing", "servicing", "Asset Servicing operations team",
         {"segments": ["servicing"], "allowedDomains": ["servicing"], "defaultRoles": ["chat_user"]}),
    Team("commercial-banking", "servicing", "Commercial Banking team",
         {"segments": ["servicing"], "allowedDomains": ["servicing"], "defaultRoles": ["chat_user"]}),
    Team("treasury", "servicing", "Treasury Operations team",
         {"segments": ["servicing"], "allowedDomains": ["servicing"], "defaultRoles": ["chat_user"]}),
    Team("human-resources", "hr", "Human Resources team",
         {"segments": ["hr"], "allowedDomains": ["hr"], "defaultRoles": ["chat_user"]}),
    Team("platform", "platform", "Platform / analytics team",
         {"segments": [], "allowedDomains": ["platform"], "defaultRoles": ["conduit_admin"]}),
]

# ── PRINCIPALS — the persona -> segment -> team -> tenant mapping ────────────────────────
# Every persona is login-capable with SEED_PASSWORD. `teams=[]` is deliberate for rm_guest
# (structurally in the wealth line but with no coverage book -> coverage service denies every
# entity: the live "no-domain-membership" denial demo).
PRINCIPALS: list[Principal] = [
    # ── Chat personas (front door). Runtime role = chat_user. ──
    Principal("rm_jane", "rm_jane", "rm.jane@meridian.local",
              "Jane Kowalski", "Relationship Manager", "Wealth Management",
              CLASS_CONF, {"wealth": CLASS_PII, "servicing": CLASS_CONF},
              ["chat_user"], ["wealth-private-banking"]),
    Principal("rm_carlos", "rm_carlos", "carlos.mendez@meridian.demo",
              "Carlos Mendez", "Senior Relationship Manager", "Wealth Management",
              CLASS_PII, {"wealth": CLASS_PII},
              ["chat_user"], ["wealth-private-banking"]),
    Principal("rm_guest", "rm_guest", "guest.rm@meridian.demo",
              "Guest RM", "Relationship Manager (Unassigned)", "Wealth Management",
              CLASS_CONF, {"wealth": CLASS_PII},
              ["chat_user"], []),
    Principal("uw_sam", "uw_sam", "sam.underwriter@meridian.demo",
              "Sam Underwriter", "Underwriter", "Insurance Underwriting",
              CLASS_CONF, {"insurance": CLASS_PII},
              ["chat_user"], ["insurance-underwriting"]),
    # uw_dana — previously referenced by the insurance-coverage book (owns POL-88003) but
    # never actually seeded as a login identity. Added here so that persona is real.
    Principal("uw_dana", "uw_dana", "dana.underwriter@meridian.demo",
              "Dana Weaver", "Underwriter", "Insurance Underwriting",
              CLASS_CONF, {"insurance": CLASS_PII},
              ["chat_user"], ["insurance-underwriting"]),
    # analyst_amy — wealth @ confidential (exercises the classification gate: research yes,
    # PII holdings no). Was V5; now Python-owned.
    Principal("analyst_amy", "analyst_amy", "amy.analyst@meridian.demo",
              "Amy Analyst", "Investment Analyst", "Wealth Management",
              CLASS_CONF, {"wealth": CLASS_CONF},
              ["chat_user"], ["wealth-private-banking"]),

    # ── Demo bankers across business lines (were V8) — spread for Conduit Insights ──
    Principal("rm_nakamura", "rm_nakamura", "kenji.nakamura@meridian.demo",
              "Kenji Nakamura", "Private Bank Relationship Manager", "Wealth Management",
              CLASS_PII, {"wealth": CLASS_PII},
              ["chat_user"], ["wealth-private-banking"]),
    Principal("comm_banker_okoro", "comm_banker_okoro", "adaeze.okoro@meridian.demo",
              "Adaeze Okoro", "Commercial Banker", "Commercial Banking",
              CLASS_CONF, {"servicing": CLASS_CONF},
              ["chat_user"], ["commercial-banking"]),
    Principal("wealth_adv_bianchi", "wealth_adv_bianchi", "luca.bianchi@meridian.demo",
              "Luca Bianchi", "Wealth Advisor", "Wealth Advisory",
              CLASS_CONF, {"wealth": CLASS_CONF},
              ["chat_user"], ["wealth-private-banking"]),
    Principal("ops_analyst_singh", "ops_analyst_singh", "priya.singh@meridian.demo",
              "Priya Singh", "Operations Analyst", "Asset Servicing",
              CLASS_PII, {"servicing": CLASS_PII},
              ["chat_user"], ["asset-servicing"],
              resources=[{"resource_type": "relationship", "resource_id": "REL-00188",
                          "metadata": {"name": "Okafor Family Account", "segment": "servicing",
                                       "purpose": "settlement-risk-demo"}}]),
    Principal("ins_uw_costa", "ins_uw_costa", "marco.costa@meridian.demo",
              "Marco Costa", "Insurance Underwriter", "Insurance Underwriting",
              CLASS_PII, {"insurance": CLASS_PII},
              ["chat_user"], ["insurance-underwriting"]),
    Principal("hr_partner_lund", "hr_partner_lund", "astrid.lund@meridian.demo",
              "Astrid Lund", "HR Business Partner", "Human Resources",
              CLASS_CONF, {"hr": CLASS_CONF},
              ["chat_user"], ["human-resources"]),
    Principal("treasury_moreau", "treasury_moreau", "camille.moreau@meridian.demo",
              "Camille Moreau", "Treasury Operations Specialist", "Treasury",
              CLASS_CONF, {"servicing": CLASS_CONF},
              ["chat_user"], ["treasury"]),
    Principal("multi_rm_fischer", "multi_rm_fischer", "hans.fischer@meridian.demo",
              "Hans Fischer", "Multi-line Relationship Manager", "Private Banking",
              CLASS_PII, {"wealth": CLASS_PII, "insurance": CLASS_CONF},
              ["chat_user"], ["wealth-private-banking", "insurance-underwriting"]),

    # ── Conduit Insights admin (was V7) — least-privilege analytics gate ──
    Principal("insights_admin", "insights_admin", "insights_admin@meridian.demo",
              "Insights Admin", "Analytics Administrator", "Platform",
              CLASS_INTERNAL, {},
              ["conduit_admin"], ["platform"]),
]


# ── Upsert engine (idempotent) ────────────────────────────────────────────────────────
def _bcrypt(password: str) -> str:
    # strength 10 + $2a$ prefix == Spring Security BCryptPasswordEncoder defaults.
    return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt(rounds=10, prefix=b"2a")).decode("utf-8")


def _attributes(p: Principal) -> dict[str, Any]:
    return {
        "classification": p.classification,
        "segments": p.segments,
        "admin_domains": p.admin_domains,
        "display_name": p.display_name,
        "title": p.title,
        "department": p.department,
    }


def upsert_tenants(cur) -> None:
    for t in TENANTS:
        cur.execute(
            """
            INSERT INTO tenants (id, name, slug, classification_schema)
            VALUES (%s, %s, %s, %s)
            ON CONFLICT (id) DO UPDATE
              SET name = EXCLUDED.name,
                  slug = EXCLUDED.slug,
                  classification_schema = EXCLUDED.classification_schema
            """,
            (t.id, t.name, t.slug, Jsonb(t.classification_schema)),
        )
    print(f"[seed-iam] tenants: {len(TENANTS)} upserted")


def upsert_roles(cur) -> None:
    for tenant in TENANTS:
        for r in ROLES:
            cur.execute(
                """
                INSERT INTO roles (tenant_id, name, permissions, description)
                VALUES (%s, %s, %s, %s)
                ON CONFLICT (tenant_id, name) DO UPDATE
                  SET permissions = EXCLUDED.permissions,
                      description = EXCLUDED.description
                """,
                (tenant.id, r.name, Jsonb(r.permissions), r.description),
            )
    print(f"[seed-iam] roles: {len(ROLES)} upserted per tenant")


def upsert_teams(cur) -> None:
    for tenant in TENANTS:
        for team in TEAMS:
            cur.execute(
                """
                INSERT INTO groups (tenant_id, name, domain_id, description, metadata)
                VALUES (%s, %s, %s, %s, %s)
                ON CONFLICT (tenant_id, name) DO UPDATE
                  SET domain_id = EXCLUDED.domain_id,
                      description = EXCLUDED.description,
                      metadata = EXCLUDED.metadata
                """,
                (tenant.id, team.name, team.domain_id, team.description, Jsonb(team.metadata)),
            )
    print(f"[seed-iam] teams (groups): {len(TEAMS)} upserted per tenant")


def upsert_principals(cur, pw_hash_for) -> None:
    for p in PRINCIPALS:
        # Password: set on INSERT, and repair a leftover SEED_REPLACE_ME placeholder, but
        # NEVER overwrite an already-hashed password on re-run (stable, non-churning).
        cur.execute(
            """
            INSERT INTO principals (id, tenant_id, username, email, password_hash, is_active, attributes)
            VALUES (%s, %s, %s, %s, %s, TRUE, %s)
            ON CONFLICT (id) DO UPDATE
              SET tenant_id = EXCLUDED.tenant_id,
                  username = EXCLUDED.username,
                  email = EXCLUDED.email,
                  is_active = TRUE,
                  attributes = EXCLUDED.attributes,
                  password_hash = CASE
                      WHEN principals.password_hash = 'SEED_REPLACE_ME'
                      THEN EXCLUDED.password_hash
                      ELSE principals.password_hash
                  END,
                  updated_at = now()
            """,
            (p.id, p.tenant_id, p.username, p.email, pw_hash_for(p.id), Jsonb(_attributes(p))),
        )
        _set_principal_roles(cur, p)
        _set_principal_teams(cur, p)
        _set_principal_resources(cur, p)
    print(f"[seed-iam] principals: {len(PRINCIPALS)} upserted (roles + teams + resources reconciled)")


def _set_principal_resources(cur, p: Principal) -> None:
    for r in p.resources:
        cur.execute(
            """
            INSERT INTO personal_resources (tenant_id, principal_id, resource_type, resource_id, metadata)
            VALUES (%s, %s, %s, %s, %s)
            ON CONFLICT (principal_id, resource_type, resource_id) DO UPDATE
              SET metadata = EXCLUDED.metadata
            """,
            (p.tenant_id, p.id, r["resource_type"], r["resource_id"], Jsonb(r.get("metadata", {}))),
        )


def _set_principal_roles(cur, p: Principal) -> None:
    """Declaratively reconcile principal_roles to exactly p.roles (drops stale grants such as
    the retired relationship_manager, adds the desired ones)."""
    cur.execute(
        "SELECT id, name FROM roles WHERE tenant_id = %s AND name = ANY(%s)",
        (p.tenant_id, p.roles),
    )
    rows = cur.fetchall()
    desired = [row[0] for row in rows]
    missing = set(p.roles) - {row[1] for row in rows}
    if missing:
        raise RuntimeError(f"principal {p.id}: unknown role(s) {sorted(missing)} — add to ROLES or V1")
    if desired:
        cur.execute(
            "DELETE FROM principal_roles WHERE principal_id = %s AND NOT (role_id = ANY(%s))",
            (p.id, desired),
        )
    else:
        cur.execute("DELETE FROM principal_roles WHERE principal_id = %s", (p.id,))
    for role_id in desired:
        cur.execute(
            """
            INSERT INTO principal_roles (principal_id, role_id, tenant_id)
            VALUES (%s, %s, %s) ON CONFLICT DO NOTHING
            """,
            (p.id, role_id, p.tenant_id),
        )


def _set_principal_teams(cur, p: Principal) -> None:
    """Declaratively reconcile group_members to exactly p.teams."""
    cur.execute(
        "SELECT id FROM groups WHERE tenant_id = %s AND name = ANY(%s)",
        (p.tenant_id, p.teams or [""]),
    )
    desired = [row[0] for row in cur.fetchall()]
    if len(desired) != len(p.teams):
        raise RuntimeError(f"principal {p.id}: unknown team(s) in {p.teams} — add to TEAMS")
    if desired:
        cur.execute(
            "DELETE FROM group_members WHERE principal_id = %s AND NOT (group_id = ANY(%s))",
            (p.id, desired),
        )
    else:
        cur.execute("DELETE FROM group_members WHERE principal_id = %s", (p.id,))
    for group_id in desired:
        cur.execute(
            "INSERT INTO group_members (group_id, principal_id) VALUES (%s, %s) ON CONFLICT DO NOTHING",
            (group_id, p.id),
        )


def main() -> int:
    host = os.environ.get("IAM_DB_HOST", "postgres")
    port = os.environ.get("IAM_DB_PORT", "5432")
    dbname = os.environ.get("IAM_DB_NAME", "meridian")
    user = os.environ.get("IAM_DB_USER", "meridian")
    password = os.environ.get("IAM_DB_PASSWORD", "meridian_dev")
    seed_password = os.environ.get("SEED_PASSWORD", "Meridian@2024")

    # One fresh BCrypt hash per principal (distinct salts) — only ever written on
    # first insert / placeholder repair, so re-runs do not churn.
    hashes = {p.id: _bcrypt(seed_password) for p in PRINCIPALS}

    conninfo = f"host={host} port={port} dbname={dbname} user={user} password={password}"
    print(f"[seed-iam] connecting to IAM Postgres {host}:{port}/{dbname} as {user}")
    try:
        with psycopg.connect(conninfo, connect_timeout=10) as conn:
            with conn.cursor() as cur:
                upsert_tenants(cur)
                upsert_roles(cur)
                upsert_teams(cur)
                upsert_principals(cur, lambda pid: hashes[pid])
            conn.commit()
    except Exception as exc:  # noqa: BLE001 — surface loudly, exit non-zero for seed-all.sh
        print(f"[seed-iam] ERROR: {exc}", file=sys.stderr)
        return 1

    print(f"[seed-iam] DONE — {len(PRINCIPALS)} principals, {len(TEAMS)} teams, "
          f"{len(ROLES)} roles, {len(TENANTS)} tenant(s). Password: '{seed_password}'.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
