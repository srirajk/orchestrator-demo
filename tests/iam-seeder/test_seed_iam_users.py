"""
Proof for scripts/seed-iam-users.py — runs the real seeder against a THROWAWAY Postgres
(testcontainers), never any live/demo database.

Covers:
  * schema parity — applies the STRUCTURAL Flyway migration V1__init.sql (DDL) exactly as
    iam-service would, then runs the seeder on top (it is self-sufficient — no V2..V9 needed).
  * login-readiness — a Python-owned persona (ops_analyst_singh) exists with a BCrypt hash that
    verifies against 'Meridian@2024' (the exact check Spring's BCryptPasswordEncoder performs),
    with the correct per-segment map, role, team and tenant_id.
  * no regression — rm_jane (seeded structurally in V1 as SEED_REPLACE_ME) ends up login-ready.
  * idempotency — running the seeder twice yields identical row counts and no error.

Run:  pip install "psycopg[binary]" bcrypt testcontainers pytest
      pytest tests/iam-seeder/ -v
Requires Docker (for the throwaway Postgres container).
"""

import importlib.util
import pathlib
import sys

import bcrypt
import psycopg
import pytest
from testcontainers.postgres import PostgresContainer

REPO = pathlib.Path(__file__).resolve().parents[2]
SEEDER_PATH = REPO / "scripts" / "seed-iam-users.py"
V1_DDL = REPO / "iam-service" / "src" / "main" / "resources" / "db" / "migration" / "V1__init.sql"
PASSWORD = "Meridian@2024"


def _load_seeder():
    spec = importlib.util.spec_from_file_location("seed_iam_users", SEEDER_PATH)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = mod  # required so dataclasses can resolve string annotations
    spec.loader.exec_module(mod)
    return mod


def _run_seeder(mod, dsn):
    host, port, db, user, pw = dsn
    import os
    os.environ.update({
        "IAM_DB_HOST": host, "IAM_DB_PORT": str(port), "IAM_DB_NAME": db,
        "IAM_DB_USER": user, "IAM_DB_PASSWORD": pw, "SEED_PASSWORD": PASSWORD,
    })
    return mod.main()


@pytest.fixture(scope="module")
def seeded_db():
    with PostgresContainer("postgres:16-alpine", dbname="meridian",
                           username="meridian", password="meridian_dev") as pg:
        dsn = (pg.get_container_host_ip(), pg.get_exposed_port(5432),
               "meridian", "meridian", "meridian_dev")
        conninfo = (f"host={dsn[0]} port={dsn[1]} dbname={dsn[2]} "
                    f"user={dsn[3]} password={dsn[4]}")
        # Apply the STRUCTURAL migration exactly as iam-service Flyway would (DDL + base seed).
        with psycopg.connect(conninfo) as conn, conn.cursor() as cur:
            cur.execute(V1_DDL.read_text())
            conn.commit()

        mod = _load_seeder()
        rc1 = _run_seeder(mod, dsn)
        assert rc1 == 0, "first seeder run must exit 0"
        yield conninfo, mod, dsn


def test_python_owned_persona_is_login_ready(seeded_db):
    conninfo, _, _ = seeded_db
    with psycopg.connect(conninfo) as conn, conn.cursor() as cur:
        cur.execute("SELECT password_hash, tenant_id, attributes, is_active "
                    "FROM principals WHERE id = 'ops_analyst_singh'")
        row = cur.fetchone()
    assert row is not None, "ops_analyst_singh must be seeded by Python"
    password_hash, tenant_id, attributes, is_active = row
    assert is_active is True
    assert tenant_id == "default"
    # The exact check Spring's BCryptPasswordEncoder.matches performs.
    assert bcrypt.checkpw(PASSWORD.encode(), password_hash.encode())
    assert attributes["segments"] == {"servicing": "confidential-pii"}
    assert attributes["classification"] == "confidential-pii"
    assert attributes["display_name"] == "Priya Singh"


def test_persona_role_and_team(seeded_db):
    conninfo, _, _ = seeded_db
    with psycopg.connect(conninfo) as conn, conn.cursor() as cur:
        cur.execute("""
            SELECT r.name FROM principal_roles pr
            JOIN roles r ON r.id = pr.role_id
            WHERE pr.principal_id = 'ops_analyst_singh'
        """)
        roles = {r[0] for r in cur.fetchall()}
        cur.execute("""
            SELECT g.name FROM group_members gm
            JOIN groups g ON g.id = gm.group_id
            WHERE gm.principal_id = 'ops_analyst_singh'
        """)
        teams = {t[0] for t in cur.fetchall()}
    assert roles == {"chat_user"}
    assert teams == {"asset-servicing"}


def test_structural_persona_still_logs_in(seeded_db):
    """rm_jane (V1, SEED_REPLACE_ME) must be login-ready after Python reseed — no regression."""
    conninfo, _, _ = seeded_db
    with psycopg.connect(conninfo) as conn, conn.cursor() as cur:
        cur.execute("SELECT password_hash, attributes FROM principals WHERE id = 'rm_jane'")
        password_hash, attributes = cur.fetchone()
    assert password_hash != "SEED_REPLACE_ME", "seeder must repair the V1 placeholder"
    assert bcrypt.checkpw(PASSWORD.encode(), password_hash.encode())
    assert attributes["segments"] == {"wealth": "confidential-pii", "servicing": "confidential"}


def test_relationship_manager_retired_for_chat_persona(seeded_db):
    """rm_jane got relationship_manager in V1; the declarative role reconcile must retire it."""
    conninfo, _, _ = seeded_db
    with psycopg.connect(conninfo) as conn, conn.cursor() as cur:
        cur.execute("""
            SELECT r.name FROM principal_roles pr
            JOIN roles r ON r.id = pr.role_id
            WHERE pr.principal_id = 'rm_jane'
        """)
        roles = {r[0] for r in cur.fetchall()}
    assert roles == {"chat_user"}, f"expected only chat_user, got {roles}"


def test_idempotent_second_run(seeded_db):
    conninfo, mod, dsn = seeded_db
    with psycopg.connect(conninfo) as conn, conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM principals")
        before_p = cur.fetchone()[0]
        cur.execute("SELECT count(*) FROM principal_roles")
        before_pr = cur.fetchone()[0]
        cur.execute("SELECT count(*) FROM group_members")
        before_gm = cur.fetchone()[0]
        cur.execute("SELECT password_hash FROM principals WHERE id = 'ops_analyst_singh'")
        hash_before = cur.fetchone()[0]

    rc2 = _run_seeder(mod, dsn)
    assert rc2 == 0, "second seeder run must exit 0"

    with psycopg.connect(conninfo) as conn, conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM principals")
        assert cur.fetchone()[0] == before_p
        cur.execute("SELECT count(*) FROM principal_roles")
        assert cur.fetchone()[0] == before_pr
        cur.execute("SELECT count(*) FROM group_members")
        assert cur.fetchone()[0] == before_gm
        # Password not churned on re-run (already-hashed survives).
        cur.execute("SELECT password_hash FROM principals WHERE id = 'ops_analyst_singh'")
        assert cur.fetchone()[0] == hash_before
